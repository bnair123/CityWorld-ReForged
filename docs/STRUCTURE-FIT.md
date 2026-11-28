# Structures in a CityWorld plan (5.13.0): forecast, reservation, pad

*Moved out of the root `CLAUDE.md` on 2026-11-28 (verbatim) so it is not loaded every session.*
Read this when touching `StructureForecast`, `StructureReservations`, the pad/blend (`padPlanForStructures`,
`PAD_SLOPE`, beard/carve/shave), a planning stall, or reproducing a modded structure in the dev runtime.

**5.13.0 (2026-09-24) was one idea: the planner knows exactly where every structure will be.** `StructureForecast`
makes vanilla's own `createStructures` call from the planner, for any chunk, before it exists, and the
self-test proves it against the stored start on every version (`checkForecast`: compare footprint, floor
and piece count — never maxY, which a chunk reload rebuilds for a terrain-matching piece). Everything
else follows from it. PORTING.md "▶ Resume here — v5.13.0" has the measurements. The rules, each paid for in a
playtest round:

- **Reserve the real footprint, plus as far as the blend will reach.** Reservation = a forecast start's
  box + `reserveMarginChunks(start)`: one chunk for anything the pad leaves alone, otherwise the same
  taper arithmetic the pad uses. One 2,809-chunk sweep: 1160 → 279 chunks reserved, 804 → 0 reserved for
  a structure that never came. A city planned inside the taper gets lifted around (houses in pits, a road
  into a wall) — so the reservation and the blend must be sized by the SAME number.
- **The blend is sized by the RISE and wobbled by noise.** `PAD_SLOPE` 2.5 blocks of run per block of
  climb, never under 16, capped at 120 (the forecast path gathers 8 chunks out; the region fallback keeps
  the pipeline's one-chunk ceiling). A 40-block rise through a 16-block taper is a terraced pyramid.
- **Blend toward the plane the pad can REACH, never the box bottom.** Outside a piece the ground never
  goes below `seaLevel + 1`; a box bottom can be underground chambers (the cursed pyramid: ~35 blocks
  down), and aiming at it sized an 88-block taper and sank the whole ring to the beach line. Under a
  piece the ground may settle to `seaLevel` (a dry beach in CityWorld), which is what closes the trench
  under a structure that sinks itself below the heightmap (Cataclysm's desert village, `start_height -3`).
- **A beard aims at a FLOOR, a shave at a box BOTTOM.** `box.minY() + groundLevelDelta - 1` is a rigid
  jigsaw's single ground plane — every acropolis piece reported y199 while the hill it cuts sits at y60,
  and the shave shaved nothing. A structure that cuts removes everything from its box bottom up, so a
  shave lowers the plan to the LOWEST box bottom below natural ground and feathers outside; only ever
  lowering. Beards: under a piece the LOWEST floor wins (the base, not the roof); outside a footprint
  only GROUND pieces pull (pieces with no lower-floored piece under them in X/Z), weighted by the square
  of inverse-square so the piece beside you owns the edge (no ridge).
- **Every structure is beard, carve, shave or neither.** Carve = a cavern for something BURIED, halo only
  then. Neither = anything that levels itself (`ScatteredFeaturePiece`) or genuinely floats.
- **Two rules keep the forecast a pure function:** `getBaseHeight` answers RAW terrain (vanilla asks it
  while placing; answering the plan would make a real chicken-and-egg), and no reservation answer is
  memoised before the forecast has bound to its level (a timing-dependent plan). Fill the forecast memo
  OUTSIDE any map lock — a jigsaw assembly inside `computeIfAbsent` is the stall below in another coat.
- **Left as Cataclysm's own:** the acropolis pillar bases one block into the water (its absolute start
  height; identical in a vanilla world). Not worth tricking the heightmap answer, which would float
  every village by one.

**The stall (fixed in the same release).** It was never random: the owner's logs put every 60-80 s
`plan.build` on the SAME platmap of the same seed. `UrbanContext.fillOutBuilding` ignored `setLot`'s
refusal of a reserved chunk, so every monotone path through the reservation re-entered it, building a
fresh lot (256 noise columns) per visit — exponential in the reservation's width. 37,669 ms → 1,040 ms.
And `getPlatMap` no longer plans inside `ConcurrentHashMap`'s bin lock (a resize locks every bin, so one
slow plan froze unrelated workers and the server thread). **A stall that looks random is a platmap: ask
which chunk, and whether the same chunk stalls twice, before reasoning.** Two code diagnoses were wrong;
the restored `Support/Timings` watchdog (at `4671737d^`, strip before shipping) named it in one run.

**Reproducing a modded structure locally.** Cataclysm's jar (like every code mod in the owner's
instance) carries SRG-named mixins that cannot apply in a named dev runtime. What works: a datapack-only
stand-in jar with its `structure_set` JSONs + `*_avoid` tags verbatim, its template pools with
`processors` rewritten to `minecraft:empty`, its NBTs, biome tags stripped to vanilla entries, and a
named transcription of any custom placement type registered under the mod's namespace from a debug
class (deleted before committing — `find me -path '*debug*'` in the jar must read 0). Its jigsaw
assembler is NOT vanilla's, so the pieces are an approximation: good for exercising a mechanism, never
for the real shape — that is the owner's playtest. Recipe in PORTING.md "The stall, found and fixed".

**⚠ Cherry-picking the pad to a version branch:** the branches never received master's `fitIndex`
refactor and a hunk-level merge mixed two generations of that code. Splice master's whole fit-index
and `padPlanForStructures` regions between the two comment anchors that exist verbatim on every branch,
then let the compiler name the API drift (26.x: `ChunkPos` is a record, `pack()`/`x()`; 26.3:
`getStructureTemplateManager`, a `Climate.Sampler` on `Structure.generate`, two-arg
`getStartForStructure`; 1.20.1/1.21.1: ten-arg `generate`, `location()`, Forge's server hooks).
