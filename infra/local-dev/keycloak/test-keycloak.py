#!/usr/bin/env python3
"""Real Keycloak/Postgres acceptance checks in disposable Docker resources.

Requires Python 3 and Docker Compose; no extra Python packages.
--full-lockout waits the real 15 minutes before checking account recovery.
--config-only validates Compose configuration without starting containers.
"""
import argparse
import base64
import hashlib
import hmac
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import (build_opener, HTTPCookieProcessor, HTTPRedirectHandler,
                            Request, urlopen)


HERE = Path(__file__).resolve().parent
sys.dont_write_bytecode = True
REDIRECT = "http://localhost:3000/keycloak-test-callback"
SELLER_ID = "68000000-0000-4000-8000-000000000002"


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def unb64(data):
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def http(url, data=None, token=None, method=None, opener=None):
    headers = {}
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with (opener.open(request, timeout=20) if opener else urlopen(request, timeout=20)) as response:
            return response.status, response.headers, response.read()
    except HTTPError as error:
        return error.code, error.headers, error.read()


def form(url, values, opener=None):
    return http(url, urlencode(values).encode(), opener=opener)


class CallbackRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        if newurl.startswith(REDIRECT):
            return None
        return super().redirect_request(request, fp, code, message, headers, newurl)


class LoginForm(HTMLParser):
    action = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.action = attrs.get("action")


class PageText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.text = []
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style"):
            self.hidden -= 1

    def handle_data(self, data):
        if not self.hidden and data.strip():
            self.text.append(data.strip())


def authorization(base, username, password):
    verifier = secrets.token_urlsafe(48)
    state = secrets.token_urlsafe(16)
    check(urlparse(base).hostname == "127.0.0.1", "Tests may only send credentials to the isolated loopback server")
    # Browsers treat loopback as a secure context; stdlib otherwise refuses
    # Keycloak's Secure session cookies over local HTTP and loses the login session.
    cookies = CookieJar(DefaultCookiePolicy(secure_protocols=("https", "http")))
    opener = build_opener(HTTPCookieProcessor(cookies), CallbackRedirect())
    query = urlencode({"client_id": "nexus-web", "response_type": "code",
                       "redirect_uri": REDIRECT, "scope": "openid",
                       "state": state, "code_challenge": b64(hashlib.sha256(verifier.encode()).digest()),
                       "code_challenge_method": "S256"})
    status, _, body = http(f"{base}/realms/nexus/protocol/openid-connect/auth?{query}", opener=opener)
    parser = LoginForm()
    parser.feed(body.decode())
    check(status == 200 and parser.action, "Expected a Keycloak browser login form")
    status, headers, body = form(parser.action, {"username": username, "password": password, "credentialId": ""}, opener)
    if status == 302 and headers.get("Location", "").startswith(REDIRECT):
        params = parse_qs(urlparse(headers["Location"]).query)
        check(params.get("state") == [state], "OAuth state mismatch")
        return params.get("code", [None])[0], verifier
    if status != 200:
        error = PageText()
        error.feed(body.decode())
        raise AssertionError(f"Unexpected browser login response: HTTP {status}: {' '.join(error.text)[-700:]}")
    return None, verifier


def exchange(base, code, verifier):
    status, _, body = form(f"{base}/realms/nexus/protocol/openid-connect/token", {
        "grant_type": "authorization_code", "client_id": "nexus-web",
        "redirect_uri": REDIRECT, "code": code, "code_verifier": verifier})
    return status, json.loads(body)


