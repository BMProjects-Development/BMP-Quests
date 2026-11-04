"""Validate, stage and publish only the tested version's release files."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import uuid4
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?")
CURSEFORGE_PROJECT_ID = 1769230
CURSEFORGE_RECEIPT = re.compile(r"\n?<!-- bmpquests-curseforge (\{[^\n]*\}) -->")
LOADERS = ("fabric", "neoforge")


def version_parts(version):
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError(f"Invalid release version: {version}")
    return tuple(int(match[i]) for i in (1, 2, 3)), match[4] is not None


def metadata(root=ROOT, ref=None):
    properties = {}
    for line in (root / "gradle.properties").read_text(encoding="utf-8-sig").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    version = properties["version"]
    version_parts(version)
    notes = root / "changelogs" / f"{version}.md"
    if not notes.is_file() or not notes.read_text(encoding="utf-8-sig").strip():
        raise ValueError(f"Missing release notes: {notes}")
    ref = os.environ.get("GITHUB_REF", "") if ref is None else ref
    if ref.startswith("refs/tags/") and ref != f"refs/tags/v{version}":
        raise ValueError(f"Tag {ref} does not match version {version}")
    return properties, notes


def jar_paths(properties, loaders=LOADERS):
    version, mc, mod = (properties[key] for key in ("version", "minecraft_version", "mod_id"))
    paths = []
    for loader in loaders:
        for classifier in ("", "-sources"):
            paths.append(Path(loader) / "build/libs" / f"{mod}-{loader}-{mc}-{version}{classifier}.jar")
    return paths


def bundle_name(properties, loader):
    return f"{properties['mod_id']}-{loader}-{properties['minecraft_version']}-{properties['version']}.zip"


def checksum_name(properties, loader):
    return f"SHA256SUMS-{loader}-{properties['minecraft_version']}.txt"


def release_names(properties, loaders):
    return ({p.name for p in jar_paths(properties, loaders)}
            | {bundle_name(properties, loader) for loader in loaders}
            | {checksum_name(properties, loader) for loader in loaders})


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_jars(root, properties, loaders=LOADERS):
    for loader in loaders:
        relative = jar_paths(properties, (loader,))[0]
        with ZipFile(root / relative) as jar:
            if loader == "fabric":
                data = json.loads(jar.read("fabric.mod.json"))
                version, icon = data["version"], data["icon"]
                dependency = data["depends"]["ftbquests"]
                expected_dependency = f">={properties['ftb_quests_version']}"
            else:
                data = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
                mod = data["mods"][0]
                version, icon = mod["version"], mod["logoFile"]
                dependency = next(d["versionRange"] for d in data["dependencies"][properties["mod_id"]]
                                  if d["modId"] == "ftbquests")
                expected_dependency = f"[{properties['ftb_quests_version']},)"
            if version != properties["version"]:
                raise ValueError(f"Wrong version inside {relative}: {version}")
            if dependency != expected_dependency:
                raise ValueError(f"FTB Quests must have an open upper bound inside {relative}")
            expected_icon = f"assets/{properties['mod_id']}/icon.png"
            if icon != expected_icon or jar.read(icon) != (root / "common/src/main/resources" / expected_icon).read_bytes():
                raise ValueError(f"Missing or changed mod icon inside {relative}")


def stage(root=ROOT, loaders=LOADERS):
    properties, _ = metadata(root)
    if not loaders or len(set(loaders)) != len(loaders) or any(loader not in LOADERS for loader in loaders):
        raise ValueError("Select supported release loaders: fabric and/or neoforge")
    paths = jar_paths(properties, loaders)
    for path in paths:
        if not (root / path).is_file():
            raise ValueError(f"Missing deliverable: {path}")
    validate_jars(root, properties, loaders)
    destination = root / "build/release"
    destination.mkdir(parents=True, exist_ok=True)
    # Remove stale staging files individually, never other versions' build outputs.
    for path in destination.iterdir():
        if not path.is_file():
            raise ValueError(f"Unexpected staging directory: {path}")
        path.unlink()
    for path in paths:
        shutil.copyfile(root / path, destination / path.name)
    for loader in loaders:
        bundle = destination / bundle_name(properties, loader)
        with ZipFile(bundle, "w", compression=ZIP_DEFLATED) as archive:
            for path in jar_paths(properties, (loader,)):
                archive.write(root / path, ("sources/" if path.name.endswith("-sources.jar") else "mods/") + path.name)
            for directory in ("docs", "changelogs"):
                for path in sorted((root / directory).rglob("*")):
                    if path.is_file():
                        archive.write(path, path.relative_to(root).as_posix())
            for name in ("README.md", "CONTRIBUTING.md", "LICENSE.txt"):
                archive.write(root / name, name)
        names = [p.name for p in jar_paths(properties, (loader,))] + [bundle.name]
        (destination / checksum_name(properties, loader)).write_text(
            "".join(f"{digest(destination / name)}  {name}\n" for name in names), encoding="utf-8")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root, text=True).strip())
    files = sorted(release_names(properties, loaders))
    manifest = {"version": properties["version"], "commit": commit, "dirty": dirty,
                "minecraft": properties["minecraft_version"], "loaders": list(loaders),
                "files": {name: digest(destination / name) for name in files}}
    (destination / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Staged {len(files)} assets for v{properties['version']}")


class GitHub:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or not token:
            raise ValueError("GITHUB_REPOSITORY and GH_TOKEN are required")
        self.repository, self.token = repository, token

    def request(self, method, path, payload=None, *, upload=False, content_type="application/json"):
        host = "uploads.github.com" if upload else "api.github.com"
        body = payload if isinstance(payload, bytes) else None if payload is None else json.dumps(payload).encode()
        suffix = f"/{path}" if path else ""
        request = Request(f"https://{host}/repos/{self.repository}{suffix}", data=body, method=method,
                          headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                                   "X-GitHub-Api-Version": "2026-03-10", "Content-Type": content_type})
        try:
            with urlopen(request, timeout=120) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as error:
            if error.code == 404 and method == "GET":
                return None
            raise RuntimeError(f"GitHub API {method} {path} failed (HTTP {error.code})") from None

    def releases(self):
        result, page = [], 1
        while True:
            rows = self.request("GET", f"releases?per_page=100&page={page}")
            if rows is None:
                raise RuntimeError("Cannot read repository releases")
            result.extend(rows)
            if len(rows) < 100:
                return result
            page += 1


def resolve_ref(api, ref):
    result = api.request("GET", f"git/ref/{quote(ref, safe='/')}")
    if result is None:
        return None
    obj = result["object"]
    for _ in range(8):
        if obj["type"] == "commit":
            return obj["sha"]
        if obj["type"] != "tag":
            break
        obj = api.request("GET", f"git/tags/{obj['sha']}")["object"]
    raise ValueError("Unsupported tag target")


def check_current(api, ref, sha, default_branch):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid tested commit")
    if ref == f"refs/heads/{default_branch}":
        remote = resolve_ref(api, f"heads/{default_branch}")
    elif ref.startswith("refs/tags/v"):
        remote = resolve_ref(api, ref.removeprefix("refs/"))
    else:
        raise ValueError("Only the default branch and matching version tags may publish")
    if remote != sha:
        raise ValueError("Outdated build: the remote ref no longer points to the tested commit")


def should_be_latest(version, releases):
    current, prerelease = version_parts(version)
    if prerelease:
        return False
    for release in releases:
        if release["draft"] or release["prerelease"]:
            continue
        try:
            other, other_prerelease = version_parts(release["tag_name"].removeprefix("v"))
        except ValueError:
            continue
        if not other_prerelease and other > current:
            return False
    return True


def validate_staged(root, properties, sha):
    version = properties["version"]
    folder = root / "build/release"
    manifest = json.loads((folder / "release-manifest.json").read_text())
    loaders = manifest.get("loaders", [])
    if not loaders or len(set(loaders)) != len(loaders) or any(loader not in LOADERS for loader in loaders):
        raise ValueError("Invalid release manifest loader selection")
    expected = release_names(properties, loaders)
    if (manifest["version"] != version or manifest["commit"] != sha or manifest["dirty"]
            or manifest.get("minecraft") != properties["minecraft_version"]
            or set(manifest["files"]) != expected):
        raise ValueError("Release assets do not match the clean tested commit and version")
    for name, checksum in manifest["files"].items():
        if digest(folder / name) != checksum:
            raise ValueError(f"Release asset checksum mismatch: {name}")
    return folder, manifest, expected


def receipt_body(notes, receipt):
    body = notes.read_text(encoding="utf-8-sig").rstrip() + "\n"
    if receipt:
        body += "\n<!-- bmpquests-curseforge " + json.dumps(receipt, separators=(",", ":")) + " -->\n"
    return body


def read_receipt(body):
    matches = CURSEFORGE_RECEIPT.findall(body or "")
    if len(matches) > 1:
        raise ValueError("Multiple CurseForge publication receipts in the release description")
    receipt = json.loads(matches[0]) if matches else {}
    if receipt and (receipt.get("project") != CURSEFORGE_PROJECT_ID or not isinstance(receipt.get("files"), dict)):
        raise ValueError("Invalid CurseForge publication receipt")
    return receipt


def publish(root=ROOT, api=None, *, ref=None, sha=None):
    ref = os.environ.get("GITHUB_REF", "") if ref is None else ref
    sha = os.environ.get("GITHUB_SHA", "") if sha is None else sha
    properties, notes = metadata(root, ref)
    version, tag = properties["version"], f"v{properties['version']}"
    folder, manifest, expected = validate_staged(root, properties, sha)
    api = api or GitHub(os.environ["GITHUB_REPOSITORY"], os.environ.get("GH_TOKEN", ""))
    repository = api.request("GET", "")
    if repository is None:
        raise ValueError("Repository is not accessible with the workflow token")
    default_branch = repository["default_branch"]
    check_current(api, ref, sha, default_branch)
    releases = api.releases()
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    receipt = read_receipt(existing.get("body")) if existing else {}
    tag_sha = resolve_ref(api, f"tags/{tag}")
    if tag_sha is None:
        api.request("POST", "git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
    elif tag_sha != sha:
        api.request("PATCH", f"git/refs/tags/{tag}", {"sha": sha, "force": True})
    draft = {"tag_name": tag, "target_commitish": sha, "name": f"BMP Quests {version}",
             "body": receipt_body(notes, receipt), "draft": True,
             "prerelease": version_parts(version)[1], "make_latest": "false"}
    if existing:
        release = api.request("PATCH", f"releases/{existing['id']}", draft)
        retained = {asset["name"] for asset in release["assets"] if asset["name"] not in expected}
        for asset in release["assets"]:
            if asset["name"] in expected:
                api.request("DELETE", f"releases/assets/{asset['id']}")
    else:
        release = api.request("POST", "releases", draft)
        retained = set()
    release_id = release["id"]
    # Keep a failed/incomplete upload as a draft; no partial public release.
    for name in sorted(expected):
        path = folder / name
        asset = api.request("POST", f"releases/{release_id}/assets?name={quote(name)}", path.read_bytes(),
                            upload=True, content_type="application/octet-stream")
        if asset["state"] != "uploaded" or asset["size"] != path.stat().st_size:
            raise RuntimeError(f"Incomplete release upload: {name}")
        if asset.get("digest") and asset["digest"] != f"sha256:{manifest['files'][name]}":
            raise RuntimeError(f"Uploaded asset digest mismatch: {name}")
    check_current(api, ref, sha, default_branch)
    uploaded = api.request("GET", f"releases/{release_id}")["assets"]
    if {a["name"] for a in uploaded} != expected | retained:
        raise RuntimeError("Draft does not contain the complete tested release set")
    latest = should_be_latest(version, api.releases())
    result = api.request("PATCH", f"releases/{release_id}", {"draft": False, "make_latest": str(latest).lower()})
    print(f"Published {result['html_url']}")


class CurseForge:
    """CurseForge's author Upload API; no third-party libraries or Core API key."""

    def __init__(self, token):
        if not token:
            raise ValueError("Add the CurseForge author API token as the CURSEFORGE_TOKEN repository secret")
        self.token = token

    def request(self, path, body=None, content_type="application/json"):
        request = Request("https://minecraft.curseforge.com/api/" + path, data=body,
                          headers={"X-Api-Token": self.token, "Content-Type": content_type,
                                   "User-Agent": "BMP-Quests-release"})
        try:
            with urlopen(request, timeout=120) as response:
                return json.load(response)
        except HTTPError as error:
            raise RuntimeError(f"CurseForge API {path} failed (HTTP {error.code})") from None

    def game_versions(self):
        return self.request("game/versions")

    def upload(self, path, metadata):
        boundary = "bmpquests-" + uuid4().hex
        body = (
            f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
            'Content-Type: application/json\r\n\r\n'
        ).encode() + json.dumps(metadata).encode() + (
            f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            'Content-Type: application/java-archive\r\n\r\n'
        ).encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        result = self.request(f"projects/{CURSEFORGE_PROJECT_ID}/upload-file", body,
                              f"multipart/form-data; boundary={boundary}")
        if not isinstance(result.get("id"), int) or result["id"] <= 0:
            raise RuntimeError("CurseForge upload did not return a valid file ID")
        return result["id"]


