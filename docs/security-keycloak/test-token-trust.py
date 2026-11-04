#!/usr/bin/env python3
"""Issue #237: real Keycloak -> Gateway -> Catalog/Postgres acceptance.

Requires #236's local-dev/keycloak fixtures, Docker Compose, OpenSSL, Java 21+
and prebuilt executable service jars. Uses stdlib only; never reads private .env.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import URLError

ROOT = Path(__file__).resolve().parents[2]
INFRA = ROOT / "infra/local-dev"
KEYCLOAK = Path(os.environ.get("KEYCLOAK_FIXTURE_DIR", str(INFRA / "keycloak"))).resolve()
SELLER = "68000000-0000-4000-8000-000000000002"
CATEGORY = "69000000-0000-4000-8000-000000000102"


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def run(evidence_dir=None):
    evidence_dir = evidence_dir.resolve() if evidence_dir else None
    sources = [Path(__file__), *sorted((ROOT / "config-service/src/main/resources/config").glob("*.yml"))]
    report = {"started_at_utc": datetime.now(timezone.utc).isoformat(), "status": "running",
              "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "source_sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
              "checks": []}
    if evidence_dir:
        evidence_dir.mkdir(parents=True, exist_ok=True)
        check(not any(evidence_dir.iterdir()), "Use an empty evidence directory")

    def record(label, **details):
        entry = {"label": label, "at_utc": datetime.now(timezone.utc).isoformat(), **details}
        report["checks"].append(entry)
        print(label, flush=True)
        if evidence_dir:
            (evidence_dir / "evidence.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            with (evidence_dir / "summary.log").open("a", encoding="utf-8") as log:
                log.write(entry["at_utc"] + " " + label + "\n")

    for name in ("test-keycloak.py", "realm-nexus.json", "seed-users.sh"):
        check((KEYCLOAK / name).is_file(),
              f"Missing {name}: merge #236 first or set KEYCLOAK_FIXTURE_DIR to its keycloak directory")
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("keycloak_test", KEYCLOAK / "test-keycloak.py")
    kc = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(kc)
    project = "nexus-237-test-" + secrets.token_hex(4)
    processes = []
    with tempfile.TemporaryDirectory(prefix=project) as tmp:
        directory = Path(tmp)
        password = secrets.token_urlsafe(24)
        admin_password = secrets.token_urlsafe(24)
        demo_password = secrets.token_urlsafe(24)
        init = directory / "init.sql"
        init.write_text("CREATE DATABASE auth_db;\nCREATE DATABASE catalog_db;\nCREATE DATABASE user_db;\n")
        compose_config = {
            "services": {
                "postgres": {
                    "image": "pgvector/pgvector:pg18",
                    "environment": {"POSTGRES_USER": "nexus_test", "POSTGRES_PASSWORD": password},
                    "ports": [{"target": 5432, "published": "0", "host_ip": "127.0.0.1"}],
                    "volumes": [f"{init}:/docker-entrypoint-initdb.d/init.sql:ro"],
                    "healthcheck": {"test": ["CMD-SHELL", "pg_isready -U nexus_test -d postgres"],
                                    "interval": "2s", "timeout": "5s", "retries": 30}},
                "auth-service": {
                    "image": "quay.io/keycloak/keycloak:26.7.5",
                    "command": ["start-dev", "--import-realm"],
                    "environment": {"KC_DB": "postgres", "KC_DB_URL": "jdbc:postgresql://postgres:5432/auth_db",
                                    "KC_DB_USERNAME": "nexus_test", "KC_DB_PASSWORD": password,
                                    "KC_BOOTSTRAP_ADMIN_USERNAME": "test-admin",
                                    "KC_BOOTSTRAP_ADMIN_PASSWORD": admin_password,
                                    "JAVA_OPTS_KC_HEAP": "-Xms64m -Xmx512m"},
                    "ports": [{"target": 8080, "published": "0", "host_ip": "127.0.0.1"}],
                    "volumes": [f"{KEYCLOAK / 'realm-nexus.json'}:/opt/keycloak/data/import/nexus-realm.json:ro"],
                    "depends_on": {"postgres": {"condition": "service_healthy"}}},
                "auth-seed": {
                    "image": "quay.io/keycloak/keycloak:26.7.5",
                    "entrypoint": ["/bin/bash", "/opt/nexus/seed-users.sh", "--container"],
                    "environment": {"KEYCLOAK_SERVER_URL": "http://auth-service:8080",
                                    "KEYCLOAK_ADMIN": "test-admin", "KEYCLOAK_ADMIN_PASSWORD": admin_password,
                                    **{f"DEMO_{role}_PASSWORD": demo_password for role in ("ADMIN", "SELLER", "BUYER")}},
                    "volumes": [f"{KEYCLOAK / 'seed-users.sh'}:/opt/nexus/seed-users.sh:ro"]}}}
        compose_file = directory / "compose.json"
        compose_file.write_text(json.dumps(compose_config))
        if os.name != "nt":
            compose_file.chmod(0o600)
        # Ignore any operator Compose variables; this project owns only its resources.
        environment = os.environ.copy()
        for key in ("COMPOSE_FILE", "COMPOSE_PROFILES", "COMPOSE_PROJECT_NAME"):
            environment.pop(key, None)

        def compose(*args, input=None):
            result = subprocess.run(["docker", "compose", "-p", project, "-f", str(compose_file), *args],
                                    cwd=directory, env=environment, input=input, text=True, capture_output=True)
            check(result.returncode == 0, f"Compose {' '.join(args[:2])} failed: {result.stderr[-1500:]}")
            return result.stdout.strip()

        def wait(url, process=None, timeout=180):
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if process and process.poll() is not None:
                    raise AssertionError(f"Service exited before readiness: {url}; see temporary service logs")
                try:
                    if kc.http(url)[0] == 200:
                        return
                except (URLError, TimeoutError, ConnectionError):
                    pass
                time.sleep(1)
            raise AssertionError(f"Readiness timed out: {url}")

        def start(name, args, env):
            jars = [p for p in (ROOT / name / "target").glob("*.jar") if not p.name.endswith(".original")]
            check(len(jars) == 1, f"Run ./mvnw verify in {name} first")
            log = open(directory / f"{name}.log", "w")
            java = str(Path(env["JAVA_HOME"]) / ("bin/java.exe" if os.name == "nt" else "bin/java")) if env.get("JAVA_HOME") else "java"
            process = subprocess.Popen([java, "-Duser.timezone=UTC", "-Xms64m", "-Xmx384m", "-jar", str(jars[0]), *args],
                                       cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
            processes.append((process, log))
            return process

        try:
            record(f"Starting disposable project {project}", project=project)
            compose("up", "-d", "postgres", "auth-service")
            base = "http://" + compose("port", "auth-service", "8080")
            database_port = compose("port", "postgres", "5432").rsplit(":", 1)[1]
            wait(base + "/realms/nexus/.well-known/openid-configuration", timeout=240)
            compose("run", "--rm", "--no-deps", "-T", "auth-seed")
            code, verifier = kc.authorization(base, "seller@nexus.local", demo_password)
            check(code, "Seller PKCE login failed")
            status, tokens = kc.exchange(base, code, verifier)
            check(status == 200, "Seller code exchange failed")
            token = tokens["access_token"]
            claims = kc.verify_token(base, token)
            check(claims["sub"] == SELLER and "SELLER" in claims["roles"]
                  and "PRODUCT.CREATE" in claims["privileges"], "Wrong seller claims")
            record("PASS: real seeded seller PKCE login; Keycloak RS256 signature and flat claims verified",
                   claims={key: claims[key] for key in ("sub", "roles", "privileges", "aud", "iss")},
                   signature_verified=True, realm_fixture_sha256=hashlib.sha256((KEYCLOAK / "realm-nexus.json").read_bytes()).hexdigest())

            config_port, catalog_port, gateway_port = port(), port(), port()
            app_env = environment.copy()
            app_env.update({"KEYCLOAK_ISSUER_URI": base + "/realms/nexus",
                            "KEYCLOAK_JWK_SET_URI": base + "/realms/nexus/protocol/openid-connect/certs",
                            "DB_HOST": "127.0.0.1", "DB_PORT": database_port,
                            "DB_USERNAME": "nexus_test", "DB_PASSWORD": password})
            config = start("config-service", [f"--server.port={config_port}",
                            "--management.tracing.export.zipkin.enabled=false"], app_env)
            config_url = f"http://127.0.0.1:{config_port}"
            wait(config_url + "/actuator/health", config)
            common = ["--spring.config.name=issue237-acceptance",
                      f"--spring.config.import=configserver:{config_url}", "--eureka.client.enabled=false",
                      "--management.tracing.export.zipkin.enabled=false", "--spring.kafka.listener.auto-startup=false",
                      "--spring.kafka.admin.auto-create=false", "--management.tracing.enabled=false",
                      "--spring.kafka.bootstrap-servers=127.0.0.1:1"]
            catalog = start("catalog-service", [*common, "--spring.application.name=catalog-service",
                            f"--server.port={catalog_port}"], app_env)
            catalog_url = f"http://127.0.0.1:{catalog_port}"
            wait(catalog_url + "/api/v1/categories/tree", catalog)
            gateway = start("api-gateway", [*common, "--spring.application.name=api-gateway",
                            f"--server.port={gateway_port}",
                            f"--spring.cloud.discovery.client.simple.instances.catalog-service[0].uri={catalog_url}",
                            f"--spring.data.redis.port={port()}"], app_env)
            gateway_url = f"http://127.0.0.1:{gateway_port}"
            wait(gateway_url + "/api/v1/categories/tree", gateway)
            payload = {"name": "Keycloak trust " + secrets.token_hex(6), "categoryId": CATEGORY,
                       "description": "Issue 237 live acceptance", "auctionEnable": False}
            status, _, body = kc.http(gateway_url + "/api/v1/products", payload, token)
            check(status == 201, f"Real Gateway -> Catalog create expected 201, got {status}: {body.decode()}")
            product = json.loads(body)["data"]
            check(product["sellerId"] == SELLER, "Product owner did not come from validated token")
            stored = compose("exec", "-T", "postgres", "psql", "-U", "nexus_test", "-d", "catalog_db", "-Atc",
                             f"SELECT seller_id FROM products WHERE id = '{product['id']}';")
            check(stored == SELLER, "Product missing from real Catalog PostgreSQL")
            record("PASS: seller token -> real Gateway -> real Catalog POST products = 201; persisted seller matches JWT",
                   gateway_status=status, product_id=product["id"], seller_id=stored, database_persisted=True,
                   response=json.loads(body))

            buyer_code, buyer_verifier = kc.authorization(base, "buyer@nexus.local", demo_password)
            check(buyer_code, "Buyer PKCE login failed")
            buyer_status, buyer_tokens = kc.exchange(base, buyer_code, buyer_verifier)
            check(buyer_status == 200, "Buyer token exchange failed")
            buyer_claims = kc.verify_token(base, buyer_tokens["access_token"])
            check("PRODUCT.CREATE" not in buyer_claims["privileges"], "Buyer unexpectedly has product-create privilege")
            for target in (gateway_url, catalog_url):
                status, _, _ = kc.http(target + "/api/v1/products", payload, buyer_tokens["access_token"])
                check(status == 403, f"Valid Buyer without privilege expected 403, got {status}")
            record("PASS: genuine Keycloak Buyer without PRODUCT.CREATE -> 403 at Gateway AND direct Catalog")

            # Remove only the mapper in the DISPOSABLE realm to obtain a genuinely
            # Keycloak-signed token without aud; editing JWT payload would only test signatures.
            status, _, body = kc.form(base + "/realms/master/protocol/openid-connect/token", {
                "grant_type": "password", "client_id": "admin-cli", "username": "test-admin", "password": admin_password})
            check(status == 200, "Isolated admin authentication failed")
            admin = json.loads(body)["access_token"]
            api = base + "/admin/realms/nexus/clients"
            status, _, body = kc.http(api + "?clientId=nexus-web", token=admin)
            check(status == 200, "Client lookup failed")
            client_id = json.loads(body)[0]["id"]
            status, _, body = kc.http(api + f"/{client_id}/protocol-mappers/models", token=admin)
            check(status == 200, "Mapper lookup failed")
            mapper = next(m for m in json.loads(body) if m["name"] == "nexus-services-audience")
            status, _, _ = kc.http(api + f"/{client_id}/protocol-mappers/models/{mapper['id']}", token=admin, method="DELETE")
            check(status == 204, "Could not remove isolated audience mapper")
            code, verifier = kc.authorization(base, "seller@nexus.local", demo_password)
            status, tokens = kc.exchange(base, code, verifier)
            check(status == 200, "Missing-aud token code exchange failed")
            no_aud = tokens["access_token"]
            no_aud_claims = kc.verify_token(base, no_aud)
            check("aud" not in no_aud_claims, "Expected a genuinely missing aud claim")

            # Start the real legacy JwtIssuer with an ephemeral owned RSA key.
            # No operator/private production key or local .env is read.
            private = directory / "legacy-private.pem"
            public = directory / "legacy-public.pem"
            subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
                            "-out", str(private)], check=True, capture_output=True)
            subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)],
                           check=True, capture_output=True)
            legacy_env = app_env.copy()
            legacy_env.update({"JWT_KEY_ID": "legacy-user-service", "JWT_PRIVATE_KEY_PATH": private.as_posix(),
                               "JWT_PUBLIC_KEY_PATH": public.as_posix()})
            user_port = port()
            user = start("user-service", [*common, "--spring.application.name=user-service",
                         f"--server.port={user_port}"], legacy_env)
            user_url = f"http://127.0.0.1:{user_port}"
            wait(user_url + "/actuator/health", user)
            legacy_email = "legacy-237-" + secrets.token_hex(6) + "@local.test"
            legacy_password = secrets.token_urlsafe(24)
            status, _, _ = kc.http(user_url + "/api/v1/auth/register", {
                "email": legacy_email, "password": legacy_password, "phone": "09" + str(secrets.randbelow(90000000) + 10000000),
                "fullName": "Isolated legacy token fixture", "identityNumber": "237-" + secrets.token_hex(8),
                "gender": "OTHER", "address": "Local test fixture", "dateOfBirth": "2000-01-01"})
            check(status == 201, f"Legacy registration expected 201, got {status}")
            status, _, body = kc.http(user_url + "/api/v1/auth/login", {"email": legacy_email, "password": legacy_password})
            check(status == 200, f"Legacy login expected 200, got {status}")
            old_token = json.loads(body)["data"]["accessToken"]
            old_claims = json.loads(kc.unb64(old_token.split(".")[1]))
            check(old_claims["iss"] == "user-service", "Legacy fixture must use the actual retired issuer")
            old_header, old_payload, old_signature = old_token.split(".")
            signature_file = directory / "legacy-signature.bin"
            signature_file.write_bytes(kc.unb64(old_signature))
            subprocess.run(["openssl", "dgst", "-sha256", "-verify", str(public), "-signature", str(signature_file)],
                           input=f"{old_header}.{old_payload}".encode(), capture_output=True, check=True)
            record("PASS: rejected legacy fixture was issued by real user-service login and has a valid signature under its own old key",
                   legacy_issuer=old_claims["iss"], legacy_signature_verified=True)

            def legacy(issuer):
                old_claims = {**claims, "iss": issuer}
                message = kc.b64(json.dumps({"alg": "RS256", "typ": "JWT", "kid": "legacy-user-service"}).encode())
                message += "." + kc.b64(json.dumps(old_claims).encode())
                result = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private)],
                                        input=message.encode(), capture_output=True, check=True)
                return message + "." + kc.b64(result.stdout)

            for label, invalid in (("real legacy user-service login token", old_token),
                                   ("legacy key with forged Keycloak issuer", legacy(claims["iss"])),
                                   ("real Keycloak token without aud", no_aud)):
                observed = {}
                for name, url in (("Gateway", gateway_url), ("direct Catalog", catalog_url)):
                    status, _, error_body = kc.http(url + "/api/v1/products", payload, invalid)
                    check(status == 401, f"{name}: {label} expected 401, got {status}")
                    observed[name] = {"http_status": status, "problem": json.loads(error_body)}
                record(f"PASS: {label} -> 401 at Gateway AND direct Catalog", responses=observed,
                       genuinely_keycloak_signed=(label == "real Keycloak token without aud"),
                       aud_absent=("aud" not in no_aud_claims) if label == "real Keycloak token without aud" else None)
            # An already-issued valid token still works: rejection was not an outage.
            status, _, _ = kc.http(gateway_url + "/api/v1/products/mine", token=token)
            check(status == 200, f"Valid seller token lost access: {status}")
            record("PASS: original valid seller token still reads own products = 200", http_status=status)
            report["status"] = "passed"
        except Exception:
            report["status"] = "failed"
            for path in directory.glob("*-service.log"):
                print(f"--- {path.name} (tail) ---\n" + "\n".join(path.read_text().splitlines()[-45:]), flush=True)
            path = directory / "api-gateway.log"
            if path.exists():
                print("--- api-gateway.log (tail) ---\n" + "\n".join(path.read_text().splitlines()[-45:]), flush=True)
            raise
        finally:
            for process, log in reversed(processes):
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                log.close()
            compose("down", "-v", "--remove-orphans")
            report["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
            record("Removed isolated containers and terminated owned application processes", cleanup=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, help="Empty directory for sanitized results; never saves raw JWTs or credentials")
    args = parser.parse_args()
    run(args.evidence_dir)
