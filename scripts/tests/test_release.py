"""Offline regression tests for release scope, upload completeness and stale-build guards."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = "a" * 40
OLD = "b" * 40


class FakeGitHub:
    def __init__(self, releases=(), tags=None, current=SHA, fail_upload=False):
        self.rows = deepcopy(list(releases))
        self.tags = dict(tags or {})
        self.current = current
        self.fail_upload = fail_upload
        self.calls = []

    def releases(self):
        return deepcopy(self.rows)

    def request(self, method, path, payload=None, **kwargs):
        self.calls.append((method, path, deepcopy(payload)))
        if method == "GET" and not path:
            return {"default_branch": "main"}
        if method == "GET" and path.startswith("git/ref/"):
            ref = path.removeprefix("git/ref/")
            sha = self.current if ref == "heads/main" else self.tags.get(ref.removeprefix("tags/"))
            return {"object": {"sha": sha, "type": "commit"}} if sha else None
        if method == "POST" and path == "git/refs":
            self.tags[payload["ref"].removeprefix("refs/tags/")] = payload["sha"]
            return {}
        if method == "PATCH" and path.startswith("git/refs/tags/"):
            self.tags[path.removeprefix("git/refs/tags/")] = payload["sha"]
            return {}
        if method == "POST" and path == "releases":
            row = {**payload, "id": max([r["id"] for r in self.rows] + [0]) + 1, "assets": [], "html_url": "https://github.com/test/repo/releases/tag/" + payload["tag_name"]}
            self.rows.append(row)
            return deepcopy(row)
        if method == "DELETE" and path.startswith("releases/assets/"):
            asset_id = int(path.rsplit("/", 1)[1])
            for row in self.rows:
                row["assets"] = [a for a in row["assets"] if a["id"] != asset_id]
            return None
        if path.startswith("releases/"):
            parsed = urlsplit(path)
            parts = parsed.path.split("/")
            row = next(r for r in self.rows if r["id"] == int(parts[1]))
            if method == "POST" and parts[-1] == "assets":
                if self.fail_upload:
                    raise RuntimeError("simulated upload failure")
                asset = {"id": len(row["assets"]) + 100, "name": parse_qs(parsed.query)["name"][0],
                         "size": len(payload), "state": "uploaded"}
                row["assets"].append(asset)
                return deepcopy(asset)
            if method == "PATCH":
                row.update(payload)
            return deepcopy(row)
        raise AssertionError((method, path))


def published(version, id=1):
    return {"id": id, "tag_name": "v" + version, "draft": False, "prerelease": False,
            "assets": [{"id": id * 1000, "name": "old.jar"}], "html_url": "https://github.com/test/repo/releases/tag/v" + version}


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        test_root = release.ROOT / "build/release-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=test_root)
        self.root = Path(self.temp.name)
        if self.root.resolve().parent != test_root.resolve():
            raise ValueError("Test cleanup escaped its workspace")
        self.addCleanup(self.temp.cleanup)
        (self.root / "gradle.properties").write_text("version=1.0.0\nminecraft_version=1.21.1\nmod_id=bmpquests\n")
        (self.root / "changelogs").mkdir()
        (self.root / "changelogs/1.0.0.md").write_text("# BMP Quests 1.0.0\n\nRelease notes.\n")
        self.properties, _ = release.metadata(self.root, "")

    def prepare(self):
        paths = release.deliverables(self.properties)
        for relative in paths[:-1]:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            with ZipFile(path, "w") as jar:
                if "fabric" in relative.parts:
                    jar.writestr("fabric.mod.json", json.dumps({"version": "1.0.0"}))
                else:
                    jar.writestr("META-INF/neoforge.mods.toml", '[[mods]]\nversion="1.0.0"\n')
        archive = self.root / paths[-1]
        archive.parent.mkdir(parents=True)
        with ZipFile(archive, "w") as zip:
            for path in paths[:-1]:
                zip.writestr(("sources/" if "-sources" in path.name else "mods/") + path.name, b"data")
            zip.writestr("changelogs/1.0.0.md", "Release notes")
        with patch.object(release.subprocess, "check_output", side_effect=lambda cmd, **kw: SHA + "\n" if cmd[1] == "rev-parse" else ""):
            release.stage(self.root)

    def publish(self, api, ref="refs/heads/main", sha=SHA):
        release.publish(self.root, api, ref=ref, sha=sha)

    def test_tag_must_match_property_version(self):
        with self.assertRaisesRegex(ValueError, "does not match"):
            release.metadata(self.root, "refs/tags/v1.0.1")
        release.metadata(self.root, "refs/tags/v1.0.0")

    def test_version_notes_are_required(self):
        (self.root / "changelogs/1.0.0.md").unlink()
        with self.assertRaisesRegex(ValueError, "Missing release notes"):
            release.metadata(self.root, "")

    def test_stage_only_current_version_files(self):
        self.prepare()
        folder = self.root / "build/release"
        (folder / "old-0.9.0.jar").write_bytes(b"stale")
        (self.root / "fabric/build/libs/old-0.9.0.jar").write_bytes(b"previous version")
        self.prepare_again()
        self.assertNotIn("old-0.9.0.jar", {p.name for p in folder.iterdir()})
        self.assertTrue((self.root / "fabric/build/libs/old-0.9.0.jar").is_file())

    def prepare_again(self):
        with patch.object(release.subprocess, "check_output", side_effect=lambda cmd, **kw: SHA + "\n" if cmd[1] == "rev-parse" else ""):
            release.stage(self.root)

    def test_new_version_stays_draft_until_all_assets_uploaded(self):
        self.prepare()
        api = FakeGitHub()
        self.publish(api)
        self.assertEqual(api.tags, {"v1.0.0": SHA})
        row = api.rows[0]
        self.assertFalse(row["draft"])
        self.assertEqual(row["make_latest"], "true")
        self.assertEqual(len(row["assets"]), 6)
        create = next(payload for method, path, payload in api.calls if method == "POST" and path == "releases")
        self.assertTrue(create["draft"])
        publication = next(i for i, (m, p, data) in enumerate(api.calls) if m == "PATCH" and p == "releases/1" and data.get("draft") is False)
        self.assertTrue(all(i < publication for i, (m, p, _) in enumerate(api.calls) if m == "POST" and "assets?" in p))

    def test_same_version_replaces_only_its_release_and_tag(self):
        self.prepare()
        older = published("0.9.0", 2)
        api = FakeGitHub([published("1.0.0"), older], {"v1.0.0": OLD, "v0.9.0": OLD})
        self.publish(api)
        self.assertEqual(len(api.rows), 2)
        self.assertEqual(api.rows[1], older)
        self.assertEqual(api.tags["v0.9.0"], OLD)
        self.assertEqual(api.tags["v1.0.0"], SHA)
        deleted = [p for m, p, _ in api.calls if m == "DELETE"]
        self.assertEqual(deleted, ["releases/assets/1000"])

    def test_older_stable_release_does_not_displace_latest(self):
        self.prepare()
        newer = published("2.0.0", 2)
        api = FakeGitHub([newer])
        self.publish(api)
        self.assertEqual(api.rows[0], newer)
        self.assertEqual(api.rows[1]["make_latest"], "false")

    def test_prerelease_never_becomes_latest(self):
        self.assertFalse(release.should_be_latest("3.0.0-rc.1", [published("1.0.0")]))
        self.assertTrue(release.should_be_latest("3.0.0", [published("2.0.0")]))

    def test_stale_branch_build_has_no_remote_writes(self):
        self.prepare()
        api = FakeGitHub(current=OLD)
        with self.assertRaisesRegex(ValueError, "Outdated build"):
            self.publish(api)
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def test_moved_tag_has_no_remote_writes(self):
        self.prepare()
        api = FakeGitHub(tags={"v1.0.0": OLD})
        with self.assertRaisesRegex(ValueError, "Outdated build"):
            self.publish(api, "refs/tags/v1.0.0")
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def test_feature_branch_cannot_publish(self):
        self.prepare()
        api = FakeGitHub()
        with self.assertRaisesRegex(ValueError, "Only the default branch"):
            self.publish(api, "refs/heads/feature")
        self.assertTrue(all(method == "GET" for method, _, _ in api.calls))

    def test_upload_failure_leaves_draft(self):
        self.prepare()
        api = FakeGitHub(fail_upload=True)
        with self.assertRaisesRegex(RuntimeError, "upload failure"):
            self.publish(api)
        self.assertTrue(api.rows[0]["draft"])
        self.assertFalse(any(m == "PATCH" and p.startswith("releases/") and data.get("draft") is False for m, p, data in api.calls))

    def test_changed_asset_is_rejected_before_network(self):
        self.prepare()
        (self.root / "build/release/SHA256SUMS.txt").write_text("tampered")
        api = FakeGitHub()
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.publish(api)
        self.assertEqual(api.calls, [])

    def test_dirty_commit_is_not_publishable(self):
        self.prepare()
        path = self.root / "build/release/release-manifest.json"
        manifest = json.loads(path.read_text())
        manifest["dirty"] = True
        path.write_text(json.dumps(manifest))
        api = FakeGitHub()
        with self.assertRaisesRegex(ValueError, "clean tested commit"):
            self.publish(api)
        self.assertEqual(api.calls, [])

    def test_branch_moving_during_upload_keeps_release_as_draft(self):
        self.prepare()

        class MovingBranch(FakeGitHub):
            def request(self, method, path, payload=None, **kwargs):
                result = super().request(method, path, payload, **kwargs)
                if method == "POST" and "assets?" in path:
                    self.current = OLD
                return result

        api = MovingBranch()
        with self.assertRaisesRegex(ValueError, "Outdated build"):
            self.publish(api)
        self.assertTrue(api.rows[0]["draft"])

    def test_incomplete_draft_is_never_published(self):
        self.prepare()

        class MissingAsset(FakeGitHub):
            def request(self, method, path, payload=None, **kwargs):
                result = super().request(method, path, payload, **kwargs)
                if method == "GET" and path == "releases/1":
                    result["assets"].pop()
                return result

        api = MissingAsset()
        with self.assertRaisesRegex(RuntimeError, "complete tested release set"):
            self.publish(api)
        self.assertTrue(api.rows[0]["draft"])


if __name__ == "__main__":
    unittest.main()
