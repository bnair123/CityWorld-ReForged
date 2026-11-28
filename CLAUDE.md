# CLAUDE.md

Guidance for Claude Code in this repository. Kept short on purpose (it loads every session): open a topic
file only when its "read when" applies. **The same file sits on `master` and on `mc1.21.1`** — change both.

## What this is

**bnair's fork** (`github.com/bnair123/CityWorld-ReForged`) of CityWorld ReForged: the Bukkit CityWorld
worldgen plugin (cities, roads, buildings, mines, sewers, farms, nature) ported to NeoForge/Forge by its
upstream owner (Sablednah). This fork is for bnair's own play on **Minecraft 1.21.1 NeoForge**; it is not
published to CurseForge/Modrinth. Current work: transit (metro, MTR train services, cable cars) and the
companion navigation mod `../CityWorld-Navigator`.

| Where | What |
|---|---|
| `../CityWorld-ReForged-worktrees/mc1.21.1` (branch `mc1.21.1`) | **all code work**: build, run, self-test, commit here |
| `CityWorld-ReForged` root (branch `master`, 1.21.11) | **docs only** (`PORTING.md`, plans, `docs/`); not buildable here (no `tools/`, no `run/`) |
| Prism instance `C:\Users\bnair\AppData\Roaming\PrismLauncher\instances\Arcadia\minecraft` | where bnair plays (world `saves/City`); jars go in its `mods/` |

From the `mc1.21.1` worktree, master's checkout (and its `docs/`) is `../../CityWorld-ReForged`.
Each code commit on `mc1.21.1` gets a "Docs: … (7.4, mc1.21.1 <sha>)" commit on master recording it.

**Licence: GPL-3.0-only, non-negotiable** — a derivative of GPL-3 CityWorld; never relabel it (e.g. MIT).
Vendoring Bukkit's GPL-3 noise classes with attribution is allowed.

## Topic index — read when…

All paths are on master.

