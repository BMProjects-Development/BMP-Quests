# Verification record

Date: 2026-10-27. The test instances are disposable local development instances, not a production modpack.

| Check | Result |
| --- | --- |
| Common, Fabric and NeoForge Java compilation | Passed |
| Both loader builds | Passed |
| JUnit text and graph tests | 6 passed |
| Offline release lifecycle tests (staging, replacement scope, stale refs, upload failure, latest selection) | 15 passed |
| Fabric dedicated server integration suite | 966 assertions passed |
| NeoForge dedicated server integration suite | 966 assertions passed |
| Fabric client and integrated world startup | Passed |
| Fabric native context menu and 100-quest Bulk Edit | Passed |
| Fabric Apply and one-action native Undo for 100 quests | Passed |
| NeoForge client and integrated world startup | Passed |
| NeoForge Russian image properties, conditions list and native quest picker | Passed |
| NeoForge saving image conditions through the native editor | Passed |
| NeoForge title draft: empty Apply leaves the editor open, entering a title renames 100 quests, native Undo restores their titles | Passed |

The server suite checks image legacy defaults, hidden quests, availability, completion, AND/OR, missing quests, loading progress, team identity, live cache invalidation, NBT/network round trips and disk reload. Bulk checks cover batches of 2/10/50/100 quests, locale preservation, multiline formatting, single history entries and redo, reward creation/removal/copy/auto-claim, reward IDs and translations, dependency add/remove/clear and cycles, stale targets, unsupported properties, rejection of a forged request from a non-editor and image batch undo.

The native UI is shared between loaders. See the source of the opt-in integration suite under `common/src/integration`; the test harness is excluded from distributable JARs.

The regression suite reproduced acceptance of an empty title replacement in an early development build. It now rejects empty, whitespace-only and missing title inputs, checks valid replacement and Undo of 100 titles without altering the other locale, and rejects a Find and Replace result that would erase only the last selected title without changing earlier quests.

## Manual release matrix

Still required before claiming production modpack compatibility: two real connected players in a shared FTB team, leaving/joining/replacing that team while the book is open, high-latency reconnects, Quest Enhance and other editor addons, custom third-party reward types, long-session profiling in a large existing book, and the final JARs in a separate launcher instance. The server tests simulate separate team progress objects and invoke the real permission gate; they do not replace that multiplayer matrix.
