# Development

The shared implementation lives in `common`; `fabric` and `neoforge` provide loader entry points. This is an addon and does not redistribute or alter FTB Quests sources or assets.

## Build

Run Gradle with JDK 25. Minecraft and the addon use a Java 21 toolchain; install both JDKs so Gradle can discover them. Exact build dependency versions are pinned in `gradle.properties`. Loader metadata accepts FTB Quests from the build's minimum version upward, without an upper bound. Check newer FTB Quests releases for API compatibility when updating the build dependencies.

```powershell
./gradlew.bat :common:test :fabric:build :neoforge:build
./gradlew.bat distribution
```

On Linux/macOS use `./gradlew`. Playable JARs are produced in `fabric/build/libs` and `neoforge/build/libs`. The portable ZIP is in `build/distributions`. Do not install the common, sources or Javadoc JARs in Minecraft.

## Test

Unit tests cover literal text operations and dependency graphs, including a chain of 100,000 nodes. The opt-in integration source set runs against real FTB Quests objects inside a game server. It is excluded from normal builds.

Use disposable development directories. Start each server once, review its generated `eula.txt`, and accept the Minecraft EULA there if you agree. For offline test players, bind the test server to `127.0.0.1` before changing authentication settings.

```powershell
./gradlew.bat :fabric:runServer :neoforge:runServer -PintegrationTests
```

Fabric uses `fabric/runs/server`, NeoForge uses `neoforge/run`. Successful runs print `BMPQUESTS_INTEGRATION_PASS` and create `integration-result.txt` inside the run directory. They stop automatically. Tests create a chapter of 100 quests and retain the fixture for inspection. Repeated runs add another chapter. Never point them at a real quest book.

Normal clients: `:fabric:runClient` and `:neoforge:runClient`. The optional NeoForge `-PvisualTests` opens an existing disposable world named `BMP-Tests`; it does not create that world. See [test coverage](docs/testing.md) for the recorded results and remaining manual scenarios.

After integration runs, build release artifacts **without** `-PintegrationTests`. Resource generation tracks the option, so the test mixin is removed automatically. Verify that no `IntegrationMixin` or test classes occur in distributable JARs.

## Integration notes