| File | Read when |
|---|---|
| `NAVIGATION-PLAN.md` | **this fork's current state and next steps**: the Navigator mod and the 7.4 transit work (metro, MTR services and depots, stations, cable car, ferries parked, review rounds). Docs commits for `mc1.21.1` land here. |
| `PORTING.md` | upstream design notes, measurements and API notes ("Resume here" sections, up to 7.3). |
| `TRAINS-PLAN.md` | the upstream subway trains (Railcraft, Slash Rails, Choo Choo, electric line). |
| `docs/DEBUGGING.md` | probing/measuring worldgen, chasing a hang or a silent failure, placing furniture or wall pieces. |
| `docs/ARCHITECTURE.md` | changing the planner ↔ lot ↔ provider cycle, the chunk generator, the Nether/End; reading the Bukkit source. |
| `docs/STRUCTURE-FIT.md` | structure forecast/reservation/pad, planning stalls, modded structures in dev. |
| `docs/SHIPPING.md` | cherry-picking between branches, the upstream six-line release/publish process, fleet deploy. |
| `docs/RELEASES.md` | what shipped upstream up to v7.3.1 and its open items (history; not this fork's current state). |
| `docs/FORGE-1.20.1.md` | only if touching the upstream 1.20.1 Forge line. |
| `COBBLEMON-PLAN.md`, `BUILDING-DATAPACKS-PLAN.md` | Cobblemon support; building kinds as data. |
| `PALETTES.md`, `LOOT.md`, `BIOMES.md`, `SCHEMATICS.md`, `EXAMPLES.md`, `DATAPACKS.md`, `CURSEFORGE-CONFIGURATION.md` | user-facing datapack/settings docs (a new setting gets its row in `CURSEFORGE-CONFIGURATION.md`). |
| `CHANGELOG.md` | release notes. |

## Build & run (in the `mc1.21.1` worktree)

No system Java and no working `tools/` (it is a dangling symlink file); use the installed JDK 21:

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew compileJava   # fast inner loop
./gradlew build         # -> build/libs/cityworld-<version>.jar (copy it into Arcadia's mods/)
./gradlew runServer     # dev dedicated server (run/server.properties: level-type=cityworld:city)
./gradlew runClient     # dev client
```

- **Delete `run/world` to force regeneration.** Gradle can't pipe stdin to the server: log what you need from
  a temporary `ServerStartedEvent` listener instead.
- `./deploy.sh` defaults to the upstream owner's instance; don't use it as-is.
- **Verify with `scripts/selftest.sh`** (headless server, fixed seed, ~88 checks, JSON report) or
  `scripts/selftest-one.sh`. "Looks right in game" repeatedly disagreed with the code.
- Versions live in `gradle.properties` → `src/main/templates/META-INF/neoforge.mods.toml`: edit those, never
  a generated `mods.toml`. Mod id `cityworld`; entrypoint `me.daddychurchill.CityWorld.CityWorldMod`.
- **Never switch branches in the root checkout** (other sessions work in it). `WEBSITE.md` is git-ignored and
  local-only: never commit it. Moving a commit between branches: `git cherry-pick <EXPLICIT SHA>`, one commit
  per invocation, check `git log` shows it, grep for `<<<<` after resolving (`docs/SHIPPING.md`).

## Hard rules (each was paid for; the stories are in the topic files)

- **Nothing in the jar may be able to stop a server — not even behind a developer flag** (`halt`/`System.exit`;
  upstream had two CurseForge releases rejected for it). Strip diagnostic code before a real release. Check
  with `scripts/scan_jar.sh`, **proved on a positive first** — never trust a zero from an untested detector.
- **Never compile in a checkout whose dev server, probe or self-test is running** (it runs off that checkout's
  `build/classes`; a mid-run `NoClassDefFoundError` is that race).
- **Kill the previous `runServer` by PID** before starting another (dev port 25599). Never `pkill` a pattern
  that appears in your own command line. One JVM at a time.
- **Measure, don't hypothesize**: probe (`-Dcityworld.probe=…`, `scripts/probe.sh`) and read the
  `PROBE: dimension <id> generator <class>` line before believing any number. A hang at "Preparing spawn area"
  is a deadlock until a thread dump says otherwise. Grep logs for `FAILED` before believing scarcity.
- **Never a bare `join()` in worldgen** — build-once waits go through `worldgen/Waiting.join`. An entity built
  during decoration holds the real level: use overloads that do not notify neighbours.
- **Same seed, same city**: a roll that only happens when a cell is free goes on a fork (`Odds.fork()`,
  `PlatLot.phaseDice`) or is rolled unconditionally. Planning never looks at a neighbour platmap; drawing may.
- **New gameplay numbers are settings** with today's value as the default; a settings codec group holds at
  most 16 fields (start a new group rather than squeeze).
- **Overriding another mod's datapack file** needs `ordering="AFTER"` *and* a `mod_loaded` condition.
- **Slice-and-join edits of long docs**: `wc -l` before and after, and grep a heading near the end.

## Code conventions

- Ported code keeps the original `me.daddychurchill.CityWorld.*` packages; Gradle group is lowercase.
- `compat/` holds the Bukkit shims: `Material` (wrapper over `Block`/`BlockState`/`Item`; its constants are
  **generated by `scripts/gen_material.py` — never hand-edit**), `BlockFace` → `Direction`, `Block` → a
  positioned block on a `LevelAccessor`. Bukkit `BlockData` ≡ `BlockState`.
- `Support/Odds` is the deterministic RNG; prefer it over raw randomness. The brain
  (`ShapeProvider ↔ PlatMap ↔ PlatLot ↔ Context ↔ Plugins ↔ Rooms ↔ Clipboard`) is one cycle.
- Original Bukkit source: `git show 251078e:src/me/daddychurchill/CityWorld/<path>`.
