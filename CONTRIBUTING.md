# Development

The shared implementation lives in `common`; `fabric` and `neoforge` provide loader entry points. This is an addon and does not redistribute or alter FTB Quests sources or assets.

## Build

Run Gradle with JDK 25. Minecraft and the addon use a Java 21 toolchain; install both JDKs so Gradle can discover them. Exact Minecraft, loader and FTB dependency versions are pinned in `gradle.properties`. FTB Quests is deliberately restricted to the inspected API version in the loader metadata.

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

Update `version`, add matching notes under `changelogs/`, run unit and integration checks, then build both loaders and `distribution`. The ZIP contains playable mods, sources, guides and version history. It excludes dependencies, test fixtures, caches and development instances.

`.github/workflows/release.yml` builds pull requests without publishing. A successful push to `main`, a matching `v<version>` tag, or a manual run on `main` publishes through GitHub Releases. The workflow uses its built-in `GITHUB_TOKEN` with `contents: write` only in the publishing job; no personal token is needed. The tag must match `version` in `gradle.properties`, and the corresponding release notes are required.

With the same version, only that release's assets and tag are replaced with the tested commit. Changing the version creates a separate release and preserves previous versions. All six assets (Fabric/NeoForge playable and sources JARs, distribution ZIP, SHA-256 checksums) are uploaded to a draft and checked before publication. Older stable versions and prereleases do not replace a newer stable release as latest. Serialized publishing and remote-ref checks prevent stale builds from replacing newer work. A failed upload remains a draft for the next successful run to complete.

The release scripts use Python 3.11+ from the GitHub runner. To verify their behavior locally without network writes:

```powershell
python -m unittest discover -s scripts/tests -v
python scripts/release.py validate
python scripts/release.py stage
```

Staging copies only the current version's exact file names into `build/release/` and records their checksums and tested commit. Local uncommitted staging is marked dirty and cannot be published by the script. The publish job downloads these exact build outputs; it does not rebuild them with write permissions. The script preserves version-specific notes as the release description.
