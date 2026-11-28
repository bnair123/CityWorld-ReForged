# Debugging worldgen: probes, traps and worldgen rules

*Moved out of the root `CLAUDE.md` on 2026-11-28 (verbatim) so it is not loaded every session.*
Read this before probing, measuring or chasing a worldgen bug, a hang, or a "there is little of X" report,
and before placing furniture or wall-hung things.

## Probes and measuring

- **Debugging worldgen: measure, don't hypothesize.** `-Dcityworld.diagnostics=true` sweeps every
  tag pool at startup and shouts empties (the classic silent-fallback bug class);
  `-Dcityworld.probe=<chunkX>,<chunkZ>` (via `JAVA_TOOL_OPTIONS` on `runServer`, seed pinned in
  `run/server.properties`, `rm -rf run/world`) force-generates a region, dumps per-layer block
  tallies + furnishing traces, and halts. The probe solved in two runs what four patch-and-playtest
  rounds could not. Verify fixes by re-probe BEFORE deploying.
  **`-Dcityworld.watch=<x>,<y>,<z>`** (world coords, alongside the probe) logs every write to that cell
  with the CityWorld stack behind it — "who draws this block?" answered in one run (the line-of-blocks
  building was `drawInteriorColumns` through a CENTER stairwell; zero WATCH lines after the fix).
- **⚠ Read `PROBE: dimension <id> generator <class>` BEFORE believing any number from it.**
  Until 2026-09-17 the world presets defined **vanilla** realms (CityWorld's were swapped in by `CityWorldRealms`
  from the Customize toggles, which a dedicated server never goes through), so `runServer` gave a vanilla Nether
  and End — and with BoP installed the wrong world still shows crimson forest and withered abyss, so it looks
  convincingly right. Four probe runs measured the wrong dimension on 2026-09-16 and "proved" a fix using blocks
  vanilla's own surface rules had placed; it was committed, pushed and deployed before the generator line was
  read. The presets now ship CityWorld's Nether and End, so a **fresh** `run/world` has them — one made before
  that, or with its realms switched back, does not, and the line is still the first thing to read.
  `CityWorldChunkGenerator` = ours, `NoiseBasedChunkGenerator` = vanilla (PORTING.md has a
  `run/world/datapacks/` recipe for forcing either), and `-Dcityworld.probe=find:biome:<id>` checks a biome is present before you call its feature
  broken. Three more probe traps, each of which cost a run: the **server watchdog kills any sweep over 60s**
  (it runs as one long tick — set `max-tick-time=-1` in the gitignored `run/server.properties`); a roofed
  dimension's `WORLD_SURFACE` is the *ceiling*; and a small sweep sits in ONE biome, so zero there means
  "wrong place", not "broken".
- **A slice-and-join edit of a long doc can silently drop the rest of it.** `s = s[:start] + new + tail`
  with the wrong `tail` truncated PORTING.md from 4,297 lines to 203 for a day (2026-09-17; `git diff --stat`
  showed only the intended hunk because the drop was one big deletion). After any such edit, `wc -l` before and
  after, and `grep` for a heading you know sits near the end.
- **Shape questions need a picture, and there are two that need no client.** `-Dcityworld.probe=survey:end`
  (with `-Dcityworld.probe.dim=minecraft:the_end`) prints the End's *plan* as a chunk map — void, island, road,
  structure — for 10,000 chunks in 5 s without generating one; it found 40-chunk roads across the void and 685
  farms on end stone in its first two runs. `scripts/region_render.py <region dir> x0 x1 y0 y1 z0 z1 out.png`
  renders a generated box as a plan plus a side elevation (kill the server first so the region is flushed;
  1.21.11's End is `run/world/DIM1/region`, 26.x's `dimensions/minecraft/the_end/region`). Block tallies said the
  first End was fine; one screenshot said it was chunk-square slabs. And when "the map says city, the world says
  empty", `-Dcityworld.probe.radius=N` now prints `PLANvWORLD` — per swept chunk, the planned lot class and how
  many blocks stand above the street — which is the disagreement itself, chunk by chunk. The owner's F3 line
  (`context NeighborhoodContext` over `EndNatureLot nature 100%`) diagnosed the levelled-empties bug in one
  screenshot; ask for F3 before hypothesising.
- **Overriding another mod's datapack file takes two things, and each fails differently.**
  (1) `ordering="AFTER"` on an optional dependency in the `neoforge.mods.toml` template — a mod's pack only
  wins a file conflict if it sorts after the mod it overrides; without it the override is a **silent
  no-op** (measured identical block counts either way). (2) A
  `"neoforge:conditions": [{"type": "neoforge:mod_loaded", …}]` guard — the file references that mod's
  objects, and when the mod is absent the reference is unbound, which fails the **whole registry load** and
  **stops the server starting at all** (`Unbound values in registry …`). A dangling *feature* reference is
  fatal; a dangling *tag* entry is merely dropped. The self-test caught this on the two branches that have
  no BoP in `run/mods` — which is exactly why every branch gets tested, not one.

- **A probe that prints the same picture three times has not run.** `scripts/probe.sh` deletes the stale
  `latest.log` first: a leftover `PROBE complete` line made the wait loop kill the new server at once.

