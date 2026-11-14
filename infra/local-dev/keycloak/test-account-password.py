#!/usr/bin/env python3
"""Real Keycloak/Mailpit password acceptance in disposable native processes.

Requires Java 21, Python 3, an unpacked official Keycloak 26.7.5 distribution
and the Mailpit executable. No existing realm, environment file or database is
used. The source Keycloak distribution is copied before startup; all test data
and processes are removed on completion. Downloads are not performed here.
"""
import argparse
import hashlib
from html import unescape
from html.parser import HTMLParser
from http.cookiejar import CookieJar, DefaultCookiePolicy
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urljoin, urlparse
from urllib.request import build_opener, HTTPCookieProcessor, Request, urlopen


sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("keycloak_acceptance", HERE / "test-keycloak.py")
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
check, http, form = helpers.check, helpers.http, helpers.form


class Page(HTMLParser):
    def __init__(self, body):
        super().__init__()
        self.forms, self.links, self.styles = {}, [], []
        self.feed(body.decode())

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id"):
            self.forms[attrs["id"]] = attrs.get("action")
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
        if tag == "link" and attrs.get("rel") == "stylesheet":
            self.styles.append(attrs.get("href", ""))


def browser():
    cookies = CookieJar(DefaultCookiePolicy(secure_protocols=("https", "http")))
    return build_opener(HTTPCookieProcessor(cookies), helpers.CallbackRedirect())


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def wait_until(probe, reason, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = probe()
            if result:
                return result
        except (URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(0.5)
    raise AssertionError(reason)


def run(keycloak_home, mailpit_executable):
    check((keycloak_home / "bin/kc.bat").exists() or (keycloak_home / "bin/kc.sh").exists(), "Keycloak distribution is missing")
    check(mailpit_executable.is_file(), "Mailpit executable is missing")
    check(subprocess.run(["java", "-version"], capture_output=True).returncode == 0, "Java is unavailable")
    processes, streams = [], []
    with tempfile.TemporaryDirectory(prefix="nexus-219-live-") as tmp:
        scratch = Path(tmp)
        home = scratch / "keycloak"
        shutil.copytree(keycloak_home, home, ignore=shutil.ignore_patterns("data", "log", "logs"))
        # Only this disposable realm uses an ephemeral Mailpit port and fresh users.
        realm = json.loads((HERE / "realm-nexus.json").read_text())
        realm["users"] = []
        kc_port, smtp_port, mail_port = free_port(), free_port(), free_port()
        realm["smtpServer"].update({"host": "127.0.0.1", "port": str(smtp_port)})
        (home / "data/import").mkdir(parents=True)
        (home / "data/import/nexus.json").write_text(json.dumps(realm))
        shutil.copytree(HERE / "themes/nexus", home / "themes/nexus")
        base, mailbox = f"http://127.0.0.1:{kc_port}", f"http://127.0.0.1:{mail_port}"
        admin_name, admin_password = "test-" + secrets.token_hex(8), secrets.token_urlsafe(32)
        environment = os.environ.copy()
        environment.update({"KC_BOOTSTRAP_ADMIN_USERNAME": admin_name, "KC_BOOTSTRAP_ADMIN_PASSWORD": admin_password,
                            "JAVA_OPTS_KC_HEAP": "-Xms64m -Xmx512m"})
        for name in tuple(environment):
            if name.startswith("KC_") and name not in ("KC_BOOTSTRAP_ADMIN_USERNAME", "KC_BOOTSTRAP_ADMIN_PASSWORD"):
                del environment[name]
        creation = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}

        def launch(arguments, name, env=None):
            output = (scratch / f"{name}.log").open("w")
            streams.append(output)
            process = subprocess.Popen(arguments, cwd=scratch, env=env, stdout=output, stderr=subprocess.STDOUT, **creation)
            processes.append(process)

        try:
            launch([str(mailpit_executable), "--listen", f"127.0.0.1:{mail_port}", "--smtp", f"127.0.0.1:{smtp_port}",
                    "--database", str(scratch / "mailpit.db"), "--disable-version-check"], "mailpit")
            command = [str(home / ("bin/kc.bat" if os.name == "nt" else "bin/kc.sh")), "start-dev", "--import-realm",
                       "--http-host=127.0.0.1", f"--http-port={kc_port}", "--hostname-strict=false"]
            if os.name == "nt":
                command = [os.environ.get("COMSPEC", "cmd.exe"), "/d", "/c", *command]
            launch(command, "keycloak", environment)
            wait_until(lambda: http(f"{mailbox}/api/v1/messages")[0] == 200, "Mailpit did not start")
            wait_until(lambda: http(f"{base}/realms/nexus/.well-known/openid-configuration")[0] == 200,
                       "Keycloak did not import its disposable realm", timeout=180)
            print("PASS: isolated native Keycloak 26.7.5 and Mailpit are serving loopback only", flush=True)
            status, _, body = form(f"{base}/realms/master/protocol/openid-connect/token", {
                "grant_type": "password", "client_id": "admin-cli", "username": admin_name, "password": admin_password})
            check(status == 200, "Disposable administrator sign-in failed")
            token = json.loads(body)["access_token"]
            admin_base = f"{base}/admin/realms/nexus"

            def admin(path, data=None, method=None):
                request = Request(admin_base + path, data=json.dumps(data).encode() if data is not None else None,
                                  headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"}, method=method)
                try:
                    with urlopen(request, timeout=20) as response:
                        result = response.read()
                except HTTPError as error:
                    raise AssertionError(f"Disposable admin operation failed: HTTP {error.code}") from None
                return json.loads(result) if result else None

            username, password = "password-" + secrets.token_hex(8), secrets.token_urlsafe(24)
            email = username + "@nexus.local"
            admin("/users", {"username": username, "email": email, "emailVerified": True,
                             "firstName": "Test", "lastName": "Buyer", "enabled": True}, "POST")
            user_id = admin("/users?" + urlencode({"username": username, "exact": "true"}))[0]["id"]
            admin(f"/users/{user_id}/reset-password", {"type": "password", "value": password, "temporary": False}, "PUT")

            def login_page(opener):
                verifier = secrets.token_urlsafe(48)
                query = urlencode({"client_id": "nexus-web", "response_type": "code", "scope": "openid",
                                   "redirect_uri": helpers.REDIRECT, "state": secrets.token_urlsafe(16),
                                   "code_challenge": helpers.b64(hashlib.sha256(verifier.encode()).digest()), "code_challenge_method": "S256"})
                return http(f"{base}/realms/nexus/protocol/openid-connect/auth?{query}", opener=opener)

            def themed(body):
                styles = [url for url in Page(body).styles if "/nexus/" in url and url.endswith("/css/nexus.css")]
                check(len(styles) == 1 and http(urljoin(base, styles[0]))[0] == 200, "Nexus theme stylesheet is not served")
                prefix = urljoin(base, styles[0]).removesuffix("css/nexus.css")
                for asset in ("img/nexus-logo.svg", "fonts/Geist.ttf"):
                    check(http(prefix + asset)[0] == 200, "A self-hosted Nexus theme asset is missing")

            def read_mail(action):
                def latest():
                    status, _, payload = http(f"{mailbox}/api/v1/messages")
                    check(status == 200, "Mailpit listing failed")
                    for message in json.loads(payload).get("messages", []):
                        _, _, detail = http(f"{mailbox}/api/v1/message/{message['ID']}")
                        contents = json.loads(detail)
                        if any(to.get("Address") == email for to in contents.get("To", [])):
                            links = Page(contents.get("HTML", "").encode()).links
                            for link in links:
                                if "/login-actions/action-token?" in link:
                                    params = parse_qs(urlparse(unescape(link)).query)
                                    claims = json.loads(helpers.unb64(params["key"][0].split(".")[1]))
                                    if claims.get("typ") == action:
                                        return unescape(link)
                    return None
                link = wait_until(latest, "Expected email did not arrive in real Mailpit", timeout=20)
                check(urlparse(link).netloc == urlparse(base).netloc, "Email action links must target the disposable Keycloak")
                return link

            # Start the actual Forgot password browser flow, not an Admin reset.
            opener = browser()
            status, _, body = login_page(opener)
            check(status == 200 and "kc-form-login" in Page(body).forms, "Browser sign-in form missing")
            themed(body)
            reset = next((link for link in Page(body).links if "/login-actions/reset-credentials?" in link), None)
            check(reset, "Forgot password link missing")
            status, _, body = http(urljoin(base, reset), opener=opener)
            check(status == 200 and "kc-reset-password-form" in Page(body).forms, "Forgot password form missing")
            themed(body)
            status, _, body = form(Page(body).forms["kc-reset-password-form"], {"username": email}, opener)
            check(status == 200 and b"email" in body.lower(), "Forgot password submission failed")
            reset_link = read_mail("reset-credentials")
            print("PASS: Forgot password is themed and delivers a reset link to real Mailpit", flush=True)

            reset_browser = browser()
            status, _, body = http(reset_link, opener=reset_browser)
            check(status == 200 and "kc-passwd-update-form" in Page(body).forms, "Reset email did not open a new-password form")
            themed(body)
            status, _, body = form(Page(body).forms["kc-passwd-update-form"], {"password-new": "short", "password-confirm": "short"}, reset_browser)
            check(status == 200 and "kc-passwd-update-form" in Page(body).forms and b"minimum length 8" in body,
                  "Password policy rejection was not displayed on the reset form")
            themed(body)
            new_password = secrets.token_urlsafe(24)
            status, _, body = form(Page(body).forms["kc-passwd-update-form"], {"password-new": new_password, "password-confirm": new_password}, reset_browser)
            check(status in (200, 302), "Valid password reset failed")
            code, verifier = helpers.authorization(base, username, new_password)
            check(code and helpers.exchange(base, code, verifier)[0] == 200, "New password did not authenticate through real PKCE")
            code, _ = helpers.authorization(base, username, password)
            check(code is None, "Old password still authenticates after reset")
            status, _, body = http(reset_link, opener=browser())
            check("kc-passwd-update-form" not in Page(body).forms, "A used password reset link was reusable")
            print("PASS: policy errors remain on the Nexus page; reset changes credentials and the email link is single-use", flush=True)

            account_browser = browser()
            status, _, body = login_page(account_browser)
            status, _, _ = form(Page(body).forms["kc-form-login"], {"username": username, "password": new_password, "credentialId": ""}, account_browser)
            check(status == 302, "Account console setup did not authenticate")
            profile = "http://localhost:3000/profile"
            status, _, body = http(f"{base}/realms/nexus/account/?" + urlencode({"referrer": "nexus-web", "referrer_uri": profile}), opener=account_browser)
            check(status == 200 and profile.encode() in body and "kc-form-login" not in Page(body).forms,
                  "Real account console did not preserve the validated profile referrer")
            print("PASS: real account console accepts nexus-web's safe /profile return referrer", flush=True)

            # Exercise real token expiry without waiting the production lifetime.
            # Only this action token has a two-second lifespan; realm policy stays unchanged.
            admin(f"/users/{user_id}/execute-actions-email?" + urlencode({"client_id": "nexus-web", "redirect_uri": helpers.REDIRECT, "lifespan": 2}), ["UPDATE_PASSWORD"], "PUT")
            expiring_link = read_mail("execute-actions")
            claims = json.loads(helpers.unb64(parse_qs(urlparse(expiring_link).query)["key"][0].split(".")[1]))
            while time.time() <= claims["exp"] + 1:
                time.sleep(0.5)
            status, _, body = http(expiring_link, opener=browser())
            check("kc-passwd-update-form" not in Page(body).forms and b"Forgot password?" in body and b"expired" in body.lower(),
                  "Expired reset action did not explain how to request a new password email")
            themed(body)
            print("PASS: an actually expired action token shows Nexus recovery instructions", flush=True)

            # Native email verification support is distinct from registration #210.
            admin(f"/users/{user_id}", {"emailVerified": False}, "PUT")
            admin(f"/users/{user_id}/send-verify-email?" + urlencode({"client_id": "nexus-web", "redirect_uri": helpers.REDIRECT}), method="PUT")
            verify_link = read_mail("verify-email")
            check(admin(f"/users/{user_id}")["emailVerified"] is False, "Email was verified before its link was clicked")
            verify_browser = browser()
            status, _, body = http(verify_link, opener=verify_browser)
            # Keycloak deliberately asks for confirmation when an email opens in
            # a fresh browser; follow its signed confirmation using that session.
            if admin(f"/users/{user_id}")["emailVerified"] is False:
                confirmation = next((link for link in Page(body).links if "/login-actions/action-token?" in link), None)
                check(confirmation, "Fresh-browser email verification confirmation is missing")
                status, _, body = http(urljoin(base, confirmation), opener=verify_browser)
            check(status in (200, 302) and admin(f"/users/{user_id}")["emailVerified"] is True, "Real email verification link did not verify the user")
            # Verify state changed exactly via the signed action; no FE or mock is involved.
            print("PASS: real Mailpit verification link updates the disposable Keycloak user's emailVerified flag", flush=True)
            print("LIMIT: this upstream probe does not exercise #210 registration/resend or the frontend/gateway account-console return", flush=True)
        finally:
            for process in reversed(processes):
                if process.poll() is None:
                    if os.name == "nt":
                        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True)
                    else:
                        process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
            for stream in streams:
                stream.close()
            print("Stopped only test-owned processes; disposable realm/mailbox removed", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keycloak-home", required=True, type=Path)
    parser.add_argument("--mailpit", required=True, type=Path)
    arguments = parser.parse_args()
    run(arguments.keycloak_home.resolve(), arguments.mailpit.resolve())