def publish_curseforge(root=ROOT, api=None, curseforge=None, *, ref=None, sha=None):
    ref = os.environ.get("GITHUB_REF", "") if ref is None else ref
    sha = os.environ.get("GITHUB_SHA", "") if sha is None else sha
    properties, notes = metadata(root, ref)
    folder, manifest, _ = validate_staged(root, properties, sha)
    api = api or GitHub(os.environ["GITHUB_REPOSITORY"], os.environ.get("GH_TOKEN", ""))
    curseforge = curseforge or CurseForge(os.environ.get("CURSEFORGE_TOKEN", ""))
    default_branch = api.request("GET", "")["default_branch"]
    check_current(api, ref, sha, default_branch)
    tag = f"v{properties['version']}"
    if resolve_ref(api, f"tags/{tag}") != sha:
        raise ValueError("CurseForge publication requires the tested GitHub release tag")
    release = next((r for r in api.releases() if r["tag_name"] == tag and not r["draft"]), None)
    if not release:
        raise ValueError("Publish the complete tested GitHub release before uploading to CurseForge")
    receipt = read_receipt(release.get("body")) or {"project": CURSEFORGE_PROJECT_ID, "files": {}}
    paths = [p for p in jar_paths(properties, manifest["loaders"]) if not p.name.endswith("-sources.jar")]
    mc = properties["minecraft_version"]
    pending = [p for p in paths if receipt["files"].get(f"{mc}/{p.parts[0]}", {}).get("sha256") != manifest["files"][p.name]]
    if not pending:
        print(f"CurseForge already has the selected tested files for {tag}, Minecraft {mc}")
        return
    versions = curseforge.game_versions()
    names = [properties["minecraft_version"]] + [{"fabric": "Fabric", "neoforge": "NeoForge"}[p.parts[0]] for p in pending]
    version_ids = {}
    for name in names:
        matches = [v["id"] for v in versions if v["name"] == name]
        if len(matches) != 1:
            raise ValueError(f"Cannot uniquely resolve CurseForge game version: {name}")
        version_ids[name] = matches[0]
    for relative in pending:
        check_current(api, ref, sha, default_branch)
        loader = relative.parts[0]
        label = {"fabric": "Fabric", "neoforge": "NeoForge"}[loader]
        suffix = "fabric" if loader == "fabric" else "forge"
        dependencies = [f"ftb-quests-{suffix}", f"ftb-library-{suffix}", f"ftb-teams-{suffix}", "architectury-api"]
        if loader == "fabric":
            dependencies.append("fabric-api")
        data = {
            "displayName": f"BMP Quests {properties['version']} - {label} - {mc}",
            "changelog": notes.read_text(encoding="utf-8-sig"), "changelogType": "markdown",
            "releaseType": "beta" if version_parts(properties["version"])[1] else "release",
            "gameVersions": [version_ids[properties["minecraft_version"]], version_ids[label]],
            "relations": {"projects": [{"slug": slug, "type": "requiredDependency"} for slug in dependencies]},
        }
        file_id = curseforge.upload(folder / relative.name, data)
        receipt["files"][f"{mc}/{loader}"] = {"sha256": manifest["files"][relative.name], "id": file_id}
        # Checkpoint each successful loader so retries can resume a partial publication.
        api.request("PATCH", f"releases/{release['id']}", {"body": receipt_body(notes, receipt)})
        print(f"Uploaded {label} to https://www.curseforge.com/minecraft/mc-mods/bmp-quests/files/{file_id}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "stage", "publish", "publish-curseforge"))
    args = parser.parse_args()
    directory = os.environ.get("BUILD_DIRECTORY", ".")
    root = (ROOT / directory).resolve()
    if not root.is_relative_to(ROOT):
        raise ValueError("Build directory must stay inside the repository")
    loaders = json.loads(os.environ.get("BUILD_LOADERS", json.dumps(LOADERS)))
    if args.command == "validate":
        properties, _ = metadata(root)
        print(f"Release metadata valid: v{properties['version']}")
    elif args.command == "stage":
        stage(root, loaders)
    elif args.command == "publish":
        publish(root)
    else:
        publish_curseforge(root)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        sys.exit(str(error))
