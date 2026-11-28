# Architecture

*Moved out of the root `CLAUDE.md` on 2026-11-28 (verbatim) so it is not loaded every session.*
Read this before changing the planner/lot/provider cycle, the chunk generator, or the Nether/End realms,
or when you need the original Bukkit source.

## Architecture being ported

The original funnels nearly all Bukkit block coupling through one seam, which is what makes this
possible:

- **`AbstractBlocks`** → **`InitialBlocks`** (generation side, on `ChunkAccess`) — **done**.
- **`SupportBlocks`** → `RealBlocks`/`RelativeBlocks`/`WorldBlocks`/`CornerBlocks` (decoration side,
  on `LevelAccessor`) — **done**. Rests on one abstract method, `getActualBlock(x,y,z)`.
- **`PlatMap`** — a 10×10 grid of chunks; the unit of city planning. Seed-deterministic (important
  for the multithreaded modern chunk pipeline).
- **`PlatLot`** subclasses (`RoadLot`, `BuildingLot`, `ConnectedLot`, `NatureLot`, …) — one chunk's
  worth of "what goes here".
- **`Context/`** (`DataContext` subclasses) — decide which lots populate a PlatMap.
- **Provider pattern** (`Plugins/`) — `ShapeProvider`, `CoverProvider`, `OreProvider`, `TreeProvider`,
  `LootProvider`, `MaterialProvider`, `PasteProvider`, … selected per world style/environment.
- **`Support/Odds`** — deterministic RNG wrapper; prefer it over raw randomness.

**⚠ The brain is one mutually-recursive cycle** (`ShapeProvider ↔ PlatMap ↔ PlatLot ↔ Context ↔
Plugins ↔ Rooms ↔ Clipboard`) — measured: every seed yields the same 316-file / ~40k-line closure.
**There is no "terrain-only" slice.** Port it as a mass transform in waves plus shims. The cycle's
edges are often thin (single method signatures), so they can be stubbed to break it.

Modern worldgen wraps this in a codec-registered `ChunkGenerator` (`worldgen/CityWorldChunkGenerator`,
registered `cityworld:city`), exposed as both a dimension and a world preset. **The port is complete
and the brain is wired in** — it generates real CityWorld terrain, cities, interiors, mines, caves and
decoration across 13 world styles, on six Minecraft versions across two loaders (1.20.1 on
MinecraftForge; 1.21.1, 1.21.11, 26.1, 26.2, 26.3 on NeoForge). It suppresses *most* vanilla
structures/decoration/carvers so CityWorld owns the chunk, with deliberate exceptions: strongholds,
trial chambers and ancient cities are placed (see PORTING.md), and vanilla biome features may decorate
wild land depending on `world.wildDecoration`.

**The other realms (5.9.0) invert that.** The ruined Nether is the overworld's twin (`twin_of`, same plan,
`environment: nether`, decayed harder) on CityWorld terrain. **The End is vanilla's terrain everywhere** —
`vanillaEnd()` (a real `NoiseBasedChunkGenerator` inside ours) fills every chunk, and CityWorld only *reports*
those heights to the planner (`worldgen/EndTerrain`, an exact re-implementation of `NoiseChunk`'s
interpolation, proved column-for-column against `getBaseHeight` by the self-test) so upstream's "buildable =
flat at street level" rule puts cities on island tops. `ShapeProvider_TheEnd` shapes nothing: it terraces the
ground only under built lots plus a 12-block apron, keeps island roads (`keepsIsolatedRoads`), thins by a
regional noise (`settled`; retune with `-Dcityworld.end.settled=<n>` + `survey:end`), and answers no to every
shaft/cave. Its biomes come from the real `TheEndBiomeSource` through `EndTerrain.sampler()` — that is the
only way TerraBlender/BoP End biomes exist, because TerraBlender mixes into that class. **BoP + TerraBlender
are installed only in the `mc26.2` worktree's `run/mods`** — verify any mod interaction there. All 13 world
presets ship both realms by default; the Customize toggles switch either back to vanilla.

**Verify changes with `scripts/selftest.sh`** — a headless dedicated server on a fixed seed that
checks ~88 things and writes a JSON report per Minecraft version. It exists because "looks right in
game" repeatedly disagreed with what the code did; several bugs in this project were a feature
silently doing nothing while looking exactly like working software.

## Reading the original Bukkit source

It is **not in the working tree**. Two ways in:

```bash
# One file, from the last pre-port commit (stable sha; 9827bcf removed the tree):
git show 251078e:src/me/daddychurchill/CityWorld/Support/AbstractBlocks.java

# Or restore the whole reference tree (self-bootstrapping; finds the commit itself):
python3 scripts/gen_material.py     # -> /tmp/cityworld-portgen/bukkit-ref/src/...
```

That script also extracts decompiled Minecraft sources to `/tmp/cityworld-portgen/mcsrc/`. Grep them
to **verify API signatures instead of guessing** — 1.21.11 renamed and reshaped things (see the
"1.21.11 API notes" in `PORTING.md`; e.g. `ResourceLocation` is now `Identifier`).
