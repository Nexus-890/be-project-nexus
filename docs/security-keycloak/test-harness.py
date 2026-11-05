#!/usr/bin/env python3
"""Offline API compatibility check against the actual #236 fixture helpers."""
import ast
import importlib.util
import inspect
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FixtureCompatibilityTest(unittest.TestCase):
    def test_all_fixture_calls_match_the_committed_helper_api(self):
        harness = load("token_trust", HERE / "test-token-trust.py")
        fixture = load("keycloak_fixture", harness.KEYCLOAK / "test-keycloak.py")
        tree = ast.parse((HERE / "test-token-trust.py").read_text())
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Attribute)
                 and isinstance(node.func.value, ast.Name) and node.func.value.id == "kc"]
        self.assertTrue(calls, "Harness must exercise the actual Keycloak helpers")
        for call in calls:
            with self.subTest(helper=call.func.attr, line=call.lineno):
                signature = inspect.signature(getattr(fixture, call.func.attr))
                signature.bind(*[None for _ in call.args],
                               **{keyword.arg: None for keyword in call.keywords})


if __name__ == "__main__":
    unittest.main(verbosity=2)
