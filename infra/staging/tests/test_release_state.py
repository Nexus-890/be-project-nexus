import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_state", Path(__file__).parents[1] / "scripts/release-state.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
A, B, C = "a" * 40, "b" * 40, "c" * 40


class ReleaseStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="nexus-release-state-")
        self.addCleanup(self.temporary.cleanup)
        self.state = module.ReleaseState(self.temporary.name)

    def deploy(self, sha):
        self.state.begin(sha)
        self.state.commit(sha)

    def test_first_success_has_no_previous_image(self):
        self.deploy(A)
        self.assertEqual(self.state.load(), {"version": 1, "current": A, "previous": None, "pending": None})
        with self.assertRaises(ValueError):
            self.state.rollback_target()

    def test_upgrade_retains_previous_success(self):
        self.deploy(A)
        self.deploy(B)
        self.assertEqual(self.state.rollback_target(), A)

    def test_redeploying_current_commit_preserves_older_rollback(self):
        self.deploy(A)
        self.deploy(B)
        self.deploy(B)
        self.assertEqual(self.state.rollback_target(), A)

    def test_failed_candidate_rolls_back_to_current_without_losing_history(self):
        self.deploy(A)
        self.deploy(B)
        self.state.begin(C)
        self.assertEqual(self.state.rollback_target(), B)
        self.state.rollback_success(B)
        self.assertEqual(self.state.load(), {"version": 1, "current": B, "previous": A, "pending": None})

    def test_failed_redeploy_can_recover_current_commit(self):
        self.deploy(A)
        self.deploy(B)
        self.state.begin(B)
        self.state.rollback_success(self.state.rollback_target())
        self.assertEqual(self.state.rollback_target(), A)

    def test_successful_rollback_swaps_the_two_successful_images(self):
        self.deploy(A)
        self.deploy(B)
        self.state.rollback_success(A)
        self.assertEqual(self.state.load(), {"version": 1, "current": A, "previous": B, "pending": None})

    def test_unfinished_first_deployment_has_no_invented_rollback(self):
        self.state.begin(A)
        with self.assertRaises(ValueError):
            self.state.rollback_target()
        self.state.begin(B)
        self.state.commit(B)
        self.assertIsNone(self.state.load()["previous"])

    def test_same_pending_candidate_can_be_retried(self):
        self.deploy(A)
        self.state.begin(B)
        self.state.begin(B)
        self.state.commit(B)
        self.assertEqual(self.state.load()["previous"], A)

    def test_another_candidate_cannot_hide_a_failed_upgrade(self):
        self.deploy(A)
        self.state.begin(B)
        before = self.state.path.read_bytes()
        with self.assertRaises(ValueError):
            self.state.begin(C)
        self.assertEqual(self.state.path.read_bytes(), before)

    def test_invalid_sha_and_stale_commit_leave_state_unchanged(self):
        self.deploy(A)
        before = self.state.path.read_bytes()
        for candidate in ("latest", "b" * 7, B):
            with self.assertRaises(ValueError):
                self.state.commit(candidate)
        self.assertEqual(self.state.path.read_bytes(), before)

    def test_wrong_rollback_target_leaves_state_unchanged(self):
        self.deploy(A)
        self.deploy(B)
        before = self.state.path.read_bytes()
        with self.assertRaises(ValueError):
            self.state.rollback_success(C)
        self.assertEqual(self.state.path.read_bytes(), before)

    def test_interrupted_atomic_replace_keeps_previous_valid_state(self):
        self.deploy(A)
        self.state.begin(B)
        before = self.state.path.read_bytes()
        with patch.object(module.os, "replace", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                self.state.commit(B)
        self.assertEqual(self.state.path.read_bytes(), before)
        self.assertEqual(self.state.rollback_target(), A)
        self.assertEqual(list(self.state.directory.glob(".release-*.tmp")), [])

    def test_damaged_json_is_not_replaced_with_a_guessed_release(self):
        self.state.path.write_text('{"version":', encoding="utf-8")
        with self.assertRaises(ValueError):
            self.state.begin(A)
        self.assertEqual(self.state.path.read_text(encoding="utf-8"), '{"version":')

    def test_legacy_failed_upgrade_can_be_migrated(self):
        for name, sha in (("current", B), ("previous", A), ("pending", C)):
            (self.state.directory / name).write_text(sha + "\n", encoding="utf-8")
        self.assertEqual(self.state.rollback_target(), B)
        self.state.commit(C)
        self.assertEqual(json.loads(self.state.path.read_text(encoding="utf-8")), {"version": 1, "current": C, "previous": B, "pending": None})

    def test_ambiguous_legacy_state_requires_operator_evidence(self):
        for name in ("current", "pending"):
            (self.state.directory / name).write_text(B, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            self.state.load()

    def test_cli_redeploy_and_rollback_use_the_same_state_contract(self):
        def run(*args):
            return subprocess.run([sys.executable, str(Path(module.__file__)), "--state-dir", str(self.state.directory), *args],
                                  check=True, text=True, capture_output=True).stdout.strip()
        self.assertEqual(run("current", "--fallback", A), A)
        for sha in (A, B, B):
            run("begin", sha)
            run("commit", sha)
        self.assertEqual(run("rollback-target"), A)
        run("rollback-success", A)
        self.assertEqual(json.loads(run("show")), {"version": 1, "current": A, "previous": B, "pending": None})

    def test_cli_invalid_sha_fails_without_creating_metadata(self):
        result = subprocess.run([sys.executable, str(Path(module.__file__)), "--state-dir", str(self.state.directory), "begin", "latest"],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("full lowercase commit SHA", result.stderr)
        self.assertFalse(self.state.path.exists())


if __name__ == "__main__":
    unittest.main()
