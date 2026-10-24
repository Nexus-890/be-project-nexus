#!/usr/bin/env python3
"""Offline contract checks: python3 keycloak/test-realm.py (no dependencies)."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


HERE = Path(__file__).resolve().parent
BACKEND = HERE.parents[2]
SQL = BACKEND / "user-service/src/main/resources/db/migration/V3__roles_and_accounts.sql"


def source_matrix():
    sql = SQL.read_text()
    privileges = re.findall(r"\('([^']+)',", sql.split("ON CONFLICT")[0])
    matrix = {}
    for block in sql.split("INSERT INTO role_permissions")[1:]:
        role = re.search(r"WHERE r.code = '([^']+)'", block)[1]
        matrix[role] = re.findall(r"'([A-Z_]+(?:\.[A-Z_]+)+)'", block.split("ON CONFLICT")[0])
    return privileges, matrix


class RealmContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.realm = json.loads((HERE / "realm-nexus.json").read_text())
        cls.client = cls.realm["clients"][0]

    def test_exact_privileges_and_composite_matrix_from_v3(self):
        privileges, matrix = source_matrix()
        actual = [r["name"] for r in self.realm["roles"]["client"]["nexus-web"]]
        self.assertEqual(len(privileges), 65)
        self.assertEqual(len(actual), 65)
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
        self.assertNotIn("roles", self.client["defaultClientScopes"])
        self.assertEqual(set(self.realm["scopeMappings"][0]["roles"]), set(source_matrix()[1]))

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

    def test_standard_policy_matches_documented_baseline(self):
        self.assertEqual(self.realm["passwordPolicy"], r"length(8) and regexPattern((?s).*\S.*)")


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


if __name__ == "__main__":
    unittest.main(verbosity=2)
