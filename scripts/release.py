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
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?")


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


def deliverables(properties):
    version, mc, mod = (properties[key] for key in ("version", "minecraft_version", "mod_id"))
    paths = []
    for loader in ("fabric", "neoforge"):
        for classifier in ("", "-sources"):
            paths.append(Path(loader) / "build/libs" / f"{mod}-{loader}-{mc}-{version}{classifier}.jar")
    paths.append(Path("build/distributions") / f"{mod}-{version}.zip")
    return paths


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_jars(root, properties):
    for loader in ("fabric", "neoforge"):
        relative = next(p for p in deliverables(properties) if p.parts[0] == loader and not p.name.endswith("-sources.jar"))
        with ZipFile(root / relative) as jar:
            if loader == "fabric":
                version = json.loads(jar.read("fabric.mod.json"))["version"]
            else:
                version = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())["mods"][0]["version"]
            if version != properties["version"]:
                raise ValueError(f"Wrong version inside {relative}: {version}")


def stage(root=ROOT):
    properties, _ = metadata(root)
    paths = deliverables(properties)
    for path in paths:
        if not (root / path).is_file():
            raise ValueError(f"Missing deliverable: {path}")
    validate_jars(root, properties)
    with ZipFile(root / paths[-1]) as archive:
        required = {f"mods/{p.name}" if not p.name.endswith("-sources.jar") else f"sources/{p.name}" for p in paths[:-1]}
        required.add(f"changelogs/{properties['version']}.md")
        if not required.issubset(archive.namelist()):
            raise ValueError("Distribution ZIP does not contain the complete release set")
    destination = root / "build/release"
    destination.mkdir(parents=True, exist_ok=True)
    # Remove stale staging files individually, never other versions' build outputs.
    for path in destination.iterdir():
        if not path.is_file():
            raise ValueError(f"Unexpected staging directory: {path}")
        path.unlink()
    for path in paths:
        shutil.copyfile(root / path, destination / path.name)
    checksums = destination / "SHA256SUMS.txt"
    checksums.write_text("".join(f"{digest(destination / p.name)}  {p.name}\n" for p in paths), encoding="utf-8")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=no"], cwd=root, text=True).strip())
    files = [p.name for p in paths] + [checksums.name]
    manifest = {"version": properties["version"], "commit": commit, "dirty": dirty,
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


def publish(root=ROOT, api=None, *, ref=None, sha=None):
    ref = os.environ.get("GITHUB_REF", "") if ref is None else ref
    sha = os.environ.get("GITHUB_SHA", "") if sha is None else sha
    properties, notes = metadata(root, ref)
    version, tag = properties["version"], f"v{properties['version']}"
    folder = root / "build/release"
    manifest = json.loads((folder / "release-manifest.json").read_text())
    expected = {p.name for p in deliverables(properties)} | {"SHA256SUMS.txt"}
    if (manifest["version"] != version or manifest["commit"] != sha or manifest["dirty"]
            or set(manifest["files"]) != expected):
        raise ValueError("Release assets do not match the clean tested commit and version")
    for name, checksum in manifest["files"].items():
        if digest(folder / name) != checksum:
            raise ValueError(f"Release asset checksum mismatch: {name}")
    api = api or GitHub(os.environ["GITHUB_REPOSITORY"], os.environ.get("GH_TOKEN", ""))
    repository = api.request("GET", "")
    if repository is None:
        raise ValueError("Repository is not accessible with the workflow token")
    default_branch = repository["default_branch"]
    check_current(api, ref, sha, default_branch)
    releases = api.releases()
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    tag_sha = resolve_ref(api, f"tags/{tag}")
    if tag_sha is None:
        api.request("POST", "git/refs", {"ref": f"refs/tags/{tag}", "sha": sha})
    elif tag_sha != sha:
        api.request("PATCH", f"git/refs/tags/{tag}", {"sha": sha, "force": True})
    draft = {"tag_name": tag, "target_commitish": sha, "name": f"BMP Quests {version}",
             "body": notes.read_text(encoding="utf-8-sig"), "draft": True,
             "prerelease": version_parts(version)[1], "make_latest": "false"}
    if existing:
        release = api.request("PATCH", f"releases/{existing['id']}", draft)
        for asset in release["assets"]:
            api.request("DELETE", f"releases/assets/{asset['id']}")
    else:
        release = api.request("POST", "releases", draft)
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
    if {a["name"] for a in uploaded} != expected:
        raise RuntimeError("Draft does not contain the complete tested release set")
    latest = should_be_latest(version, api.releases())
    result = api.request("PATCH", f"releases/{release_id}", {"draft": False, "make_latest": str(latest).lower()})
    print(f"Published {result['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "stage", "publish"))
    args = parser.parse_args()
    if args.command == "validate":
        properties, _ = metadata()
        print(f"Release metadata valid: v{properties['version']}")
    elif args.command == "stage":
        stage()
    else:
        publish()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError) as error:
        sys.exit(str(error))
