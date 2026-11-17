#!/usr/bin/env python3
"""Offline contract checks: py -3 keycloak/test-realm.py (no dependencies)."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
BACKEND = HERE.parents[2]
SQL = BACKEND / "user-service/src/main/resources/db/migration/V3__roles_and_accounts.sql"

# User-service V7 dropped the historical auth tables. New privileges are seeded in Keycloak.
PROMOTIONS = {
    "ADMIN": ["PROMOTION.EVENT.MANAGE", "PROMOTION.EVENT.REGISTER", "VOUCHER.MANAGE", "VOUCHER.CLAIM", "VOUCHER.VIEW"],
    "SELLER": ["PROMOTION.EVENT.REGISTER", "VOUCHER.MANAGE", "VOUCHER.VIEW"],
    "BUYER": ["VOUCHER.CLAIM", "VOUCHER.VIEW"],
    "SUPPORT_STAFF": ["VOUCHER.VIEW"],
}


def source_matrix():
    sql = SQL.read_text()
    privileges = re.findall(r"\('([^']+)',", sql.split("ON CONFLICT")[0])
    matrix = {}
    for block in sql.split("INSERT INTO role_permissions")[1:]:
        role = re.search(r"WHERE r.code = '([^']+)'", block)[1]
        matrix[role] = re.findall(r"'([A-Z_]+(?:\.[A-Z_]+)+)'", block.split("ON CONFLICT")[0])
    for role, grants in PROMOTIONS.items():
        matrix[role].extend(grants)
    return privileges + PROMOTIONS["ADMIN"], matrix


class RealmContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.realm = json.loads((HERE / "realm-nexus.json").read_text())
        cls.client = cls.realm["clients"][0]

    def test_exact_privileges_and_composite_matrix_from_role_seeds(self):
        privileges, matrix = source_matrix()
        actual = [r["name"] for r in self.realm["roles"]["client"]["nexus-web"]]
        self.assertEqual(len(privileges), 70)
        self.assertEqual(len(actual), len(privileges))
        self.assertEqual(set(actual), set(privileges))
        roles = self.realm["roles"]["realm"]
        self.assertEqual(len(roles), 4)
        self.assertEqual({r["name"] for r in roles}, set(matrix))
        for role in roles:
            self.assertTrue(role["composite"])
            self.assertEqual(role["composites"], {"client": {"nexus-web": matrix[role["name"]]}})

    def test_public_client_requires_s256_and_limits_redirects(self):
        self.assertEqual(self.realm["realm"], "nexus")
        self.assertTrue(self.realm["enabled"])
        self.assertEqual(self.client["clientId"], "nexus-web")
        self.assertTrue(self.client["publicClient"])
        self.assertTrue(self.client["standardFlowEnabled"])
        for key in ("implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled", "fullScopeAllowed"):
            self.assertFalse(self.client[key], key)
        self.assertEqual(self.client["attributes"]["pkce.code.challenge.method"], "S256")
        self.assertEqual(self.client["redirectUris"], ["http://localhost:3000/*"])
        self.assertEqual(self.client["webOrigins"], ["http://localhost:3000"])

    def test_flat_multivalued_claims_and_audience(self):
        mappers = {m["protocolMapper"]: m["config"] for m in self.client["protocolMappers"]}
        for provider, claim in (("oidc-usermodel-realm-role-mapper", "roles"),
                                ("oidc-usermodel-client-role-mapper", "privileges")):
            config = mappers[provider]
            self.assertEqual(config["claim.name"], claim)
            self.assertEqual(config["multivalued"], "true")
            self.assertEqual(config["access.token.claim"], "true")
            self.assertEqual(config["jsonType.label"], "String")
        self.assertEqual(mappers["oidc-usermodel-client-role-mapper"]["usermodel.clientRoleMapping.clientId"], "nexus-web")
        self.assertEqual(mappers["oidc-audience-mapper"]["included.custom.audience"], "nexus-services")
        self.assertEqual(self.realm["defaultSignatureAlgorithm"], "RS256")
        self.assertIn("basic", self.client["defaultClientScopes"], "Keycloak 26.7 needs basic to emit sub")
        self.assertNotIn("roles", self.client["defaultClientScopes"])
        privileges, matrix = source_matrix()
        self.assertEqual(set(self.realm["scopeMappings"][0]["roles"]), set(matrix))
        mappings = self.realm["clientScopeMappings"]["nexus-web"]
        web_mapping = next(mapping for mapping in mappings if mapping.get("client") == "nexus-web")
        self.assertEqual(set(web_mapping["roles"]), set(privileges))

    def test_demo_ids_roles_and_no_credentials(self):
        users = self.realm["users"]
        self.assertEqual(len(users), 3)
        for i, name in enumerate(("admin", "seller", "buyer"), 1):
            user = users[i - 1]
            self.assertEqual(user["id"], f"68000000-0000-4000-8000-{i:012d}")
            self.assertEqual(user["username"], f"{name}@nexus.local")
            self.assertEqual(user["email"], user["username"])
            self.assertTrue(user["enabled"])
            self.assertEqual(user["realmRoles"], [name.upper()])
            self.assertEqual(user["requiredActions"], [])

        def check(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    self.assertNotIn(key.lower(), {"credentials", "password", "secret", "password_hash", "secretdata", "credentialdata"})
                    check(child)
            elif isinstance(value, list):
                for child in value:
                    check(child)
        check(self.realm)

    def test_temporary_lockout_not_early_quick_login_lock(self):
        self.assertTrue(self.realm["bruteForceProtected"])
        self.assertFalse(self.realm["permanentLockout"])
        self.assertEqual(self.realm["maxTemporaryLockouts"], 0)
        self.assertEqual(self.realm["failureFactor"], 5)
        self.assertEqual(self.realm["waitIncrementSeconds"], 900)
        self.assertEqual(self.realm["maxFailureWaitSeconds"], 900)
        self.assertEqual(self.realm["quickLoginCheckMilliSeconds"], 0)
        self.assertGreater(self.realm["maxDeltaTimeSeconds"], 900)

    def test_refresh_tokens_are_rotated_and_never_reusable(self):
        # cookie phiên giữ refresh token: mỗi lần đổi phải nhận token mới, token cũ dùng lại thì Keycloak hủy phiên
        self.assertTrue(self.realm["revokeRefreshToken"])
        self.assertEqual(self.realm["refreshTokenMaxReuse"], 0)

    def test_standard_policy_matches_documented_baseline(self):
        # Chính sách giữ lịch sử để Admin không đặt lại đúng mật khẩu hiện tại.
        self.assertEqual(self.realm["passwordPolicy"], r"length(8) and passwordHistory(1) and regexPattern((?s).*\S.*)")

    def test_session_idle_timeout_covers_access_token_lifetime(self):
        self.assertGreaterEqual(self.realm["ssoSessionIdleTimeout"], self.realm["accessTokenLifespan"])
        self.assertGreaterEqual(self.realm["ssoSessionMaxLifespan"], self.realm["ssoSessionIdleTimeout"])


class RatingEligibilityMachineClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = json.loads((HERE / "auction-rating-client.json").read_text())

    def test_client_is_confidential_and_only_allows_service_accounts(self):
        self.assertEqual(self.client["clientId"], "nexus-auction-service")
        self.assertEqual(self.client["clientAuthenticatorType"], "client-secret")
        self.assertTrue(self.client["enabled"])
        self.assertTrue(self.client["serviceAccountsEnabled"])
        for setting in ("publicClient", "bearerOnly", "standardFlowEnabled", "implicitFlowEnabled",
                        "directAccessGrantsEnabled", "authorizationServicesEnabled", "fullScopeAllowed"):
            self.assertFalse(self.client[setting], setting)
        self.assertEqual(self.client["redirectUris"], [])
        self.assertEqual(self.client["webOrigins"], [])
        self.assertEqual(self.client["defaultClientScopes"], ["basic"])
        self.assertEqual(self.client["optionalClientScopes"], [])
        self.assertNotIn("secret", self.client)

    def test_access_token_has_only_the_internal_authority_and_machine_marker(self):
        mappers = self.client["protocolMappers"]
        self.assertEqual(len(mappers), 3)
        claims = {mapper["config"].get("claim.name"): mapper for mapper in mappers}
        privilege = claims["privileges"]
        self.assertEqual(privilege["protocolMapper"], "oidc-hardcoded-claim-mapper")
        self.assertEqual(privilege["config"]["jsonType.label"], "JSON")
        self.assertEqual(json.loads(privilege["config"]["claim.value"]), ["RATING.ELIGIBILITY.CHECK"])
        marker = claims["service_account"]
        self.assertEqual(marker["protocolMapper"], "oidc-hardcoded-claim-mapper")
        self.assertEqual(marker["config"]["jsonType.label"], "boolean")
        self.assertEqual(marker["config"]["claim.value"], "true")
        audience = next(mapper for mapper in mappers if mapper["protocolMapper"] == "oidc-audience-mapper")
        self.assertEqual(audience["config"]["included.custom.audience"], "nexus-services")
        for mapper in mappers:
            self.assertEqual(mapper["config"]["access.token.claim"], "true")
            self.assertEqual(mapper["config"]["id.token.claim"], "false")

    def test_provisioning_is_optional_and_uses_the_secret_free_client_template(self):
        script = (HERE / "seed-users.sh").read_text(encoding="utf-8")
        self.assertIn('if [ -n "${AUCTION_RATING_CLIENT_SECRET:-}" ]; then', script)
        self.assertIn('kc create clients -r nexus -f /opt/nexus/auction-rating-client.json -i', script)
        self.assertIn('kc update "clients/$client" -r nexus -f /opt/nexus/auction-rating-client.json -n', script)
        self.assertIn('"secret=$AUCTION_RATING_CLIENT_SECRET" -n', script)
        compose = (HERE.parent / "compose.yaml").read_text()
        self.assertIn('AUCTION_RATING_CLIENT_SECRET: ${AUCTION_RATING_CLIENT_SECRET:-}', compose)
        self.assertIn('./keycloak/auction-rating-client.json:/opt/nexus/auction-rating-client.json:ro', compose)
        example = (HERE.parent / ".env.example").read_text()
        self.assertRegex(example, r"(?m)^AUCTION_RATING_CLIENT_SECRET=$")


@unittest.skipIf(os.name == "nt" or not shutil.which("bash"), "Bash entry point runs on Unix")
class SeedScriptTest(unittest.TestCase):
    def run_script(self, *args, env=None):
        return subprocess.run(["bash", str(HERE / "seed-users.sh"), *args],
                              env=env, text=True, capture_output=True)

    def test_syntax_help_and_unknown_option(self):
        subprocess.run(["bash", "-n", str(HERE / "seed-users.sh")], check=True)
        self.assertEqual(self.run_script("--help").returncode, 0)
        self.assertEqual(self.run_script("--unknown").returncode, 2)
        self.assertEqual(self.run_script("--env-file").returncode, 2)

    def test_missing_env_file_fails_before_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_script("--env-file", str(Path(directory) / "missing.env"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing env file", result.stderr)

    def test_missing_secret_names_only_and_no_shell_evaluation(self):
        env = {"PATH": os.environ["PATH"], "KEYCLOAK_ADMIN": "admin"}
        result = self.run_script("--container", env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("KEYCLOAK_ADMIN_PASSWORD", result.stderr)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docker = root / "docker"
            docker.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
            docker.chmod(0o755)
            env_file = root / "path with spaces.env"
            marker = root / "must-not-exist"
            env_file.write_text(f'DEMO_SELLER_PASSWORD=$(touch "{marker}")\n')
            env["PATH"] = f'{root}{os.pathsep}{env["PATH"]}'
            result = self.run_script("--env-file", str(env_file), env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(marker.exists())
            args = json.loads(result.stdout)
            self.assertEqual(args[:3], ["compose", "--env-file", str(env_file)])
            self.assertEqual(args[-5:], ["run", "--rm", "--no-deps", "-T", "auth-seed"])


@unittest.skipUnless(os.name == "nt" and (shutil.which("pwsh") or shutil.which("powershell")),
                     "PowerShell entry point runs on Windows")
class PowerShellSeedScriptTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shell = shutil.which("pwsh") or shutil.which("powershell")
        cls.script = HERE / "seed-users.ps1"

    def run_script(self, *args):
        return subprocess.run([self.shell, "-NoProfile", "-File", str(self.script), *args],
                              text=True, capture_output=True)

    def test_help_and_missing_env_file(self):
        self.assertEqual(self.run_script("-Help").returncode, 0)
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_script("-EnvFile", str(Path(directory) / "missing.env"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing env file", result.stderr)

    def test_env_file_is_passed_to_compose_without_evaluation(self):
        def quote(value):
            return "'" + str(value).replace("'", "''") + "'"

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "path with spaces.env"
            marker = root / "must-not-exist"
            env_file.write_text(f'DEMO_SELLER_PASSWORD=$(New-Item {quote(marker)})\n')
            command = ("function docker { ConvertTo-Json -InputObject @($args) -Compress; "
                       "$global:LASTEXITCODE = 0 }; "
                       f"& {quote(self.script)} -EnvFile {quote(env_file)}")
            result = subprocess.run([self.shell, "-NoProfile", "-Command", command],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(marker.exists())
            args = json.loads(result.stdout)
            self.assertEqual(args[:3], ["compose", "--env-file", str(env_file)])
            self.assertEqual(args[-5:], ["run", "--rm", "--no-deps", "-T", "auth-seed"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
