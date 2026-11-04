"""Offline regression tests for release scope, upload completeness and stale-build guards."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from email.parser import BytesParser
from email.policy import default
from io import BytesIO
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from zipfile import ZipFile

SPEC = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)
SHA = "a" * 40
OLD = "b" * 40
ICON = b"\x89PNG\r\n\x1a\nfixture"
ICON_PATH = "assets/bmpquests/icon.png"


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
                asset = {"id": max([a["id"] for r in self.rows for a in r["assets"]] + [99]) + 1, "name": parse_qs(parsed.query)["name"][0],
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


class FakeCurseForge:
    def __init__(self, fail_loader=None):
        self.uploads = []
        self.fail_loader = fail_loader
        self.versions = [{"name": name, "id": id} for id, name in enumerate(("1.21.1", "Fabric", "NeoForge"), 100)]

    def game_versions(self):
        return self.versions

    def upload(self, path, metadata):
        if self.fail_loader and self.fail_loader in path.name:
            raise RuntimeError("simulated CurseForge upload failure")
        self.uploads.append((path, deepcopy(metadata)))
        return 9000 + len(self.uploads)


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        test_root = release.ROOT / "build/release-tests"
        test_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=test_root)
        self.root = Path(self.temp.name)
        if self.root.resolve().parent != test_root.resolve():
            raise ValueError("Test cleanup escaped its workspace")
        self.addCleanup(self.temp.cleanup)
        (self.root / "gradle.properties").write_text("version=1.0.0\nminecraft_version=1.21.1\nmod_id=bmpquests\nftb_quests_version=2101.1.37\n")
        (self.root / "changelogs").mkdir()
        (self.root / "changelogs/1.0.0.md").write_text("# BMP Quests 1.0.0\n\nRelease notes.\n")
        self.properties, _ = release.metadata(self.root, "")
        icon = self.root / "common/src/main/resources" / ICON_PATH
        icon.parent.mkdir(parents=True)
        icon.write_bytes(ICON)
        for name in ("README.md", "CONTRIBUTING.md", "LICENSE.txt"):
            (self.root / name).write_text("Release documentation\n")
        (self.root / "docs").mkdir()
        (self.root / "docs/guide.md").write_text("Player guide\n")

    def prepare(self):
        paths = release.jar_paths(self.properties)
        for relative in paths:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            with ZipFile(path, "w") as jar:
                jar.writestr(ICON_PATH, ICON)
                if "fabric" in relative.parts:
                    jar.writestr("fabric.mod.json", json.dumps({"version": "1.0.0", "icon": ICON_PATH,
                                                               "depends": {"ftbquests": ">=2101.1.37"}}))
                else:
                    jar.writestr("META-INF/neoforge.mods.toml", f'[[mods]]\nversion="1.0.0"\nlogoFile="{ICON_PATH}"\n'
                                 '[[dependencies.bmpquests]]\nmodId="ftbquests"\nversionRange="[2101.1.37,)"\n')
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
        self.assertEqual(len(row["assets"]), 8)
        create = next(payload for method, path, payload in api.calls if method == "POST" and path == "releases")
        self.assertTrue(create["draft"])
        publication = next(i for i, (m, p, data) in enumerate(api.calls) if m == "PATCH" and p == "releases/1" and data.get("draft") is False)
        self.assertTrue(all(i < publication for i, (m, p, _) in enumerate(api.calls) if m == "POST" and "assets?" in p))

    def test_same_version_replaces_only_its_release_and_tag(self):
        self.prepare()
        older = published("0.9.0", 2)
        api = FakeGitHub([published("1.0.0"), older], {"v1.0.0": OLD, "v0.9.0": OLD})
        api.rows[0]["assets"][0]["name"] = "bmpquests-fabric-1.21.1-1.0.0.jar"
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
        (self.root / "build/release/SHA256SUMS-fabric-1.21.1.txt").write_text("tampered")
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

    def curseforge_setup(self):
        self.prepare()
        api = FakeGitHub()
        self.publish(api)
        return api, FakeCurseForge()

    def upload_curseforge(self, api, curseforge):
        release.publish_curseforge(self.root, api, curseforge, ref="refs/heads/main", sha=SHA)

    def test_curseforge_uploads_only_playable_jars_with_loader_specific_dependencies(self):
        api, curseforge = self.curseforge_setup()
        self.upload_curseforge(api, curseforge)
        self.assertEqual(len(curseforge.uploads), 2)
        for (path, data), (loader, version_id, suffix) in zip(curseforge.uploads, (("fabric", 101, "fabric"), ("neoforge", 102, "forge"))):
            self.assertEqual(path.name, f"bmpquests-{loader}-1.21.1-1.0.0.jar")
            self.assertEqual(data["gameVersions"], [100, version_id])
            self.assertEqual(data["releaseType"], "release")
            self.assertEqual(data["changelog"], (self.root / "changelogs/1.0.0.md").read_text())
            expected = {f"ftb-quests-{suffix}", f"ftb-library-{suffix}", f"ftb-teams-{suffix}", "architectury-api"}
            if loader == "fabric":
                expected.add("fabric-api")
            self.assertEqual({p["slug"] for p in data["relations"]["projects"]}, expected)
            self.assertTrue(all(p["type"] == "requiredDependency" for p in data["relations"]["projects"]))

    def test_curseforge_rerun_after_github_republication_skips_identical_files(self):
        api, curseforge = self.curseforge_setup()
        self.upload_curseforge(api, curseforge)
        self.publish(api)
        self.upload_curseforge(api, curseforge)
        self.assertEqual(len(curseforge.uploads), 2)
        self.assertEqual(len(release.CURSEFORGE_RECEIPT.findall(api.rows[0]["body"])), 1)

    def test_curseforge_partial_failure_resumes_only_missing_loader(self):
        api, curseforge = self.curseforge_setup()
        curseforge.fail_loader = "neoforge"
        with self.assertRaisesRegex(RuntimeError, "CurseForge upload failure"):
            self.upload_curseforge(api, curseforge)
        self.assertEqual(set(release.read_receipt(api.rows[0]["body"])["files"]), {"1.21.1/fabric"})
        self.publish(api)
        curseforge.fail_loader = None
        self.upload_curseforge(api, curseforge)
        self.assertEqual(len(curseforge.uploads), 2)
        self.assertEqual(set(release.read_receipt(api.rows[0]["body"])["files"]), {"1.21.1/fabric", "1.21.1/neoforge"})

    def test_curseforge_changed_artifact_uploads_only_changed_loader(self):
        api, curseforge = self.curseforge_setup()
        self.upload_curseforge(api, curseforge)
        folder = self.root / "build/release"
        path = folder / "bmpquests-fabric-1.21.1-1.0.0.jar"
        path.write_bytes(path.read_bytes() + b"changed")
        manifest_path = folder / "release-manifest.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["files"][path.name] = release.digest(path)
        manifest_path.write_text(json.dumps(manifest))
        self.publish(api)
        self.upload_curseforge(api, curseforge)
        self.assertEqual(len(curseforge.uploads), 3)
        self.assertIn("-fabric-", curseforge.uploads[-1][0].name)

    def test_curseforge_stale_build_has_no_uploads(self):
        api, curseforge = self.curseforge_setup()
        api.current = OLD
        with self.assertRaisesRegex(ValueError, "Outdated build"):
            self.upload_curseforge(api, curseforge)
        self.assertEqual(curseforge.uploads, [])

    def test_curseforge_tampered_artifacts_have_no_network_calls(self):
        api, curseforge = self.curseforge_setup()
        api.calls.clear()
        (self.root / "build/release/SHA256SUMS-fabric-1.21.1.txt").write_text("tampered")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.upload_curseforge(api, curseforge)
        self.assertEqual(curseforge.uploads, [])
        self.assertEqual(api.calls, [])

    def test_curseforge_requires_published_github_release_and_matching_tag(self):
        api, curseforge = self.curseforge_setup()
        api.tags["v1.0.0"] = OLD
        with self.assertRaisesRegex(ValueError, "release tag"):
            self.upload_curseforge(api, curseforge)
        api.tags["v1.0.0"] = SHA
        api.rows[0]["draft"] = True
        with self.assertRaisesRegex(ValueError, "GitHub release before"):
            self.upload_curseforge(api, curseforge)
        self.assertEqual(curseforge.uploads, [])

    def test_curseforge_unknown_loader_is_rejected_before_any_upload(self):
        api, curseforge = self.curseforge_setup()
        curseforge.versions = curseforge.versions[:-1]
        with self.assertRaisesRegex(ValueError, "NeoForge"):
            self.upload_curseforge(api, curseforge)
        self.assertEqual(curseforge.uploads, [])

    def test_curseforge_api_requires_author_token(self):
        with self.assertRaisesRegex(ValueError, "CURSEFORGE_TOKEN"):
            release.CurseForge("")

    def test_curseforge_multipart_request_contains_original_file_and_metadata(self):
        self.prepare()
        path = self.root / "build/release/bmpquests-fabric-1.21.1-1.0.0.jar"
        metadata = {"changelog": "Release notes", "gameVersions": [100, 101], "releaseType": "release"}
        with patch.object(release, "urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value = BytesIO(b'{"id": 123}')
            self.assertEqual(release.CurseForge("test-author-token").upload(path, metadata), 123)
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, f"https://minecraft.curseforge.com/api/projects/{release.CURSEFORGE_PROJECT_ID}/upload-file")
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("X-api-token"), "test-author-token")
        self.assertNotIn("test-author-token", request.full_url)
        envelope = b"Content-Type: " + request.get_header("Content-type").encode() + b"\r\nMIME-Version: 1.0\r\n\r\n"
        parts = {part.get_param("name", header="content-disposition"): part for part in
                 BytesParser(policy=default).parsebytes(envelope + request.data).iter_parts()}
        self.assertEqual(json.loads(parts["metadata"].get_payload(decode=True)), metadata)
        self.assertEqual(parts["file"].get_filename(), path.name)
        self.assertEqual(parts["file"].get_payload(decode=True), path.read_bytes())

    def test_staging_checks_both_packaged_icons(self):
        self.prepare()
        (self.root / "common/src/main/resources" / ICON_PATH).write_bytes(b"changed icon")
        with self.assertRaisesRegex(ValueError, "mod icon"):
            self.prepare_again()

    def test_staging_rejects_fixed_ftb_quests_version(self):
        self.prepare()
        path = self.root / "fabric/build/libs/bmpquests-fabric-1.21.1-1.0.0.jar"
        with ZipFile(path, "w") as jar:
            jar.writestr(ICON_PATH, ICON)
            jar.writestr("fabric.mod.json", json.dumps({"version": "1.0.0", "icon": ICON_PATH,
                                                       "depends": {"ftbquests": "=2101.1.37"}}))
        with self.assertRaisesRegex(ValueError, "open upper bound"):
            self.prepare_again()

    def stage_loaders(self, loaders):
        with patch.object(release.subprocess, "check_output", side_effect=lambda cmd, **kw: SHA + "\n" if cmd[1] == "rev-parse" else ""):
            release.stage(self.root, loaders)

    def test_selective_staging_needs_no_unselected_build_outputs(self):
        self.prepare()
        for path in release.jar_paths(self.properties, ("neoforge",)):
            (self.root / path).unlink()
        self.stage_loaders(("fabric",))
        folder = self.root / "build/release"
        manifest = json.loads((folder / "release-manifest.json").read_text())
        self.assertEqual(manifest["loaders"], ["fabric"])
        self.assertEqual(set(manifest["files"]), release.release_names(self.properties, ("fabric",)))
        with ZipFile(folder / release.bundle_name(self.properties, "fabric")) as bundle:
            self.assertEqual({name for name in bundle.namelist() if name.startswith("mods/")},
                             {"mods/bmpquests-fabric-1.21.1-1.0.0.jar"})
            self.assertIn("docs/guide.md", bundle.namelist())
            self.assertIn("changelogs/1.0.0.md", bundle.namelist())
            self.assertFalse(any("neoforge" in name for name in bundle.namelist()))

    def test_selective_publication_preserves_other_loaders_and_minecraft_versions(self):
        api, curseforge = self.curseforge_setup()
        self.upload_curseforge(api, curseforge)
        # Another supported Minecraft build can share this mod version's release.
        foreign = {"id": 2000, "name": "bmpquests-fabric-1.20.1-1.0.0.jar", "size": 123, "state": "uploaded"}
        api.rows[0]["assets"].append(deepcopy(foreign))
        neoforge = deepcopy([a for a in api.rows[0]["assets"] if "neoforge" in a["name"]])
        receipt_before = release.read_receipt(api.rows[0]["body"])["files"]["1.21.1/neoforge"]
        jar = self.root / release.jar_paths(self.properties, ("fabric",))[0]
        jar.write_bytes(jar.read_bytes() + b"updated Fabric build")
        self.stage_loaders(("fabric",))
        self.publish(api)
        self.upload_curseforge(api, curseforge)
        self.assertEqual([a for a in api.rows[0]["assets"] if "neoforge" in a["name"]], neoforge)
        self.assertIn(foreign, api.rows[0]["assets"])
        self.assertEqual(release.read_receipt(api.rows[0]["body"])["files"]["1.21.1/neoforge"], receipt_before)
        self.assertEqual(len(curseforge.uploads), 3)
        self.assertIn("-fabric-", curseforge.uploads[-1][0].name)

    def test_fabric_only_release_uploads_no_neoforge_file_to_curseforge(self):
        self.prepare()
        self.stage_loaders(("fabric",))
        api, curseforge = FakeGitHub(), FakeCurseForge()
        self.publish(api)
        self.upload_curseforge(api, curseforge)
        self.assertEqual(len(api.rows[0]["assets"]), 4)
        self.assertEqual(len(curseforge.uploads), 1)
        self.assertIn("-fabric-", curseforge.uploads[0][0].name)

    def test_publication_and_receipts_keep_minecraft_ports_separate(self):
        api, curseforge = self.curseforge_setup()
        self.upload_curseforge(api, curseforge)
        original_assets = deepcopy(api.rows[0]["assets"])
        properties = self.root / "gradle.properties"
        properties.write_text(properties.read_text().replace("minecraft_version=1.21.1", "minecraft_version=1.20.1"))
        self.properties, _ = release.metadata(self.root, "")
        self.prepare()
        self.stage_loaders(("fabric",))
        curseforge.versions.append({"name": "1.20.1", "id": 200})
        self.publish(api)
        self.upload_curseforge(api, curseforge)
        self.assertTrue(all(asset in api.rows[0]["assets"] for asset in original_assets))
        self.assertEqual(len(api.rows[0]["assets"]), 12)
        self.assertEqual(len(curseforge.uploads), 3)
        self.assertEqual(curseforge.uploads[-1][1]["gameVersions"], [200, 101])
        self.assertEqual(set(release.read_receipt(api.rows[0]["body"])["files"]),
                         {"1.21.1/fabric", "1.21.1/neoforge", "1.20.1/fabric"})


if __name__ == "__main__":
    unittest.main()