- **Kill the previous `runServer` before starting another.** A backgrounded one keeps the dev port —
  **25599**, not 25565 (`run/server.properties`; the self-test moved off 25565 because it collided
  with another mod's dev server, override with `CITYWORLD_SELFTEST_PORT`). The second run then fails
  with `bind(..) failed: Address already in use` → `Failed to initialize server` → a crash report and
  an NPE in `overworld()` on shutdown. That reads like a code fault and isn't one. Kill it **by PID**
  and confirm the process is gone — `pkill -f "gradlew runServer"` returns success without killing
  the JVM, which then holds `run/world/session.lock` and the next run dies with
  `DirectoryLock$LockException: already locked` instead. Never match a `pkill`/`pgrep` pattern that
  appears in your own command line: it kills your own shell.

## Hangs, quiet failures, detectors

- **⚠ A hang at "Preparing spawn area" is a DEADLOCK until a thread dump says otherwise — and no sweep can
  reproduce it.** 5.16.0's museum podium called `ItemFrame.setRotation(int)`, which notifies comparators through
  the frame's level — the real `ServerLevel` — from a generation worker: `hasChunkAt` → `getBlockState` →
  `getChunk` joins the server thread, which is waiting for that chunk. `hasChunkAt` is true only when the chunk
  already holds a FULL ticket while it decorates (spawn area, teleport, login, forced chunk); a chunk that
  decorates as its neighbour's ring answers false, and every probe, sweep and self-test loads chunks one after
  another, so each had already decorated as a neighbour. The self-test's `checkDirectLoad` (FORCED tickets on a
  whole museum district at once, via `updateChunkForced` — `setChunkForced` loads synchronously and hangs the
  harness itself) is the only check that sees this class. **An entity built during decoration holds the real
  level: read any vanilla method you call on it, and take the overload that does not notify.** To dump the
  owner's hung game from WSL: `tasklist.exe` for the big `javaw.exe` PID, then
  `/mnt/c/Users/darre/.gradle/jdks/eclipse_adoptium-21-amd64-windows.2/bin/jstack.exe <pid>`.
- **A quiet worldgen failure looks like scarcity, and the plan hash does not see it.** `ShapeProvider.populateLots`
  catches every exception and logs `populateLots FAILED`; the platmap then generates as nature. A coin-flip pool
  hook called twice (`garageDoorPool()`) handed `MaterialTags.pick` a null tag, and for half a day every
  industrial district was silently dropped while the plan — measured before population — hashed identical. Grep
  any probe or self-test log for `FAILED` before believing "there is little of X"; `MaterialTags.resolve(null)`
  is now an empty pool, and a hook that rolls odds must be called once.

- **⚠ The same caution applies to DIAGNOSTIC code, not just shutdown code.** `Support/Timings` was
  off unless `-Dcityworld.timing=true`, cost nothing when off, and only ever logged — and it was still
  stripped before release, on the owner's call: *"we got but by one before - and although its just
  timeing - no need to trigger people or bots looking for 'hidden' code."* A reviewer greps the shipped
  bytecode, not the flag guarding it. Restore it from history for a debugging session, strip it again
  before shipping, and verify with a detector **proved on a positive first** — the pre-strip jar reads
  1 `Timings` class, a stripped one reads 0.
- **⚠ A detector that has never fired is worth nothing, and a polling one can lie by timing.** The
  stall watchdog's first run produced **zero** dumps with nothing broken: it polled every 2 s while the
  phase it watched took ~800 ms locally, so every phase began and ended inside one poll gap. Only a
  tunable interval (25 ms) proved it worked. Same family as the `strings`-based halt check that read 0
  for everything.
- **⚠ "Measured in game" is not a measurement.** `StructureReservations`' memo javadoc claimed the memo
  fixed the minute-long stall because the symptom seemed to go away once. It had not: proper timing put
  reservation at 2125 ms across 492,863 calls against a single 71,557 ms `populateMap`. A false claim
  in a comment is worse than no comment. Always A/B against a control on the same seed.
- **Never compile in a checkout whose dev server or self-test is running.** `runSelfTest`/`runServer`
  run off that checkout's `build/classes`; a `compileJava` mid-run replaces class files under the JVM
  and the harness dies with `NoClassDefFoundError: …CityWorldSelfTest$1`. That is a race, not a code
  fault. Master and each worktree have separate build dirs, so build *another* checkout meanwhile.

## Worldgen code rules that cost a playtest round

- **Lot connection keys are positional, and a replaced lot's neighbours keep matching it.** `ConnectedLot`'s key
  comes from the chunk position; flood-filled copies take the source's key; `CivilizedContext.validateMap`
  swaps a `trulyIsolated` STRUCTURE with an isolated neighbour for a fresh backfill lot — which is born with
  the same positional key the copies carry, so a warehouse counted a silo battery as its own wing and drew no
  wall. `isConnected` now also requires the same kind of lot; a lot meant to cluster is not `trulyIsolated`.

- **Furniture mods are three families** (see `PALETTES.md`): Macaw's + Refurbished (tags/data map
  generated by `scripts/gen_furniture_tags.py` — re-run it when THOSE mods update) and Fantasy's
  Furniture sets, which share one vocabulary (`src/main/resources/cityworld/furniture_vocabulary/`)
  and are recognised from the block registry at runtime by `Support/FurnitureSets` — a new set needs
  NO regen or rebuild. Multi-block pieces place through `SupportBlocks.setFurniture` only; both plain
  `setBlock` overloads route declared furniture there, so never bypass it for pool furniture.
- **Two placement rules that each cost a playtest round:** (1) anything drawn between `claimStairs`
  and `drawStairs` (columns, `lightInterior`) must consult `isStairClaimed` — the stairs cut later;
  (2) a "wall" for art/sconces/shelves is `isWallBacking` (full cube, sturdy, not glass, not pooled),
  checked behind EVERY cell of a wide piece, and blocks go before entities (a painting is invisible to
  `isEmpty`, so a chandelier chain went through one).
