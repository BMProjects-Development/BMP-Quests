"""Resolve explicit release commands against the supported Minecraft build targets."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
LOADERS = ("fabric", "neoforge")


def targets(root=ROOT):
    rows = json.loads((root / "release-targets.json").read_text(encoding="utf-8"))["targets"]
    seen_versions, seen_directories = set(), set()
    if not rows:
        raise ValueError("At least one build target is required")
    for row in rows:
        version, directory, loaders = (row[key] for key in ("minecraft", "directory", "loaders"))
        if any(not re.fullmatch(r"\d+", row.get(key, "")) for key in ("java", "toolchain")):
            raise ValueError(f"Set the Gradle runtime java and Minecraft toolchain for {version}")
        path = (root / directory).resolve()
        if not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", version):
            raise ValueError(f"Invalid Minecraft target: {version}")
        if not path.is_relative_to(root.resolve()) or not (path / "gradlew").is_file():
            raise ValueError(f"Build target must be a Gradle project inside the repository: {directory}")
        if version in seen_versions or path in seen_directories:
            raise ValueError("Build targets must have unique Minecraft versions and directories")
        if not loaders or len(set(loaders)) != len(loaders) or any(loader not in LOADERS for loader in loaders):
            raise ValueError(f"Invalid loaders for {version}: {loaders}")
        properties = (path / "gradle.properties").read_text(encoding="utf-8-sig")
        if not re.search(rf"^minecraft_version\s*=\s*{re.escape(version)}\s*$", properties, re.MULTILINE):
            raise ValueError(f"Minecraft target {version} does not match {directory}/gradle.properties")
        seen_versions.add(version)
        seen_directories.add(path)
    return rows


def select(command, rows):
    """One optional Minecraft version and one optional loader; no implicit release."""
    tokens = command.lower().split()
    if not tokens or tokens[0] != "release" or not 2 <= len(tokens) <= 3:
        raise ValueError("Use release all, release <loader>, release <minecraft>, or release <minecraft> <loader>")
    selectors = tokens[1:]
    if "all" in selectors:
        if selectors != ["all"]:
            raise ValueError("release all cannot be combined with other selectors")
        return [dict(row, loaders=list(row["loaders"])) for row in rows]
    versions = {row["minecraft"] for row in rows}
    chosen_versions = [token for token in selectors if token in versions]
    chosen_loaders = [token for token in selectors if token in LOADERS]
    if len(chosen_versions) > 1 or len(chosen_loaders) > 1 or len(chosen_versions) + len(chosen_loaders) != len(selectors):
        raise ValueError(f"Unknown or conflicting release selectors: {' '.join(selectors)}")
    result = []
    for row in rows:
        if chosen_versions and row["minecraft"] != chosen_versions[0]:
            continue
        loaders = [loader for loader in row["loaders"] if not chosen_loaders or loader == chosen_loaders[0]]
        if loaders:
            result.append(dict(row, loaders=loaders))
    if not result:
        raise ValueError(f"No supported builds match: {command}")
    return result


def plan(event_name, event, ref, rows):
    command = ""
    if ref == "refs/heads/main":
        if event_name == "push":
            message = (event.get("head_commit") or {}).get("message", "")
            commands = [line.strip() for line in message.splitlines() if re.match(r"^release(?:\s|$)", line.strip(), re.IGNORECASE)]
            if len(commands) > 1:
                raise ValueError("Use one standalone release command per commit")
            command = commands[0] if commands else ""
        elif event_name == "workflow_dispatch":
            command = event.get("inputs", {}).get("command", "").strip()
    selected = select(command, rows) if command else rows
    return {"publish": bool(command), "matrix": {"include": selected}}


def build(root=ROOT):
    directory = os.environ.get("BUILD_DIRECTORY", ".")
    minecraft = os.environ["BUILD_MINECRAFT"]
    loaders = json.loads(os.environ["BUILD_LOADERS"])
    available = next((row for row in targets(root) if row["minecraft"] == minecraft and row["directory"] == directory), None)
    if not available or not loaders or any(loader not in available["loaders"] for loader in loaders):
        raise ValueError("Build selection does not match release-targets.json")
    path = (root / directory).resolve()
    tasks = [":common:test"] + [f":{loader}:build" for loader in loaders] + ["verifyArtifacts"]
    subprocess.run(["bash", "./gradlew", *tasks, f"-PreleaseLoaders={','.join(loaders)}", "--console=plain"], cwd=path, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("plan", "build"))
    args = parser.parse_args()
    if args.operation == "build":
        build()
        return
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    result = plan(os.environ["GITHUB_EVENT_NAME"], event, os.environ.get("GITHUB_REF", ""), targets())
    output = f"publish={str(result['publish']).lower()}\nmatrix={json.dumps(result['matrix'], separators=(',', ':'))}\n"
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as destination:
        destination.write(output)
    print(output, end="")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