- The inspected upstream source is [FTB Quests `1.21.1/main`](https://github.com/FTBTeam/FTB-Quests/tree/1.21.1/main), commit `dc94c46a6a9ae806fb5921159513448fcedc8816`.
- `ChapterImageMixin` extends image SNBT and its native network representation. Both endpoints must have BMP Quests installed.
- Image rules use the original `Quest.isVisible`, `TeamData.canStartTasks` and completion state. Progress cache invalidation and team identity invalidate the addon cache. The loading team fails closed.
- The standard image editor and list editor configure rules; no image IDs or translation files need manual editing.
- Bulk requests contain operations, not arbitrary replacement object snapshots. The server checks FTB editor permissions and editing mode, limits the request, builds snapshots, checks the combined dependency graph and creates reward IDs itself.
- One `BulkTransaction` records the whole batch in FTB history. Mutation failures restore the previous state before publishing updates. Only changed translation locales are synchronized. All language entries are retained when copying and undoing reward removal.
- Native context menus are extended by injections. No menu, selection system or screen is overwritten. Compatibility with other addons still needs testing against their particular versions.

## Release preparation

Update `version`, add matching notes under `changelogs/`, then run the relevant unit and integration checks. Publishing is opt-in: ordinary commits and pull requests only build and verify supported targets. Pushing a tag does not trigger this workflow.

Put **one command on its own line in the head commit message** pushed to `main`. It may be the commit title or a separate line in its body. Earlier commits in the same push are ignored. For a squash merge, put the command in the final squash commit message.

| Command | Published builds |
| --- | --- |
| `release all` | Every configured Minecraft version and its supported loaders |
| `release fabric` | Fabric across all configured Minecraft versions |
| `release neoforge` | NeoForge across all configured Minecraft versions |
| `release 1.21.1` | All supported loaders for Minecraft 1.21.1 |
| `release 1.21.1 fabric` | Only Fabric for Minecraft 1.21.1 |
| `release neoforge 1.21.1` | Only NeoForge for Minecraft 1.21.1 |

Commands are case-insensitive. Unknown versions, unsupported combinations and multiple commands fail validation before publishing. A phrase such as `fix: release all` does not request a release. A manual **Run workflow** on `main` accepts the same command in its `command` input; leaving it empty only builds. Feature branches and pull requests cannot publish, even if their messages contain commands.

The selected targets are built and tested before the release job runs. Each Minecraft/loader variant has four GitHub assets: playable JAR, sources JAR, a ZIP containing that variant with guides and changelog history, and its own SHA-256 checksums. The names include the loader and Minecraft version so a Fabric-only fix cannot replace a NeoForge ZIP or its checksums. `gradlew distribution` remains available for a local ZIP containing both loaders.

With the same mod version, only the selected assets in that version's GitHub release are replaced; files for unselected loaders and Minecraft versions are retained. The version tag moves to the latest tested publishing commit. Changing the mod version creates a separate release and preserves previous releases. Selected assets are uploaded to a draft and checked before publication. Older stable versions and prereleases do not replace a newer stable release as latest. Serialized publishing and remote-ref checks reject stale builds. A failed upload leaves a draft that a retry can complete. GitHub uses its built-in `GITHUB_TOKEN` with `contents: write` only in the publishing job; no personal token is needed.

The release scripts use Python 3.11+ from the GitHub runner. To verify their behavior locally without network writes:

```powershell
python -m unittest discover -s scripts/tests -v
python scripts/release.py validate
python scripts/release.py stage
```

Staging copies only the current version's exact file names into `build/release/` and records their checksums and tested commit. Local uncommitted staging is marked dirty and cannot be published by the script. The publish job downloads these exact build outputs; it does not rebuild them with write permissions. The script preserves version-specific notes as the release description.

### Adding another Minecraft version

`release-targets.json` is the source of the CI build matrix. Currently it lists only Minecraft 1.21.1 with Fabric and NeoForge. To add a port, place its independent Gradle project in a repository directory such as `versions/1.20.1`, then add an entry with `minecraft`, `directory`, its supported `loaders`, the Gradle runtime `java`, and the Minecraft Java `toolchain`. CI installs both JDKs and runs Gradle with the selected runtime. Do this after the port builds: adding a number alone does not port the mod. The target's `minecraft_version` must match its entry, and each directory and Minecraft version must be unique.

Each project provides its own `gradlew`, `gradle.properties`, version-specific changelog, player guides, license, common tests, loader builds, and `verifyArtifacts` task supporting `-PreleaseLoaders=fabric,neoforge`. Build versions and dependencies remain in that project's Gradle properties. The central workflow and publishing scripts are reused. `release all` and loader-only commands automatically include newly configured targets; a version selector restricts the matrix to that project. CurseForge upload receipts also include the Minecraft version to keep ports independent.

### CurseForge

After the GitHub release is published, the same serialized job uploads only the selected tested playable JARs to [BMP Quests on CurseForge](https://www.curseforge.com/minecraft/mc-mods/bmp-quests) (project `1769230`). Sources, checksums and ZIPs stay on GitHub. Each CurseForge file receives its own loader and Minecraft version, the matching `changelogs/<version>.md`, and required dependencies. The Fabric file uses the Fabric FTB projects and Fabric API; the NeoForge file uses the Forge/NeoForge FTB projects. Both declare Architectury API. Prereleases are uploaded as beta files.

One-time setup: create an **author API token** in [CurseForge API Tokens](https://authors.curseforge.com/account/api-tokens), then add it as the repository's `CURSEFORGE_TOKEN` Actions secret under **Settings → Secrets and variables → Actions**. A CurseForge Core API key is a different credential and cannot upload files. The workflow uses the [official Upload API](https://support.curseforge.com/support/solutions/articles/9000197321-curseforge-upload-api); no additional publishing service or Python package is needed. A missing token fails the CurseForge step with setup instructions while preserving the GitHub release.

Before uploading, the script rechecks the tested commit, tag, artifact checksums and published GitHub release. Successful uploads are recorded by Minecraft version, loader, SHA-256 and CurseForge file ID in a hidden comment in that mod version's GitHub release description. Keep this comment when editing the release manually. Re-running the workflow preserves the receipts, skips identical accepted files and resumes a partial publication. Changed JARs are uploaded as new CurseForge files; previous files and other versions are preserved. If a network interruption occurs after CurseForge accepts a file but before its receipt is saved, check the CurseForge file list before retrying. Uploaded files still follow CurseForge's moderation process.
