"""#138 smoke: real Commerce + Inventory + Shipment/FAKE, separate ephemeral DBs.

Build the three service jars first. Run with Python 3.11+ and cryptography:
  python docs/testing/commerce-reservation-smoke.py --java <JDK21/java> --output <logs>

Only this run's UUID-named Docker container and JVMs are stopped/removed. Catalog
and RS256/JWKS are explicit HTTP fixtures; GHN, Kafka delivery and remote CI are
outside this smoke. No existing databases, accounts, volumes or services change.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def uid() -> str:
    return str(uuid.uuid4())


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def free_port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def call(url: str, token: str | None = None, body=None, key: str | None = None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers)
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(request, timeout=12)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, json.loads(raw) if raw else None, time.monotonic() - started


class Smoke:
    def __init__(self, args):
        self.args = args
        self.backend = Path(__file__).resolve().parents[2]
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.container = "nexus-138-smoke-" + uuid.uuid4().hex[:12]
        self.created_container = False
        self.processes = {}
        self.handles = []
        self.evidence = {"scope": "actual commerce/inventory/shipment FAKE; Catalog/JWKS HTTP fixtures", "cases": []}
        self.catalog = {}
        self.private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.http = None
        self.fixture_port = free_port()
        self.issuer = f"http://127.0.0.1:{self.fixture_port}/issuer"
        self.ports = {name: free_port() for name in ("inventory", "shipment", "commerce")}
        self.password = secrets.token_urlsafe(24)

    def command(self, arguments, data=None):
        result = subprocess.run(arguments, input=data, text=True, capture_output=True, timeout=90,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout or "Command failed")
        return result.stdout.strip()

    def sql(self, database, statement):
        return self.command(["docker", "exec", "-i", self.container, "psql", "-U", "smoke", "-d", database,
                             "-At", "-v", "ON_ERROR_STOP=1"], statement)

    def fixture(self):
        owner = self
        numbers = self.private.public_key().public_numbers()
        jwk = {"kty": "RSA", "alg": "RS256", "use": "sig", "kid": "smoke138",
               "n": b64(numbers.n.to_bytes((numbers.n.bit_length() + 7) // 8, "big")),
               "e": b64(numbers.e.to_bytes((numbers.e.bit_length() + 7) // 8, "big"))}

        class Handler(BaseHTTPRequestHandler):
            # Giữ kết nối HTTP/1.1 đúng với pool của OkHttp/JDK clients trong bài smoke.
            protocol_version = "HTTP/1.1"

            def handle(self):
                try:
                    super().handle()
                except ConnectionResetError:
                    # JVM đang dừng đóng kết nối keep-alive; không phải lỗi xử lý request fixture.
                    pass
            def do_GET(self):
                if self.path == "/jwks":
                    body = {"keys": [jwk]}
                elif self.path.startswith("/api/v1/skus/"):
                    sku = owner.catalog.get(self.path.rsplit("/", 1)[-1])
                    if not sku:
                        self.send_error(404)
                        return
                    body = {"code": 0, "message": "fixture", "data": sku}
                elif self.path.startswith("/api/v1/products/"):
                    product = self.path.rsplit("/", 1)[-1]
                    sku = next((item for item in owner.catalog.values() if item["productId"] == product), None)
                    if not sku:
                        self.send_error(404)
                        return
                    body = {"code": 0, "message": "fixture", "data": {"id": product,
                            "sellerId": sku["sellerId"], "name": "Smoke product", "status": "ACTIVE", "images": []}}
                else:
                    self.send_error(404)
                    return
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.http = ThreadingHTTPServer(("127.0.0.1", self.fixture_port), Handler)
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def token(self, actor):
        now = int(time.time())
        header = b64(json.dumps({"alg": "RS256", "kid": "smoke138", "typ": "JWT"}).encode())
        payload = b64(json.dumps({"sub": actor, "iss": self.issuer, "aud": ["nexus-services"], "iat": now,
                                 "exp": now + 1800, "roles": ["BUYER"],
                                 "privileges": ["ORDER.CREATE", "CHECKOUT.START", "ORDER.VIEW", "SHIPPING.QUOTE"]}).encode())
        message = (header + "." + payload).encode()
        return message.decode() + "." + b64(self.private.sign(message, padding.PKCS1v15(), hashes.SHA256()))

    def start(self, name, extras=()):
        jars = sorted((self.backend / (name + "-service") / "target").glob("*.jar"))
        jars = [jar for jar in jars if not jar.name.endswith("-tests.jar")]
        if len(jars) != 1:
            raise RuntimeError(f"Build {name}-service first; expected one executable jar")
        config = self.backend / "config-service/src/main/resources/config" / (name + "-service.yml")
        args = [self.args.java, "-Duser.timezone=UTC", "-jar", str(jars[0]),
                "--server.port=" + str(self.ports[name]), "--server.address=127.0.0.1",
                "--spring.config.location=" + config.as_uri(), "--spring.application.name=" + name + "-service",
                "--spring.cloud.config.enabled=false",
                "--spring.cloud.config.import-check.enabled=false", "--eureka.client.enabled=false",
                "--spring.cloud.discovery.enabled=false", "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.admin.auto-create=false", "--spring.kafka.listener.missing-topics-fatal=false",
                "--spring.kafka.bootstrap-servers=127.0.0.1:9", "--nexus.outbox.relay.enabled=false",
                "--management.tracing.export.enabled=false", "--spring.jpa.open-in-view=false",
                "--management.endpoints.web.exposure.include=health", "--management.health.defaults.enabled=false",
                f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{self.db_port}/{name}_db",
                "--spring.datasource.username=smoke", "--spring.datasource.password=" + self.password,
                "--nexus.security.jwt.issuer=" + self.issuer, "--nexus.security.jwt.audience=nexus-services",
                f"--nexus.security.jwt.jwk-set-uri=http://127.0.0.1:{self.fixture_port}/jwks", *extras]
        handle = (self.output / (name + ".log")).open("w", encoding="utf-8")
        self.handles.append(handle)
        process = subprocess.Popen(args, cwd=self.output, stdout=handle, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.processes[name] = process
        for _ in range(180):
            if process.poll() is not None:
                raise RuntimeError(f"{name} startup failed; inspect {name}.log")
            try:
                status, _, _ = call(f"http://127.0.0.1:{self.ports[name]}/actuator/health")
                if status == 200:
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.5)
        raise RuntimeError(f"{name} startup timeout")

    def stop(self, name):
        process = self.processes.pop(name, None)
        if process:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    def add_sku(self, available, seller=None):
        sku = uid()
        self.catalog[sku] = {"id": sku, "productId": uid(), "sellerId": seller or uid(), "skuCode": "SMOKE-" + sku[:8],
                             "price": 150000, "status": "ACTIVE",
                             "shippingParcel": {"weightGrams": 1000, "lengthCm": 30, "widthCm": 20, "heightCm": 10}}
        self.sql("inventory_db", "INSERT INTO inventory_balances(sku_id,warehouse_id,total_quantity,available_quantity) "
                 f"VALUES ('{sku}','{self.warehouse}',{available},{available});")
        return sku

    def cart(self, lines):
        actor, cart = uid(), uid()
        statements = [f"INSERT INTO carts(id,user_id) VALUES ('{cart}','{actor}');"]
        for sku, quantity in lines:
            product = self.catalog[sku]["productId"]
            statements.append("INSERT INTO cart_items(cart_id,product_id,sku_id,quantity,price) "
                              f"VALUES ('{cart}','{product}','{sku}',{quantity},150000);")
        self.sql("commerce_db", "\n".join(statements))
        return actor

    def create(self, actor, body=None, key=None):
        request = body or {"paymentMethod": "SEPAY", "shippingAddress": {"recipientName": "Smoke buyer",
                 "phoneNumber": "0901234567", "addressLine": "1 Smoke Street", "wardCode": "1003646",
                 "provinceCode": "1000001", "countryCode": "VN"}}
        return call(f"http://127.0.0.1:{self.ports['commerce']}/api/v1/orders", self.token(actor), request, key or uid())

    def counts(self):
        return (int(self.sql("commerce_db", "SELECT count(*) FROM orders;")),
                int(self.sql("inventory_db", "SELECT count(*) FROM inventory_reservations;")))

    def record(self, name, check, **facts):
        if not check:
            raise AssertionError(name + ": " + json.dumps(facts))
        self.evidence["cases"].append({"name": name, "passed": True, **facts})
        print("PASS " + name, flush=True)

    def run(self):
        self.fixture()
        self.command(["docker", "run", "-d", "--name", self.container, "--label", "nexus.issue=138-smoke",
                      "-e", "POSTGRES_USER=smoke", "-e", "POSTGRES_PASSWORD=" + self.password,
                      "-p", "127.0.0.1::5432", "postgres:18"])
        self.created_container = True
        for _ in range(90):
            try:
                # Chờ server TCP cuối cùng, không nhầm temporary server lúc initdb đang tạo schema.
                self.command(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "smoke"])
                break
            except RuntimeError:
                time.sleep(0.5)
        self.db_port = int(self.command(["docker", "port", self.container, "5432/tcp"]).rsplit(":", 1)[1])
        for database in ("inventory_db", "shipment_db", "commerce_db"):
            self.sql("postgres", f"CREATE DATABASE {database};")
        self.start("inventory")
        self.warehouse = self.sql("inventory_db", "SELECT id FROM warehouses WHERE code='WH_001';")
        self.start("shipment", ["--nexus.shipping.carrier=FAKE", "--nexus.shipping.default-warehouse-id=" + self.warehouse,
                               "--nexus.shipping.default-pickup.province-code=1000001",
                               "--nexus.shipping.default-pickup.ward-code=1003646",
                               "--nexus.shipping.default-pickup.address-line=1 Pickup Street",
                               f"--nexus.shipping.catalog-base-url=http://127.0.0.1:{self.fixture_port}"])
        self.start("commerce", [f"--spring.cloud.openfeign.client.config.catalog-service.url=http://127.0.0.1:{self.fixture_port}",
                               f"--nexus.order.inventory-base-url=http://127.0.0.1:{self.ports['inventory']}",
                               f"--nexus.order.shipping-base-url=http://127.0.0.1:{self.ports['shipment']}"])

        before = self.counts()
        scarce = self.add_sku(1)
        status, body, elapsed = self.create(self.cart([(scarce, 2)]))
        self.record("oversell rejected without order/reservation", status == 409
                    and body["code"] == "COMMERCE_INSUFFICIENT_STOCK" and self.counts() == before,
                    status=status, seconds=elapsed)

        good, bad = self.add_sku(4), self.add_sku(0)
        before = self.counts()
        status, body, _ = self.create(self.cart([(good, 1), (bad, 1)]))
        self.record("two seller rollback is atomic", status == 409 and self.counts() == before
                    and self.sql("inventory_db", f"SELECT available_quantity FROM inventory_balances WHERE sku_id='{good}';") == "4",
                    status=status, problem=body if status != 409 else None)

        seller1, seller2 = self.add_sku(5), self.add_sku(5)
        actor = self.cart([(seller1, 2), (seller2, 1)])
        request = {"paymentMethod": "SEPAY", "shippingAddress": {"recipientName": "Smoke buyer",
                   "phoneNumber": "0901234567", "addressLine": "1 Smoke Street", "wardCode": "1003646",
                   "provinceCode": "1000001", "countryCode": "VN"}}
        before = self.counts()
        preview_status, preview, _ = call(f"http://127.0.0.1:{self.ports['commerce']}/api/v1/checkout/preview",
                                         self.token(actor), request)
        self.record("preview uses authenticated buyer and server shipping fees", preview_status == 200
                    and float(preview["data"]["shippingFee"]) == 60000 and float(preview["data"]["total"]) == 510000
                    and self.counts() == before, status=preview_status)
        key = uid()
        status, body, _ = self.create(actor, key=key)
        group = body.get("data", {}).get("orderGroupId")
        self.record("two seller creation includes per-order fees", status == 201
                    and len(body["data"]["orders"]) == 2 and float(body["data"]["totalAmount"]) == 510000, status=status)
        order_ids = [item["orderId"] for item in body["data"]["orders"]]
        order_set = ",".join("'" + item + "'" for item in order_ids)
        reservations = self.sql("inventory_db", f"SELECT order_id::text||'|'||expires_at::text FROM inventory_reservations WHERE order_id IN ({order_set}) ORDER BY order_id;").splitlines()
        deadlines = self.sql("commerce_db", f"SELECT id::text||'|'||payment_due_at::text FROM orders WHERE order_group_id='{group}' ORDER BY id;").splitlines()
        self.record("reservation owns child and exact deadline", reservations == deadlines and len(reservations) == 2,
                    orderIds=order_ids)
        values = self.sql("commerce_db", f"SELECT bool_and(shipping_info->>'warehouseId'='{self.warehouse}' AND shipping_info#>>'{{recipient,address,wardCode}}'='1003646' AND shipping_info#>>'{{recipient,address,provinceCode}}'='1000001' AND shipping_fee=30000) FROM orders WHERE order_group_id='{group}';")
        group_deadline = self.sql("commerce_db", f"SELECT bool_and(o.payment_due_at=g.due_at) FROM orders o JOIN order_groups g ON g.id=o.order_group_id WHERE g.id='{group}';")
        self.record("trusted shipping snapshot and group deadline", values == "t" and group_deadline == "t")
        url = f"http://127.0.0.1:{self.ports['commerce']}/api/v1/orders/groups/{group}"
        own, _, _ = call(url, self.token(actor))
        other, _, _ = call(url, self.token(uid()))
        self.record("group ownership", own == 200 and other == 403, owner=own, other=other)
        before = self.counts()
        replay_status, replay, _ = self.create(actor, key=key)
        self.record("replay preserves group orders and reservations", replay_status == 200
                    and replay["data"]["orderGroupId"] == group and self.counts() == before, status=replay_status)

        missing_actor = self.cart([(self.add_sku(1), 1)])
        no_ward = json.loads(json.dumps(request))
        del no_ward["shippingAddress"]["wardCode"]
        before = self.counts()
        missing_status, _, _ = self.create(missing_actor, no_ward)
        self.record("missing ward is400 before writes", missing_status == 400 and self.counts() == before,
                    status=missing_status)

        last = self.add_sku(1)
        actors = [self.cart([(last, 1)]), self.cart([(last, 1)])]
        gate = threading.Barrier(2)
        def concurrent_create(buyer):
            gate.wait()
            return self.create(buyer)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(concurrent_create, actors))
        stock = self.sql("inventory_db", f"SELECT total_quantity||'|'||available_quantity||'|'||reserved_quantity FROM inventory_balances WHERE sku_id='{last}';")
        self.record("concurrent last unit has one winner", sorted(result[0] for result in results) == [201, 409]
                    and stock == "1|0|1", statuses=[result[0] for result in results], balance=stock)

        self.stop("shipment")
        before = self.counts()
        status, body, elapsed = self.create(self.cart([(self.add_sku(1), 1)]))
        self.record("shipment down leaves no order or reservation", status == 503 and self.counts() == before,
                    status=status, seconds=elapsed)
        self.start("shipment", ["--nexus.shipping.carrier=FAKE", "--nexus.shipping.default-warehouse-id=" + self.warehouse,
                               "--nexus.shipping.default-pickup.province-code=1000001",
                               "--nexus.shipping.default-pickup.ward-code=1003646",
                               "--nexus.shipping.default-pickup.address-line=1 Pickup Street",
                               f"--nexus.shipping.catalog-base-url=http://127.0.0.1:{self.fixture_port}"])
        self.stop("inventory")
        before = self.counts()
        status, body, elapsed = self.create(self.cart([(self.add_sku(1), 1)]))
        self.record("inventory down returns503 inside3seconds", status == 503 and elapsed < 3 and self.counts() == before,
                    status=status, seconds=elapsed)

    def cleanup(self):
        for name in list(self.processes):
            self.stop(name)
        if self.http:
            self.http.shutdown()
            self.http.server_close()
        for handle in self.handles:
            handle.close()
        if self.created_container:
            self.command(["docker", "rm", "-f", self.container])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    smoke = Smoke(args)
    try:
        smoke.run()
        smoke.evidence["passed"] = True
    except BaseException as error:
        smoke.evidence["passed"] = False
        smoke.evidence["failure"] = str(error)
        raise
    finally:
        try:
            smoke.cleanup()
        finally:
            (smoke.output / "result.json").write_text(json.dumps(smoke.evidence, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
