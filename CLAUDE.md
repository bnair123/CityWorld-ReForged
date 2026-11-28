# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.
It is kept short on purpose (it loads every session): the detail lives in the topic files indexed below —
open one only when its "read when" applies.

## What this is

A fork of **CityWorld** — originally a Bukkit/Spigot 1.14 plugin that procedurally generates worlds
full of cities, roads, buildings, mines, sewers, farms and nature — ported to a modern **NeoForge**
mod, and since v5.11.0 to **MinecraftForge on 1.20.1** as well. The port is built in place at the
repo root; the original Bukkit project was removed from the working tree and lives on in git history
as the reference implementation.

**Read `PORTING.md` first** when picking up feature work. It is the living plan and the source of truth for
decisions, progress, verified API notes, and what to do next. Start at its "Resume here" section.

| | |
|---|---|
| Minecraft | **this checkout** 1.21.11 — six lines ship: 1.20.1, 1.21.1, 1.21.11, 26.1, 26.2, 26.3 |
| Loader | NeoForge 21.11.42 here; **MinecraftForge 47 on the 1.20.1 line only** |
| Java | 21 here — **17 on 1.20.x, 21 on the 1.21 lines, 25 on 26.1+** |
| Build | Gradle + ModDevGradle (`net.neoforged.moddev`; the 1.20.1 line adds its `legacyforge` addon) |
| Licence | **GPL-3.0-only** (see below — non-negotiable) |
| Branch | work happens on `master` (the `neoforge-port` branch was merged into it and deleted) |

## Topic index — read when…

| File | Read when |
|---|---|
| `docs/RELEASES.md` | you need the current state, what shipped in a version, open items/owner to-dos, or the release recipe as last run. Newest entry first (latest: v7.3.1 + the cyberpunk mod and `hooks-7.4` branch, 2026-11-24). |
| `PORTING.md` | doing feature work: design notes, measurements, per-line API drift ("Resume here" sections). |
| `docs/SHIPPING.md` | cherry-picking to a version branch, releasing, CI, CurseForge/Modrinth, wiki, fleet deploy. |
| `docs/DEBUGGING.md` | probing/measuring worldgen, chasing a hang or a silent failure, placing furniture or wall pieces. |
| `docs/FORGE-1.20.1.md` | touching the `mc1.20.1` Forge line at all. |
| `docs/ARCHITECTURE.md` | changing the planner ↔ lot ↔ provider cycle, the chunk generator, or the Nether/End realms. |
| `docs/STRUCTURE-FIT.md` | structure forecast/reservation/pad (5.13 rules), planning stalls, modded structures in dev. |
| `TRAINS-PLAN.md`, `COBBLEMON-PLAN.md`, `NAVIGATION-PLAN.md`, `BUILDING-DATAPACKS-PLAN.md` | subway/trains/metro/MTR, Cobblemon, navigation, building datapacks. |
| `PALETTES.md`, `LOOT.md`, `BIOMES.md`, `SCHEMATICS.md`, `EXAMPLES.md`, `DATAPACKS.md` | user-facing datapack docs (the wiki is generated from these + `CURSEFORGE-*.md`, `NODES.md`). |
| `CHANGELOG.md`, `CURSEFORGE.md` | release notes; the CurseForge page text (the owner pastes it). |

## Licence — important

Upstream CityWorld is **GPL-3**, so this port is a derivative work and **must stay GPL-3**.
GPL-3 → MIT is not permitted. Don't "helpfully" relabel it. (An early `mod_license=MIT` was an
unverified assumption and has been corrected.)

A useful consequence: because we're GPL-3 and Bukkit's API is GPL-3, we **may vendor** Bukkit's
`SimplexNoiseGenerator`/`SimplexOctaveGenerator` (with attribution) to preserve CityWorld's exact
terrain shape. See `PORTING.md`.

## Build & run

There is **no system Java**; the JDKs live in `./tools/` (git-ignored) — `jdk17`, `jdk21` and `jdk25`.
**This checkout (1.21.11) needs JDK 21**; the worktrees need their own (17 for 1.20.1, 25 for 26.x), and
they reach these same JDKs through a `tools` symlink:

```bash
export JAVA_HOME="$PWD/tools/jdk21"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew compileJava   # fast inner loop while porting
./gradlew build         # -> build/libs/cityworld-<version>.jar
./gradlew runServer     # dev dedicated server
./gradlew runClient     # dev client (needs a display)
```

- `./deploy.sh` builds and copies the jar into the CurseForge test instance
  (`CityWork-ReForged`); it sets `JAVA_HOME` itself.
- The dev server's `run/server.properties` is already set to `level-type=cityworld\:city`, so
  `runServer` generates using our generator. **Delete `run/world` to force regeneration.**
- Gradle can't forward piped stdin to the server console — to verify in-world behaviour, register a
  temporary `ServerStartedEvent` listener that logs what you need, rather than piping commands.
- **Version branches live in permanent worktrees** at `../CityWorld-ReForged-worktrees/<branch>`
  (`mc1.20.1`, `mc1.21.1`, `mc26.1`, `mc26.2`, `mc26.3`; on this machine only `mc1.21.1` exists as of
  2026-11-28). **Never switch branches in the root checkout** — other Claude sessions (e.g. the website one)
  work in it. Build each worktree with its own explicit `JAVA_HOME`. `WEBSITE.md` is **git-ignored and
  local-only** (the note-passing file with the website session): read/write it freely, never commit it.
  Docs live on master. Full detail: `docs/SHIPPING.md`.
