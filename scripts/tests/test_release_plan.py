"""Release requests must be explicit and select only supported build variants."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("release_plan", Path(__file__).resolve().parents[1] / "release_plan.py")
planner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(planner)
ROWS = [
    {"minecraft": "1.21.1", "directory": ".", "java": "25", "toolchain": "21", "loaders": ["fabric", "neoforge"]},
    {"minecraft": "1.20.1", "directory": "versions/1.20.1", "java": "21", "toolchain": "17", "loaders": ["fabric"]},
]


class ReleasePlanTests(unittest.TestCase):
    def test_ordinary_commits_do_not_publish(self):
        for message in ("Fix Fabric crash", "Update release notes", "Document `release all`", "fix: release all", ""):
            with self.subTest(message=message):
                result = planner.plan("push", {"head_commit": {"message": message}}, "refs/heads/main", ROWS)
                self.assertFalse(result["publish"])
                self.assertEqual(result["matrix"]["include"], ROWS)

    def test_only_head_commit_and_explicit_manual_input_can_request_release(self):
        event = {"commits": [{"message": "release all"}], "head_commit": {"message": "Ordinary update"}}
        self.assertFalse(planner.plan("push", event, "refs/heads/main", ROWS)["publish"])
        self.assertFalse(planner.plan("workflow_dispatch", {}, "refs/heads/main", ROWS)["publish"])
        self.assertTrue(planner.plan("workflow_dispatch", {"inputs": {"command": "release fabric"}}, "refs/heads/main", ROWS)["publish"])

    def test_pull_requests_feature_branches_and_tags_never_publish(self):
        event = {"head_commit": {"message": "release all"}, "inputs": {"command": "release all"}}
        for name, ref in (("pull_request", "refs/heads/main"), ("push", "refs/heads/feature"),
                          ("push", "refs/tags/v1.0.1"), ("workflow_dispatch", "refs/heads/feature")):
            with self.subTest(name=name, ref=ref):
                self.assertFalse(planner.plan(name, event, ref, ROWS)["publish"])

    def test_release_all_includes_every_configured_version_and_loader(self):
        self.assertEqual(planner.select("release all", ROWS), ROWS)

    def test_loader_selection_applies_across_versions(self):
        self.assertEqual(planner.select("release fabric", ROWS), [dict(row, loaders=["fabric"]) for row in ROWS])
        self.assertEqual(planner.select("release neoforge", ROWS), [ROWS[0] | {"loaders": ["neoforge"]}])

    def test_minecraft_selection_includes_its_supported_loaders(self):
        self.assertEqual(planner.select("release 1.21.1", ROWS), [ROWS[0]])
        self.assertEqual(planner.select("release 1.20.1", ROWS), [ROWS[1]])

    def test_combined_selection_accepts_either_order(self):
        expected = [ROWS[0] | {"loaders": ["fabric"]}]
        self.assertEqual(planner.select("release 1.21.1 fabric", ROWS), expected)
        self.assertEqual(planner.select("release fabric 1.21.1", ROWS), expected)

    def test_standalone_body_command_and_case_are_supported(self):
        result = planner.plan("push", {"head_commit": {"message": "Fix Fabric crash\n\nRelease FABRIC 1.21.1\n"}}, "refs/heads/main", ROWS)
        self.assertTrue(result["publish"])
        self.assertEqual(result["matrix"]["include"], [ROWS[0] | {"loaders": ["fabric"]}])

    def test_ambiguous_unsupported_and_malformed_requests_fail_closed(self):
        for command in ("release", "release forge", "release 1.19.2", "release 1.20.1 neoforge",
                        "release all fabric", "release fabric neoforge", "release fabric fabric",
                        "release 1.21.1 1.20.1", "release all; echo bad"):
            with self.subTest(command=command), self.assertRaises(ValueError):
                planner.select(command, ROWS)
        with self.assertRaisesRegex(ValueError, "one standalone"):
            planner.plan("push", {"head_commit": {"message": "release fabric\nrelease neoforge"}}, "refs/heads/main", ROWS)

    def test_repository_config_matches_real_build(self):
        self.assertTrue(planner.targets())

    def test_build_only_invokes_selected_loader_tasks(self):
        env = {"BUILD_DIRECTORY": ".", "BUILD_MINECRAFT": "1.21.1", "BUILD_LOADERS": '["fabric"]'}
        with patch.dict(planner.os.environ, env), patch.object(planner.subprocess, "run") as run:
            planner.build()
        command = run.call_args.args[0]
        self.assertIn(":fabric:build", command)
        self.assertNotIn(":neoforge:build", command)
        self.assertIn("-PreleaseLoaders=fabric", command)

    def test_config_rejects_outside_directory_and_version_mismatch(self):
        base = planner.ROOT / "build/release-tests"
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as temporary:
            root = Path(temporary)
            (root / "gradlew").touch()
            (root / "gradle.properties").write_text("minecraft_version=1.21.1\n")
            config = root / "release-targets.json"
            for rows in ([ROWS[0] | {"directory": ".."}], [ROWS[0] | {"minecraft": "1.20.1"}]):
                config.write_text(json.dumps({"targets": rows}))
                with self.subTest(rows=rows), self.assertRaises(ValueError):
                    planner.targets(root)


if __name__ == "__main__":
    unittest.main()