def verify_token(base, token):
    header, payload, signature = token.split(".")
    header_data = json.loads(unb64(header))
    check(header_data["alg"] == "RS256", "Expected RS256")
    status, _, body = http(f"{base}/realms/nexus/protocol/openid-connect/certs")
    check(status == 200, "JWKS unavailable")
    key = next(k for k in json.loads(body)["keys"] if k["kid"] == header_data["kid"])

    modulus = int.from_bytes(unb64(key["n"]), "big")
    exponent = int.from_bytes(unb64(key["e"]), "big")
    size = (modulus.bit_length() + 7) // 8
    signature_bytes = unb64(signature)
    check(len(signature_bytes) == size, "JWT signature length mismatch")
    decoded = pow(int.from_bytes(signature_bytes, "big"), exponent, modulus).to_bytes(size, "big")
    # RS256 is RSASSA-PKCS1-v1_5 with the SHA-256 DigestInfo prefix.
    digest_info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(f"{header}.{payload}".encode()).digest()
    padding = size - len(digest_info) - 3
    check(padding >= 8 and hmac.compare_digest(decoded, b"\x00\x01" + b"\xff" * padding + b"\x00" + digest_info),
          "JWT signature verification failed")
    claims = json.loads(unb64(payload))
    check(claims["iss"] == f"{base}/realms/nexus", "Unexpected issuer")
    # Docker Desktop's VM clock can differ slightly from the Windows host clock.
    now = time.time()
    check(claims["iat"] <= claims["exp"] and claims["iat"] - 60 <= now < claims["exp"] + 60,
          "Token not currently valid")
    return claims