- Versions/metadata live in `gradle.properties` and expand into
  `src/main/templates/META-INF/neoforge.mods.toml` — **edit the template and gradle.properties, never a
  generated `mods.toml`** (1.20.1: `META-INF/mods.toml`, see `docs/FORGE-1.20.1.md`).
- Mod id `cityworld`; `@Mod` entrypoint `me.daddychurchill.CityWorld.CityWorldMod`.
- **Verify changes with `scripts/selftest.sh`** (headless server, fixed seed, ~88 checks, JSON report per
  version; `scripts/selftest-one.sh` for one checkout). "Looks right in game" repeatedly disagreed with the code.

## The ship loop (short form — `docs/SHIPPING.md` has the traps)

Commit on master → `git cherry-pick <EXPLICIT SHA>` in the worktree (**never `$(git rev-parse HEAD)` in a
chain** — it resolves to the worktree's own HEAD) → **one commit per cherry-pick invocation** → **verify
`git log` shows your sha before building** → PALETTES/PORTING/CHANGELOG conflicts resolve `--ours` →
`compat/Material.java` conflicts: take the branch's copy and re-run `scripts/gen_material.py` there → build →
deploy → `DEPLOYED-$(git log --format=%h -1)` stamp → selftest. Grep for `<<<<` after any resolved pick.
**Push the version branch first, then master** (CI lives only on master and tests each branch's head).
**Every release updates the wiki** (`scripts/gen-wiki.sh`, then `--push`).

## Hard rules (each was paid for; the stories are in the topic files)

- **Nothing in the shipped jar may be able to stop a server — not even behind a developer flag**
  (`halt`/`System.exit`; CurseForge rejected 5.7.0 and 5.8.0). Diagnostic code (`Support/Timings`) is stripped
  before release too. Verify with `scripts/scan_jar.sh`, **proved on a positive first** — never trust a zero from
  a detector that has never fired. Headless runs are ended by the script that starts them.
- **Never compile in a checkout whose dev server, probe or self-test is running** (it runs off that checkout's
  `build/classes`; `NoClassDefFoundError` mid-run is that race). Build another checkout meanwhile.
- **Kill the previous `runServer` by PID** before starting another (dev port 25599). Never `pkill` a pattern
  that appears in your own command line. One JVM at a time on a memory-tight machine.
- **Measure, don't hypothesize**: probe (`-Dcityworld.probe=…`, `scripts/probe.sh`), and read the
  `PROBE: dimension <id> generator <class>` line before believing any number. A hang at "Preparing spawn area"
  is a deadlock until a thread dump says otherwise. Grep logs for `FAILED` before believing scarcity.
- **Never a bare `join()` in worldgen** — every build-once wait goes through `worldgen/Waiting.join`. An entity
  built during decoration holds the real level: use overloads that do not notify neighbours.
- **Same seed, same city**: a roll that only happens when a cell is free goes on a fork (`Odds.fork()`,
  `PlatLot.phaseDice`) or is rolled unconditionally. Planning never looks at a neighbour platmap; drawing may.
- **Slice-and-join edits of long docs**: `wc -l` before and after, and grep a heading near the end (one
  truncated PORTING.md from 4,297 lines to 203).
- **Add-on jars never go on CityWorld's CurseForge project** (they have their own). Modrinth rejected the
  projects (rule 6.1): do not suggest resubmitting. New gameplay numbers are settings with today's value as the
  default (owner's standing to-do); settings codecs are capped at 16 fields per group.
- **Overriding another mod's datapack file** needs `ordering="AFTER"` *and* a `mod_loaded` condition
  (`docs/DEBUGGING.md`).

## Reading the original Bukkit source

Not in the working tree: `git show 251078e:src/me/daddychurchill/CityWorld/<path>`, or
`python3 scripts/gen_material.py` (restores it, plus decompiled Minecraft sources to grep for real API
signatures). Details: `docs/ARCHITECTURE.md`.

## Conventions specific to this port

- **Package tree is preserved**: ported code keeps the original `me.daddychurchill.CityWorld.*`
  packages (minimizes churn across ~300 files, keeps attribution). The Gradle group id is lowercase
  `me.daddychurchill.cityworld` — that's fine, it needn't match.
- **Port by mechanical transform, not retyping.** Large files (e.g. the 768-line `AbstractBlocks`)
  were ported by scripting the import/type swaps and then fixing residuals against the compiler.
  Preserve the original logic and comments; keep diffs reviewable.
- **`compat/` holds the Bukkit shims** — the trick that makes this tractable:
  - `Material` — interned wrapper over `Block`/`BlockState` **and** `Item`. Its constant block is
    **generated by `scripts/gen_material.py` — do not hand-edit**; change the generator and re-run.
  - `BlockFace` → vanilla `Direction`; `WoodSpecies` → Bukkit `TreeSpecies`.
  - `Block` → Bukkit's live positioned block (`LevelAccessor` + `BlockPos`).
  - Mappings worth remembering: Bukkit `BlockData` ≡ modern `BlockState`; Bukkit "apply physics" ≡
    vanilla update flags (`UPDATE_ALL` vs `UPDATE_CLIENTS`).
- **`Support/Odds`** is the deterministic RNG wrapper; prefer it over raw randomness. The brain
  (`ShapeProvider ↔ PlatMap ↔ PlatLot ↔ Context ↔ Plugins ↔ Rooms ↔ Clipboard`) is one cycle — see
  `docs/ARCHITECTURE.md`.

## Reference port

`../MobHealth-Forge` is a completed NeoForge port of another Bukkit plugin by the same author. Its
Gradle setup, `mods.toml` template, config (`ModConfigSpec`), Brigadier commands, and permission
handling are the patterns this project copies. (It's a simple event-driven mod, so it has no
worldgen or registration examples.)