def run(full_lockout, config_only=False):
    project = "nexus-236-test-" + secrets.token_hex(4)
    with tempfile.TemporaryDirectory(prefix=project) as tmp:
        directory = Path(tmp)
        values = {"TZ": "UTC", "DB_PORT": "0", "DB_USERNAME": "nexus_test", "DB_PASSWORD": secrets.token_urlsafe(24),
                  "KEYCLOAK_PORT": "0", "KEYCLOAK_ADMIN": "test-admin", "KEYCLOAK_ADMIN_PASSWORD": secrets.token_urlsafe(24),
                  "KAFKA_PORT": "19092", "KAFKA_UI_PORT": "18090", "REDIS_PORT": "6379", "REDIS_PASSWORD": secrets.token_urlsafe(24),
                  "MAILPIT_SMTP_PORT": "1025", "MAILPIT_UI_PORT": "8025", "GRAFANA_ADMIN_PASSWORD": secrets.token_urlsafe(24)}
        # Exercise dotenv quoting and punctuation without putting credentials in Git.
        for role in ("ADMIN", "SELLER", "BUYER"):
            values[f"DEMO_{role}_PASSWORD"] = '-p' + secrets.token_urlsafe(20) + '#$"\\ space'
        values["KEYCLOAK_USER_SERVICE_CLIENT_SECRET"] = secrets.token_urlsafe(32)
        env_file = directory / ".env"
        env_file.write_text("\n".join(f"{k}='{v}'" for k, v in values.items()) + "\n")
        if os.name != "nt":
            env_file.chmod(0o600)
        environment = os.environ.copy()
        for key in (*values, "SEPAY_BANK_ACCOUNT", "SEPAY_ACCOUNT_NAME"):
            environment.pop(key, None)
        environment.pop("COMPOSE_PROFILES", None)
        original = None
        # Profiles do not defer interpolation: unrelated payment settings must
        # neither block auth startup nor require invented bank details.
        for payment_settings in ("missing", "blank"):
            config_env = env_file
            if payment_settings == "blank":
                config_env = directory / "blank-payment.env"
                config_env.write_text(env_file.read_text() + "SEPAY_BANK_ACCOUNT=\nSEPAY_ACCOUNT_NAME=\n")
                if os.name != "nt":
                    config_env.chmod(0o600)
            rendered = subprocess.run(["docker", "compose", "--env-file", str(config_env), "-f", str(HERE.parent / "compose.yaml"), "--profile", "tools", "config", "--format", "json"],
                                      env=environment, text=True, capture_output=True, check=True)
            current = json.loads(rendered.stdout)
            check(all(name in current["services"] for name in ("postgres", "auth-service", "auth-seed")),
                  "Auth services are missing from Compose configuration")
            if original is None:
                original = current
            else:
                check(all(current["services"][name] == original["services"][name]
                          for name in ("postgres", "auth-service", "auth-seed")),
                      "Payment settings changed the auth services")
            print(f"PASS: auth Compose configuration with {payment_settings} payment settings", flush=True)
        if config_only:
            return
        config = {"services": {name: original["services"][name] for name in ("postgres", "auth-service", "auth-seed")},
                  "networks": {"nexus-network": {}}, "volumes": {"postgres-data": {}}}
        for name, service in config["services"].items():
            service["container_name"] = f"{project}-{name}"
            service.pop("profiles", None)
            for port in service.get("ports", []):
                port.update({"host_ip": "127.0.0.1", "published": "0"})
        config["services"]["auth-service"]["environment"]["JAVA_OPTS_KC_HEAP"] = "-Xms64m -Xmx512m"
        compose_file = directory / "compose.json"
        compose_file.write_text(json.dumps(config))
        if os.name != "nt":
            compose_file.chmod(0o600)

        def compose(*args):
            result = subprocess.run(["docker", "compose", "-p", project, "-f", str(compose_file), *args],
                                    env=environment, text=True, capture_output=True)
            check(result.returncode == 0, f"Docker Compose {' '.join(args[:2])} failed: {result.stderr}")
            return result.stdout

        try:
            print(f"Starting isolated project {project}", flush=True)
            compose("up", "-d", "--wait", "--wait-timeout", "240", "postgres", "auth-service")
            base = "http://" + compose("port", "auth-service", "8080").strip()
            print("PASS: PostgreSQL and imported Keycloak realm are healthy", flush=True)
            compose("run", "--rm", "--no-deps", "-T", "auth-seed")
            compose("run", "--rm", "--no-deps", "-T", "auth-seed")
            print("PASS: seed-users.sh sets passwords starting with -p and containing punctuation; repeatable", flush=True)
            status, _, body = form(f"{base}/realms/nexus/protocol/openid-connect/token", {
                "grant_type": "client_credentials", "client_id": "user-service",
                "client_secret": values["KEYCLOAK_USER_SERVICE_CLIENT_SECRET"]})
            check(status == 200, "user-service client_credentials grant failed")
            status, _, body = http(f"{base}/admin/realms/nexus/roles/BUYER", token=json.loads(body)["access_token"])
            check(status == 200 and json.loads(body)["name"] == "BUYER", "user-service service account cannot read realm roles")
            print("PASS: seed-users.sh provisions the confidential user-service client and its service account", flush=True)
            spec = importlib.util.spec_from_file_location("realm_contract", HERE / "test-realm.py")
            contracts = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(contracts)
            matrix = contracts.source_matrix()[1]
            for i, role in enumerate(("ADMIN", "SELLER", "BUYER"), 1):
                code, verifier = authorization(base, role.lower() + "@nexus.local", values[f"DEMO_{role}_PASSWORD"])
                check(code, f"{role} login failed")
                status, tokens = exchange(base, code, verifier)
                check(status == 200, f"{role} code exchange failed")
                claims = verify_token(base, tokens["access_token"])
                check(claims["sub"] == f"68000000-0000-4000-8000-{i:012d}", f"{role} wrong subject")
                check(claims["roles"] == [role], f"{role} leaked/default realm roles")
                check(set(claims["privileges"]) == set(matrix[role]), f"{role} wrong privileges")
                check(len(claims["privileges"]) == len(matrix[role]), f"{role} duplicate privileges")
                audiences = claims["aud"] if isinstance(claims["aud"], list) else [claims["aud"]]
                check("nexus-services" in audiences, f"{role} missing audience")
                print(f"PASS: {role} PKCE token, fixed UUID, exact {len(matrix[role])} privileges and RS256 signature", flush=True)

            auth_url = f"{base}/realms/nexus/protocol/openid-connect/auth?"
            query = {"client_id": "nexus-web", "response_type": "code", "scope": "openid", "redirect_uri": REDIRECT}
            status, headers, _ = http(auth_url + urlencode(query), opener=build_opener(CallbackRedirect()))
            location = headers.get("Location", "")
            oauth_error = parse_qs(urlparse(location).query).get("error")
            check(status == 400 or (status == 302 and location.startswith(REDIRECT) and oauth_error == ["invalid_request"]),
                  "Missing PKCE must be rejected, either directly or via an OAuth error callback")
            query.update({"code_challenge": "x" * 43, "code_challenge_method": "S256", "redirect_uri": "http://localhost:3001/not-allowed"})
            check(http(auth_url + urlencode(query))[0] == 400, "Invalid redirect must be rejected")
            code, _ = authorization(base, "seller@nexus.local", values["DEMO_SELLER_PASSWORD"])
            check(exchange(base, code, secrets.token_urlsafe(48))[0] == 400, "Wrong PKCE verifier must be rejected")
            status, _, _ = form(f"{base}/realms/nexus/protocol/openid-connect/token", {
                "grant_type": "password", "client_id": "nexus-web", "username": "seller@nexus.local", "password": values["DEMO_SELLER_PASSWORD"]})
            check(status == 400, "nexus-web password grant must be disabled")
            print("PASS: missing/wrong PKCE, invalid redirect and password grant rejected", flush=True)

            status, _, body = form(f"{base}/realms/master/protocol/openid-connect/token", {
                "grant_type": "password", "client_id": "admin-cli", "username": values["KEYCLOAK_ADMIN"], "password": values["KEYCLOAK_ADMIN_PASSWORD"]})
            check(status == 200, "Admin REST login failed")
            admin_token = json.loads(body)["access_token"]
            admin_url = f"{base}/admin/realms/nexus"

            def admin(path, data=None, method=None):
                status, _, body = http(admin_url + path, data, admin_token, method)
                check(status < 300, f"Admin REST {method or 'GET'} {path}: HTTP {status}")
                return json.loads(body) if body else None

            # Use a temporary account so policy tests never alter demo credentials.
            policy_id = "68000000-0000-4000-8000-000000000099"
            admin("/users", {"id": policy_id, "username": "policy-test", "enabled": True}, "POST")
            # REST creation can generate an ID; discover the actual one.
            policy_id = admin("/users?username=policy-test&exact=true")[0]["id"]
            for password in ("short", " " * 8):
                status, _, _ = http(admin_url + f"/users/{policy_id}/reset-password", {"type": "password", "value": password, "temporary": False}, admin_token, "PUT")
                check(status == 400, "Invalid password passed realm policy")
            admin(f"/users/{policy_id}/reset-password", {"type": "password", "value": "abcdefgh", "temporary": False}, "PUT")
            admin(f"/users/{policy_id}", method="DELETE")
            print("PASS: STANDARD baseline rejects short/blank and accepts 8-character password", flush=True)

            for attempt in range(1, 6):
                code, _ = authorization(base, "seller@nexus.local", secrets.token_urlsafe(20))
                check(code is None, "Wrong password unexpectedly authenticated")
                detection = admin(f"/attack-detection/brute-force/users/{SELLER_ID}")
                check(detection["numFailures"] == attempt, "Unexpected failed-login count")
                check(bool(detection["disabled"]) == (attempt == 5), "Incorrect lockout threshold")
            blocked_at = time.monotonic()
            code, _ = authorization(base, "seller@nexus.local", values["DEMO_SELLER_PASSWORD"])
            check(code is None, "Sixth attempt with correct password bypassed lockout")
            check(admin(f"/attack-detection/brute-force/users/{SELLER_ID}")["numFailures"] == 5, "Blocked request changed failure count")
            policy = admin("")
            check(policy["waitIncrementSeconds"] == 900 and policy["maxFailureWaitSeconds"] == 900, "Lockout is not fixed at 15 minutes")
            print("PASS: 5 failures trigger lockout; sixth correct password is refused; configured wait is 900s", flush=True)
            if full_lockout:
                print("Waiting the real 15-minute lockout (no policy changes or manual unlock)...", flush=True)
                # Prove the account remains locked near the end, then recovers.
                time.sleep(max(0, 890 - (time.monotonic() - blocked_at)))
                code, _ = authorization(base, "seller@nexus.local", values["DEMO_SELLER_PASSWORD"])
                check(code is None, "Account unlocked before 15 minutes")
                time.sleep(max(0, 903 - (time.monotonic() - blocked_at)))
                code, verifier = authorization(base, "seller@nexus.local", values["DEMO_SELLER_PASSWORD"])
                check(code and exchange(base, code, verifier)[0] == 200, "Account did not recover after 15 minutes")
                print("PASS: still blocked at 890s; successful login after 903s", flush=True)
            else:
                print("SKIP: wall-clock recovery; use --full-lockout for the complete 15-minute acceptance check", flush=True)
        finally:
            compose("down", "--volumes", "--remove-orphans")
            print(f"Cleaned up only {project} resources", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full-lockout", action="store_true")
    parser.add_argument("--config-only", action="store_true")
    args = parser.parse_args()
    run(args.full_lockout, args.config_only)
