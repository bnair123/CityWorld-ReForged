# Navigation, transit and city-building: the plan

*Started 2026-11-26. This is the plan for three ideas: a navigation companion mod, metro/railway lines, and a survival
city-building mode. Navigation is being built first. Target: **Minecraft 1.21.1 NeoForge** (the `mc1.21.1` line); the
other five lines follow only if it proves out. Test world: the Prism instance `Arcadia`, world `City` (vanilla land,
"cities apart": `cities.vanillaTerrain` true, `spacing` 4096, `districts` 4, rivers on, `made_with` 73, style
`modern`). That instance has Create 6.0.10, MTR 4.1.0-beta.2, JourneyMap 6.0.10, Create Railways Navigator, and
CityWorld 7.3.1.*

## 1. Navigation companion mod (`CityWorld-Navigator`, mod id `cityworld_nav`) — IN PROGRESS

### What CityWorld already knows (no new world generation needed)

| Need | Where it lives | Notes |
|---|---|---|
| Which chunks are road | `PlatMap` (10×10 chunks), `PlatLot.style` `ROAD` / `ROUNDABOUT` | A pure function of the seed; planned off-thread and cached (`CityWorldGenerator.getPlatMap`, `peekPlatMap`, `planPlatMapAsync`). ~180 ms per cold platmap. |
| Which road chunks connect | `PlatLot.isConnected(neighbour)` inside a platmap; across a platmap edge roads sit on fixed rows (`RoadLot.PlatMapRoadInset`) | Same rule `SurroundingRoads` uses to draw the road. |
| Street names | `RoadLot.getStreetName(generator, eastWest)` | Pure function of the chunk; the same text as the street signs. |
| Speed limits | `roads.speedLimitUrban` (50) / `roads.speedLimitRural` (30): rural where `platmap.getNaturePercent() >= 0.40`; `cities.highways.speedLimit` on highways | Mirrors `RoadLot.speedSign` / `highwaySpeedLimit`. |
| Cities (vanilla land) | `CityWorldGenerator.citySites` → `CitySites.Site(centreX, centreZ, radius, level, …)`; `siteAt`, `nearest` | `site.level` is the city's own street height. |
| City and district names | `Support/PlaceNames.at` / `ofCity` | Same as the town boards. |
| Highways (vanilla land) | `CityWorldGenerator.highways` → `Highways.linksTouching(x0,z0,x1,z1)` → `Link(a, b, x[], z[], y[], kind[])` | Polyline every 2 blocks, deck height, GROUND/BRIDGE/TUNNEL. Each end is a city *gate* at the end of an exit street (`exitsAt`). Highways meet only at cities. |
| POIs | `CityWorldAPI.findables` / `find` (33 places, 14 shop trades, districts, schematics by title) | Blocking: run off the server thread. |

### Design

- **Companion, not a fork of CityWorld.** It compiles against CityWorld 7.3.1's public classes; every touch of
  CityWorld's internals is in ONE adapter class (`CityWorldBridge`), so a CityWorld update means fixing one file.
  Later, the adapter's surface should become a real public API in CityWorld (`api/CityWorldRoads`), the way the
  cyberpunk mod's needs became public hooks.
- **The graph is the plan, not the world.** Roads a player digs up or builds are not seen. That is the price of
  routing to places nobody has generated yet.
- **Two levels of graph.**
  1. *Street graph*: one node per road chunk (centre at street level), edges to connected neighbours, cost =
     16 blocks ÷ the chunk's speed limit. Built lazily: A* asks for a chunk's neighbours and the bridge plans the
     platmap if needed (on a worker thread). Works on both CityWorld's own land (one endless city) and vanilla land.
  2. *Highway graph* (vanilla land only): nodes = cities, edges = highway links, cost = link length ÷ highway speed.
     Each link is attached to the street graph at the road chunk at its gate.
- **Route** = Dijkstra over cities (when start and goal are in different cities), then street A* legs from the start
  to the first gate and from the last gate to the goal, joined by the highway polylines. Same city → street A* only.
- **Directions**: the path is folded into manoeuvres ("Head east on North 5th Street 240 m", "Turn left onto Oak
  Avenue", "Take the highway to Ashford 3.2 km", "Arrive"). Turns come from the change of heading; a street name
  change without a turn is "Continue onto …".
- **Guidance in game** (server side, works in single player and on a server):
  - action bar: next manoeuvre and distance, refreshed every second;
  - a particle trail along the next ~64 blocks of the route, sent to that player only;
  - arrival within 24 blocks; off route (more than 40 blocks from the path) → recomputed.
  - JourneyMap polyline of the whole route (JourneyMap is installed): phase 2.
- **Commands** (`/nav`):
  - `/nav to <x> <z>`; `/nav to city <name>`; `/nav to street <name>` (nearest chunk of that street);
    `/nav to poi <id>` (a `findable` id: `hospital`, `shop:bakery`, …); `/nav stop`;
  - `/nav where` — city, district and street here; `/nav cities` — known cities with distance;
    `/nav pois [radius]` — POIs near here, by kind; `/nav streets` — streets of this city.
- **Costs/limits**: the search runs on its own thread with a node cap and a time budget; planning a whole city
  cold is a few seconds the first time, then cached by CityWorld.

### Phases

1. ✅ Plan (this file).
2. ✅ Bridge + street graph + A* + `/nav where`, `/nav to <x z>`, action-bar directions, particle trail.
3. ✅ Highways + city graph + `/nav to city`, `/nav cities`.
4. ✅ POIs and streets: `/nav pois`, `/nav to poi`, `/nav to street`, `/nav streets`.
   Roundabouts are one instruction ("At the roundabout, turn left onto …"): a road chunk next to a
   `RoundaboutCenterLot` is part of the ring.
5. ✅ JourneyMap (built 2026-11-26, the server plugin seen loading on the dev server; not yet seen in a client):
   the route as a blue line on the map and minimap (server overlay `nav_route`, one quad per simplified segment),
   a green pin at the destination, `/nav pois` and `/nav cities` pin their results (group "CityWorld Navigator",
   `/nav clearmap` removes them), and right-click menus on the fullscreen map ("Navigate here", "Places of
   interest here", "Cities around here", "Stop navigating") and on any waypoint ("Navigate to this waypoint").
   The menus just run `/nav` commands, so there is no networking of our own. Xaero's: not done (disabled in Arcadia).
   Trail fix the same day: each trail particle snaps to the real surface within a few blocks of the plan's street
   height (it went through canal bridge decks, which the plan does not know about).
6. Later: config (speeds, units, trail on/off); a navigator screen of its own (a keybind opening lists of cities,
   streets and places to click), which needs packets because the lists live on the server.

**Status 2026-11-26:** v0.1.0 built in `../CityWorld-Navigator` and copied into Arcadia's `mods/`. Measured headless
on the City world's own `level.dat` (`scripts/probe.sh`, plan only, no chunks generated): 6 cities within one cell of
spawn; Deerford has 15 named streets (5.5 s cold to read the whole city); in-city route 5 ms; Deerford → Thornbury
(4.9 km highway) 5.7 km in 2 s; Deerford → Higham → Downtown 8.5 km in 6 s; 24 POIs in Deerford in 0.3 s. **Not yet
seen in game:** the commands, action bar, particle trail and rerouting (compiled, not exercised by the probe).
Noticed in CityWorld itself: street names "11st", "12nd" (the odonym ordinal suffix is wrong for 11–13).
7. Later: public `CityWorldRoads` API in CityWorld; building entrances as POI addresses (the road chunk a lot
   fronts: `PlatMap.frontsRoad`).
8. Later (asked 2026-11-26): **roads players add to the network**, e.g. a road they built between two cities.
   Ideas: trace it (a tool that records points as you drive or walk it), or mark its two ends and let the mod
   follow the road surface blocks between them (flood along blocks matching the road's surface, then thin to a
   centreline). Stored per world as polylines, joined to the street graph at their nearest road chunks and
   to each other where they meet; drawn on the map like a highway. This is the first piece of the graph that
   is the world, not the plan.

### Requests for CityWorld itself (when we start editing it)

- ✅ **Smoother highways** (built 2026-11-26 on `mc1.21.1`, world stamp 74 = 7.4): the deck is a taut string within
  `cities.highways.smoothing` (6) blocks of the ground. Tuned offline: the ground under 40 highways of the City
  world dumped once, `deck_sim.py` replaying the old rule exactly (8.1 reversals/km, 1391 short bumps, 58 m/km
  climb, 40.2% bridge, 4.3% tunnel), candidates scored on the same data, the winner ported and re-measured in
  game identical to the simulation (2.2/km, 167, 29 m/km, 40.7%, 3.1%). A short tunnel now stays a tunnel where
  the cut would be more than 18 deep. Older worlds keep the old profile unless the setting is given.
- ✅ **Highways crossing each other** (same day): two neighbouring cities (Deerford and Higham, 1.4 km apart) both
  linked to the same far city gave two highways leaving side by side and crossing four times. A 7.4 world leaves
  out the longest side of any triangle of highways whose other two sides are at most 30% longer (`Highways
  .redundant`): 40 → 35 links around spawn, crossings 7 → 0, every navigator test route still found.
- Not yet looked at: crossings between highways of unrelated city pairs (none in the measured area), and
  interchanges where two do meet.
- Street-name ordinals: "11st", "12nd", "13rd" should be "11th", "12th", "13th".

- **City streets over water (owner's test of the City world, 2026-11-27): FIXED for 7.4 worlds.** Upstream's
  `RoadLot` drew each street chunk over water on its own: a crossing over water became the east-west street's raised
  bridge alone (the north-south street met a 3-block wall each side: read from the owner's save at -8840,6600, deck
  69 → 64 east-west, 64 / 67 / 66 / 69 north-south), a turn or T over water drew no bridge at all (missing
  segments), and a bridge could be at full height where a turn met it (the navigator's dead-end right turn). Now
  (`RoadLot.bridgedOverWater`, `cleanBridges` = stamp 74+): a raised bridge only where the street runs straight across
  and its whole run over water is straight, ending on land both ways; any other street over water is drawn flat at
  street level (measured: the crossing over water at Wolfminster, -1640,9880, is an ordinary crossroads; straight
  runs keep their ramps). Older worlds keep upstream's drawing so their chunks still meet; for them the navigator
  uses `RoadLot.drivableSides` (a crossing over water: its east-west street only; a turn over water: none), and
  skips a side the neighbour cannot be entered from. Navigator probe `PROBE=bridges` lists street chunks over water.

### Known limits

- Building entrances are not recorded; a POI's address is the road chunk beside its lot.
- Highways meet only at cities (no interchanges), so a route between two cities goes city to city.
- On CityWorld's own land there are no highways; long trips are long street A* searches (node cap applies).

## 2. Metro and railway lines — intercity railways and the metro BUILT (mc1.21.1)

### Railways between cities (built 2026-11-26, `worldgen/Railways`, settings `cities.railways`, world stamp 74)

The owner's choices: lines on the same city pairs as highways, on their own routes, stations at the city edge for
now (in the city centres once the metro exists). Empty trackbed for Create or MTR.

**Track geometry, from both mods' source** (research the same day; local copies in the session scratchpad):
- *Create 6.0.x* (`TrackShape`, `TrackPlacement`): straights only in 8 directions (axes + exact diagonals); a curve
  turns 45° or 90° only (135° refused, "turn_90"); minimum tangent 3.25 (45°) / 7 (90°); a curve's two ends must be
  flat; one placement spans at most `maxTrackPlacementLength` 32 blocks by default; gentle ramps need a run of
  max(4·rise, 6); a one-block step directly ahead of a straight turns it into a 1:1 slope; track is 2.75 wide,
  parallel tracks 4 apart; track needs nothing under it.
- *MTR 4.1.0-beta.2* (core `Angle`, `RailMath`, `Rail`; mod `BlockNode`): 16 node directions (22.5° steps); a bend
  with equal tangent legs is a pure arc; exactly straight 45° runs need |dx| = |dz| between nodes on block
  centres; no minimum radius, no grade limit; rails draw steepest at twice the average mid-span; platform block in
  the rail's layer (top = rail + 1), edge about 1.5 from the rail's centre; doors open with a platform within 1
  block across and 2 up or down; smallest tunnel creator 3 wide × 4 high.

**So the generator lays:** 8-direction straights, every bend 45° at radius 32 (25 blocks across: one Create curve;
a 90° change is two bends with a straight between), every corner on a block centre (runs rounded to whole blocks
or whole diagonal steps; station B absorbs the remainder), stations straight and level at the city's level, offset
56 blocks sideways from the city's centre line, 24 blocks past its last built chunk. Route = one of four shapes
built from the two compass directions either side of the bearing, scored by bridge/tunnel/earthwork; none may
touch another city. Profile = the highways' taut string (band 3), grade 2.5%. Crossing a highway: **over** (rail
deck 7 above the road) or **under** (9 below, through the road's bank) by whichever moves the line less from its
natural height, as ramps at the grade applied last; where neither fits before a station's level, a **level
crossing** (the road's deck stays; lay the track across it). Cross-section: 9-wide bed (two tracks, centres 4
apart, each centre marked in polished deepslate), 6 of headroom, kerbs, walled bridges on piers (never on a road),
arched lit tunnels; stations have platforms either side (`platformHeight` 2 over the bed, the owner's ask for
Create; MTR's own is 1), 4 wide, edge 1.5 from the track centre, canopy on posts, lanterns, stepped down at the
city end.

**Measured** (the City world's settings re-stamped 74; navigator probe `PROBE=railways`): 23 lines, 110 km within
two cells of spawn; 0 faults of angle, grid, run length or station level; grade ≤ 2.5% on all; bridge 48.5%,
tunnel 10.2%; 40 highway crossings: 29 over or under, 9 level, 2 level crossings left with a 2–3 block step (a
road sloping across a level crossing); no railway crosses another. Seen in generated chunks (`region_render.py`):
a station with canopies and the 45° bend out of it, a level crossing, an underpass, an overpass on piers.
CityWorld self-test PASS.

**Stations joined to their cities (2026-11-26 evening):**
- *A station street.* Each station gets a street through its city, paved from the centre line out to the city's
  edge whatever the district would have put there, exactly as a highway's street is (`Railways.streetAt` /
  `exitsAt`, read by `PlatMap.populateRoads` and `SurroundingRoads`). It is the road line nearest the station's
  yard (or the next one either side) along either axis the station points along, never one a highway leaves by on
  that side, and chosen for the shortest road to the yard. From the city sites alone, like the highway gates, so
  planning a city never waits on a line.
- *A forecourt and a road.* In front of each station's city end, a paved yard across the platforms' width (8 deep,
  kerbed, lamp posts at the back corners). From it, a road 5 wide plus kerbs (`ACCESS_HALF` 2, the highways' paving)
  to the end of that street: out along the street, across to the station's line, and 8 blocks straight into the
  yard. Level at the city's level, cut and banked 1:1 like the line; a highway's deck and the line's own bed keep
  their columns. Stored on the line (`Line.access`), so `linesTouching`/`crosses` see it.
- *A name board.* An oak sign on a post in the yard, both faces: the city's name, "Station", "to", the other city.
- *Clear of the highways.* A station tries offsets of 56, 112 and 168 blocks each side, keeping the first whose
  yard, approach and platforms stay clear of every highway's run out of the city (`HIGHWAY_RUN_KEPT`, 80 blocks from
  each gate). Before this, a diagonal Downtown station's yard sat on the highway's exit.
- *A straight-line shape.* When the moved stations ended up on one compass line, all four bend shapes failed (one
  run would need a negative length): stations on one line, or within 32 blocks of it, are now joined straight and
  station B moves sideways onto it.
- **Measured:** around the owner's spawn, 8 stations, each road 50–70 m (110–270 m before the street was chosen by
  road length); 24 lines, 116 km within two cells, 0 geometry faults, the same 2 sloping level crossings. Seen in
  generated chunks: the north-running Downtown station (yard, lamps, board "Downtown / Station / to / Higham", road
  to its street) and the diagonal one to Wolfminster. Renders: `docs/railways/station-diagonal*.png`.
- **Navigator:** stations are places (`/nav pois` lists them, pinned in their own colour), `/nav to station [city]`
  routes to one, and a route that starts or ends at a station follows its road ("Take the station road to Downtown
  Station"; "Leave Downtown Station by the station road into Downtown"). Probe `PROBE=stations`.

**One network, one station per city (2026-11-27, owner: "interconnected ideally ... through running as well or end
station depending on track geometry"):**
- *A station per city* (`Railways.Hub`, from the sites alone), always along a compass axis (east-west or north-south:
  square stations draw cleanly; lines still leave at any of the 8 legal angles). Every line of that city starts there.
- *Through or terminus by the geometry.* Each line is given the end its bearing is nearest. **Through** when both
  ends of an axis have a line within 67.5° (lines at least ~45° apart on opposite sides): trains run straight through.
  **Terminus** when all lines go one way (or are about 90° apart): buffer stops at the city end, a concourse across the
  platforms' ends, all lines out of the far end. The axis is the one that bends the lines least. In the owner's world,
  every station within two cells came out through (cities there have 2–4 partners spread round).
- *Tracks:* one pair of tracks per line at the busier end (up to 3 pairs), 12 apart, side platforms outside and island
  platforms between (`PAIR_SPACING` 12, island 5 wide). A line on pair *j* at one end and one on pair *j* at the other
  share it: through running. Lines at one end are ordered across by the side they bend to, so they need not cross.
- *Throats:* 32 blocks of plain level bed, as wide as the station, past each end of the platforms (room for a player's
  switches and crossovers), then each line leaves its pair straight.
- *Through station:* on the city side a two-storey building opening onto the near platform, stairs inside to the
  upper floor, a glazed footbridge from there over every track, stairs down to each other platform; canopies on posts,
  lanterns, a forecourt with lamp posts, the name board (city, "Station", first two destinations) and the road to the
  station street. *Terminus:* a concourse level with the platforms, door and steps to the forecourt.
- *Placement:* beside the city (through: offset across its axis, on the side the lines lean; terminus: out along its
  axis), the whole yard clear of every city's ground, slid along/sideways (0, ±48, ±96, ±144) until it keeps clear of
  every highway's run out of the city.
- *Lines between pairs:* exactly from one pair's throat end to the other's, every run a whole number of blocks or
  diagonal steps solved in integers (so every corner is a block centre, no station shifting). New shapes: the
  stations' own exit directions as free runs (a line runs on past its city before turning) and an S-bend for a small
  sideways step. If the given ends fail, a through station's other end is tried on the same pair (two lines then share
  it out of the station and part at a junction). A route may not run within a highway's width for more than 40 blocks.
- *Level crossings:* where a line crosses a highway with the rail 3 above to 8 below the road and the line cannot
  ramp to it (out of a station), the **road** dips or rises to the rail's level over 6 blocks per block of difference
  (`dipToRail`, only at a crossing of 30° or more). The 2-4 block steps are gone.
- **Measured** (the owner's world, navigator `PROBE=railways` rings 2): 23 lines, 131.5 km, 15 stations, 0 faults
  (angle, grid, run length, level stations, grade ≤ 2.5%); 36 highway crossings, 25 over or under, 11 level (7 by the
  road dipping); 3 junctions by stations; **4 flat railway crossings**, all on one 13 km line (Stonewell–Elmworth)
  across the lines round Little Italy. Station roads 60–100 m. Rendered: Downtown's through station (2 pairs, 3
  platforms, footbridge, building, forecourt, road), and a terminus (forced for the test: 3 pairs, concourse).
  `docs/railways/hub-*.png`.

- *Reviewed by Codex (gpt-6.1-sol, read-only) before commit:* six findings, all real, all fixed: odd `stationLength`
  put exits off block centres (now rounded down to even); resampling could drop a line's exact end (the last sample is
  now the pair's centre); the short last segment could exceed the grade (grade now per segment length); a highway
  could dip where a curving line only came near it, and a large dip could end in a cliff at the line's bounding box
  (`dipToRail` now works from the actual centreline crossings, ramping along the road from each); `tracks=1` lines
  missed the station's marked tracks (stations now mark one track per pair then). Measured after: the same 23 lines,
  0 faults; the Wolfminster crossing reads road 70 → 67 across both lines → 70, about a block per six.

**Trunks, corridors and the look (2026-11-27 overnight, owner: "long stretches of track that are parallel ... can
combine and then split"; "prettify the stations and also the bridges and tunnels ... tunnels more circular but also a
little taller so I can put catenary wires above"):**
- *Trunk and fan.* Past each end of the platforms the tracks fan (64+ blocks of plain bed) down to one two-track
  trunk; lines leave the trunk at junctions 48 apart; the line leaving last carries straight on (the end's *main*).
- *Corridors.* A line that is not its end's main may instead leave the main line further out, every 64 blocks along
  that line's straight runs (never its last run into the far station, never inside a tunnel), its deck pinned to the
  main line's on the shared stretch. Taken when cheaper to build and the ride round is at most 12% longer; only off a
  main line that is main at both its ends (no line waits on one that waits on it). `-Dcityworld.railways.nocorridor=true`
  is the control. Fixed on the way: a line leaving station B straight on held B's level over its whole first run (the
  trunk direction was given reversed).
- **Measured** (City world restamped 74, navigator `PROBE=railways` rings 2): control 21 lines 110.7 km, 8 pairs side by
  side for 2,469 m; corridors 23 lines (two that found no route now do, via a corridor) 118.6 km, side by side 872 m,
  6 corridor lines / 7 corridor junctions, 0 faults (grade, angle, grid, levels). Brookbridge's 424 m stays: its main
  line is not main at its far end.
- *Tunnels:* a round bore (`BORE_RADIUS` 5.5 springing 4 over the deck: 11 wide, 8 clear over the bed at the crown,
  two more than before, room for catenary), brick lining 1.5 thick, a deepslate-brick rib every 8, a polished
  blackstone portal ring at each mouth, sea lanterns in the crown every 12.
- *Viaducts:* piers every 16 down to the ground or sea bed (never on a road), an elliptical arch between them, a
  stone-brick cornice under the parapet, a lamp on the parapet every 32.
- *Stations:* a glass train shed over platforms and tracks (springing above the footbridge, deepslate ribs every 8 with
  lights over each pair), brick piers up the side walls to it; the platform canopies became lamp posts every 16 down
  each platform with benches between, and each post carries a "Platform N" sign facing each track (tracks numbered
  across from the city side) so MTR/Create platforms can be named to match.
- Seen in generated chunks (region slices): the Deerford shed and fans, the corridor junction east of Deerford, a
  bore at (298,−236), a viaduct over water at (−403,−672).

**Not done yet:** "take the train" in the navigator (routing over the lines); railway–railway crossings are flat (no
grade separation yet: the plan would treat lower-keyed lines like highways); a terminus has not come up naturally in
the measured area; nothing of this seen in a game client; the metro.

### Requirements for what comes next (owner, 2026-11-26)

- **Underground rapid transit = the existing subway (`Support/Subway`), rebuilt for real trains**: an EMPTY trackbed
  (players lay Create or MTR track themselves), each track bed **at least 3 blocks wide**, and **platforms one block
  higher** than today relative to the bed (Create/MTR trains need a higher platform). That means a wider tunnel and
  hall than the vanilla-rail subway; a setting (e.g. `subways.trackbed: vanilla | empty`) so old worlds keep theirs.
- **Elevated rapid transit** that avoids the roads: viaducts along lot rows between the road lines (rows 2 and 7 of
  each platmap are roads), crossing roads above them. Changes the city plan → new worlds only (gate by `made_with`).
- **Railways (overground, can be elevated)**: a variant of the highway (`worldgen/Highways`) — its route search,
  grade limit and GROUND/BRIDGE/TUNNEL sections — with a narrower deck, gentler grade and wider curves, drawn as an
  empty trackbed whose orientation follows the route so Create track (which curves freely) or MTR rail can be laid
  along it. Vanilla land only, like highways.
- Automatic Create track (straight runs, commissioned after the chunk loads, the `Support/RailSmoothing` pattern) is
  a possible later step; MTR stores its rails in its own saved data, so MTR stays "player lays the track".

### The metro: plan (2026-11-27, owner: "how to make the routing for it, how to enter it, circular or line routes?
should it go near POI like stations ... strong integration for MTR")

*Why the owner has seen none on vanilla land:* `CityWorldSettings.applyVanillaTerrain` switched subways off and
`ShapeProvider_Vanilla.supportsSubways` answered false, because a link's tunnel runs under nature lots too, and on
vanilla land a nature lot is vanilla's ground: CityWorld draws nothing there, so the tunnel would stop at it.

*First step, BUILT (mc1.21.1, 2026-11-27):* a vanilla-land world made with 7.4 (`vanillaSubways`, stamp 74)
plans the existing subway, with one extra rule in `Subway.linkOk`: a link is kept only if every chunk it passes
under is a built lot of a city (not a nature lot, not a shore, in city range). Measured on the City world (restamped
74) around chunk 76,−34: a station hall 24 below the street under its entrance building, a north-south line at the
lower level from z −720 to −415 joining three stations, its shell intact, **0 water cells in 15,818 tunnel cells**
though vanilla aquifers sit a few blocks off; a station whose neighbours lie past nature lots stands alone. Still
the vanilla-rail subway (1-wide track, 6-high tunnel), not yet the real-train trackbed below.

*Routing.* Keep the subway's grid rule (east-west lines 24 under the street, north-south 32, so lines never meet
except at an interchange station, every tunnel chunk a pure function of two stations), but per city on vanilla land:
a link only where every chunk it passes under is one of that city's built lots. Lines, not circles: a city of a few
platmaps gives one or two straight lines through its centre, crossing at an interchange; a loop only pays in a large
city, and the grid's interchange ramp already turns a dead end round. Stops should favour what people go to: the
railway station (a metro stop beside the hub's forecourt, so the mainline and metro meet), the city centre, malls,
hospitals and the civic squares — `CityWorldAPI.findLots` already knows them, so a station chunk can be chosen
nearest a POI rather than first-free.

*Entrances.* The station lot's stairs down from the street (today's `SubwayStationLot`), plus one in the railway
station's forecourt when the stop is beside it. The navigator can then route "walk to the metro, ride, walk on".

*For real trains (the owner's requirement above):* an empty trackbed per track at least 3 wide and platforms a block
higher, behind a setting so old worlds keep their vanilla-rail subway.

*MTR.* Research in `docs/railways/MTR-INTEGRATION-RESEARCH.md`: CityWorld can create MTR *station areas* (name,
colour, zone, corners) through MTR's in-process `UPDATE_DATA` operation once its server has started, from one optional
compat class loaded only with MTR present. Platforms cannot be pre-made usefully: MTR deletes a platform with no
platform rail under it, so the player lays the track and MTR finds the platforms inside the named area. Platform
numbers are therefore CityWorld's own signs on the lamp posts, which the player copies when naming MTR's platforms.
Create has no station without track. Unverified there: MTR's JSON key names for a position, and how many stations
one update may carry.

*MTR station markers: BUILT (2026-11-27, mc1.21.1 e5018cfc, `integration/mtr/MtrStations`).* With MTR installed, every railway
station CityWorld draws becomes a named MTR station over its yard (all heights), in the city's name, a colour from
the name. How: the chunk that draws the station's name board calls `MtrStations.offer` (worldgen, any thread: it only
queues); once a second the server thread waits for `MTR.main` (MTR builds its simulators on ServerStarted), sends
`LIST_DATA`, leaves out any station whose id MTR has or whose middle an existing station covers (a player's), and
sends the rest as one `UPDATE_DATA` (batches of 32 per dimension). All by reflection, no compile dependency, so CI
and an MTR beta that moves a class are safe (a failure logs once and turns it off for the session). Ids are a hash of
the yard bounds and dimension; every id sent or found is kept in `<world>/data/cityworld_mtr_offered.txt`, so a
renamed station keeps its name and a deleted one stays deleted. JSON keys read from a serialized MTR 4.1 station
(`id`, `transportMode`, `name`, `color`, `position1`/`position2` {x,y,z}, `zone1..3`, `exits`). Measured on the dev
server with MTR 4.1.0-beta.2 (owner's City seed, chunk 91,-16 r14): "1 MTR station(s) in minecraft:overworld:
Deerford", saved as `mtr/minecraft/overworld/stations/C0/D4867A0C64B6C8C0`; a rerun with the offered file deleted
sent nothing (the list guard) and rebuilt the file. Not yet: metro (subway) stations as MTR stations; not seen in a
client (the MTR dashboard should list Deerford). Land explored before this build: every 30 s a planner thread
offers the stations within 768 blocks of each player (`hubsTouching`, same ids as the boards); not measured, since a
headless server has no player. Self-test PASS (without MTR).

### Round 8: the owner's NewTestCity walk (2026-11-27) — fixes (steps 1–3) BUILT on mc1.21.1, uncommitted

**Built (same day), measured on the same seed and radius** (map `docs/railways/survey-newtestcity-after.png`):
- **Detours:** "skirt" route shapes (`Railways.skirts`, SKIRTS 128..1024) round a station's own city; tried when the
  best fit is over 1.3x the straight distance. L7 13,815 -> 6,570 blocks; every line within 1.2x; 0 turn-backs.
- **Rail over rail:** each line is planned first alone (`bases` cache, `Planned`), then again over the lines it
  crosses (`overCrossings`: >= 20 degrees, parted by 9 blocks, 14 where either is in a tunnel, split about the
  middle, or one moves the whole way, else a level crossing). Drawing (`drawRailways`) now draws every line at a
  column lowest first; the upper is a bridge with no pier, arch or fill over the lower (`overRail`).
  L0xL7 0 -> 9 blocks apart.
- **One trunk, not two side by side:** a line may branch off any line before it (`lineOrder`) at its station end,
  not only a main line that is main at both ends; branch points are kept only where the ride round could still be
  short enough (an octile bound); the far end's corridor is tried from the near end's choice and from the trunk.
  L4 off L3, L9 off L13, L20 off L14, L6 off L0: side-by-side runs 2 -> 0, still 26 lines (the owner wants every
  connection kept).
- **Ice:** `sweepRailways` clears ice, packed/blue ice, snow blocks and powder snow off the bed and 32 blocks over it.
- **Profile:** railway `smoothing` default 3 -> 6. Reversals per km 1.35 (3), 1.24 (5), 1.21 (6), 1.16 (8).
- Not done: the remaining near-zero-angle "collisions" in the survey are branch merges (same track, by design).


The owner's ask: crossings still break, ice over the track, some routes are silly (out east, then 135 degrees back),
two lines where one would do, gentler profile, prettier platforms, a footbridge narrower than the platforms, a
building that is not one block of stone bricks, nearest-station and depot POIs, turnback sidings and depots, MTR
platform height, Create or MTR, and ideas to make a one-player "state" RPG more fun. Their screenshots: the snowy
X-crossing at -5226,830; the dark stone-brick room at -3566,-851; ice spikes on a viaduct at -2647,-33; a station at
1801,2045 with an MTR train standing at a platform they edged in MTR's yellow platform blocks.

**Measured:** new probe `-Dcityworld.probe=survey:railways[:radius]` (mc1.21.1, plans only, writes `run/railways.txt`)
plus `scripts/rail_survey.py railways.txt out.png` (crossings with both decks, turn-backs, side-by-side runs, map:
`docs/railways/survey-newtestcity.png`). Seed -1126521054057737300, radius 7000, 92 s: **24 lines, 12 stations,
107,078 blocks of line.**

| Finding | Numbers | Cause (read in the code) |
|---|---|---|
| Rail-rail crossings collide | 15 crossings, **14 within 7 blocks of each other** (the snowy X: decks 72.5 / 74.5) | `Railways.profile` grade-separates from HIGHWAYS only; a line never sees another line |
| Two tunnels on top of each other at a station mouth | L0 x L6 at -3532,-944, 3 degrees apart, both TUNNEL at 64 (= the dark room) | lines leaving the same station end take slots that cross as they fan out |
| Long detours / turn-backs | L7 13,815 blocks for 6,028 straight; L18 8,747 for 3,772; L14 5,216 for 2,834; the three 100+ degree turns are all on these | `best()` keeps the cheapest shape within `LENGTH_BUDGET` of the shortest that FITS; when every short shape is refused (a city chunk or a highway run in the way), only a long one fits and it is taken however long |
| Pairs that cross twice | L12/L14, L17/L18, L18/L21 (and L8/L13 at a junction) | the same detours |
| Ice over the track | screenshot | `sweepRailways` clears trees, snow, plants and fluids; icebergs/ice spikes (ice, packed, blue ice, snow block) are none of those |

**The plan, in the order I would build it (each measured by the survey before/after, then the self-test):**

1. **Crossings, grade-separated.** At a crossing of two lines, the line whose key is lower ramps OVER the other's
   *natural* deck (+8, the `OVER_HIGHWAY` rule; under where over cannot reach, like highways); the other is held at
   or below its natural deck there. Natural decks depend on no other line, so this stays a pure function of the pair
   (no cycle, chunk-order safe). Station mouths: slots assigned by the bearing each line leaves on, so lines fan out
   without crossing. Target: survey reports 0 collisions.
2. **No detours.** A line longer than ~1.5x its straight distance is not built when the network stays connected
   without it (its cities are reached through others); otherwise kept. Two lines that cross twice: the longer goes,
   same connectivity rule. Lines running side by side into one station become one line and a junction (the corridor
   mechanism already exists). Target: 0 turn-backs, no pair crossing twice.
3. **Ice and the profile.** `sweepRailways` also clears ice, packed ice, blue ice and snow blocks in the headroom.
   "A touch more taut": `smoothing` 3 -> about 6, measured as ups-and-downs per km of deck before/after (the owner
   accepts the half slabs).
4. **MTR heights.** Platforms 1 block above the bed when MTR is loaded (`platformHeight` exists, 1..3; the default
   becomes 1 with MTR, 2 without). Platform edges in MTR's own `mtr:platform_uk_1` (the yellow edge the owner placed
   by hand) when MTR is loaded. **Create vs MTR: lock in MTR.** Its depots spawn trains on a timetable for each
   route by themselves; Create needs every train built and scheduled by hand, and nothing generated can do that.
5. **Prettier stations.** Platforms: benches, bins, a canopy per island, lamps, tactile edge; MTR's PIDS and station
   name signs when loaded. Footbridge: 3 wide down the middle, not the full platform width, stairs (MTR escalators,
   `mtr:escalator_step`/`escalator_side`, are placeable block states: try one in game first). Building: plinth,
   pilasters, arched windows, a contrasting cornice, clock tower or corner pavilions (the owner's pictures: brick and
   sandstone halls, a glass barrel vault over arcades); 2-3 facade variants by city. Roof variants (ridge-and-furrow,
   arched trusses) after.
6. **Turnbacks and depots.** A turnback siding between the running lines beyond the platforms of every through
   station and before every terminus. A depot (a large flat yard of parallel siding beds and a shed, about 160 x 48)
   beside a line outside the city, one per few stations (busiest first), registered as an MTR depot "<City> Depot"
   through the same `update_data` call as the stations.
7. **POIs.** Navigator: `/nav stations` (nearest five, clickable), `/nav to depot`, and stations and depots as
   JourneyMap waypoints. (`/nav to station` already finds the nearest one.)
8. **Subway:** left as it is for now (minecarts; a cart add-on is the owner's choice); a later rework for MTR.

**Round 9 (2026-11-27, built on mc1.21.1, uncommitted; Arcadia has `cityworld-7.4-dev-round9` + Navigator
`0.2.0-dev-round9`; self-test PASS 232, plan hashes unchanged):**
- Platform height is by the mods installed (`railways.platformHeight` 0 = auto: 1 with MTR, 2 without).
  `integration/mtr/MtrBlocks` looks MTR's blocks up by name (null without it): `platform_uk_1` edges facing the track,
  `pids_1` back-to-back pairs on `pids_pole`, `rubbish_bin_1`, `station_name_tall_standing`. Without MTR: yellow
  edge, cauldron bins. Both: a light-grey band behind the edge, an andesite course every 8, potted azaleas by the
  benches. Probed with MTR loaded: 570 edges, 36 board pairs, bins and name posts, all facing as meant.
- Footbridge stairs: one block wide down the platform's middle line (walkways either side); on a platform whose middle
  falls between two blocks, the furniture, stairs and sign posts take the one away from the track.
- Station building: red brick dressed in stone (plinth, string course, pilasters every 4, lintels), a parapet, a
  clock tower over the door (white face, open belfry, spire lantern), a canopy on posts over the steps.
- Depots: drawn as a yard beside the station on the side away from its city (stabling roads every 5, a 64-block
  shed, buffer stops, opening off the fan) and offered to MTR as "<City> Depot" (`depots`, MtrStations). **The owner
  does not want them at the station** ("its own space/building on the outskirts or even not in the city"), so
  `DEPOT_PER_MILLE` is 0 (off); the drawing (`drawDepotCell`) and MTR offer stay for the move. Turnbacks: not built
  separately (the depot lead was to serve).
- Navigator: `/nav stations` (nearest eight, clickable, pinned, with `[depot]` links), `/nav to depot [city]`,
  depots as places in `/nav pois` (built against the round 9 CityWorld jar in `libs/`).
- Self-test: each check logs `SELFTEST: time <check> <ms>`. 680 s in all; the big ones: checkPlanning 151 s,
  checkCobblemonLots 90 s, checkSubways 76 s, checkDecorationAndSigns 53 s, checkPristineTwin 52 s, checkNetherTwin
  52 s, checkMalls 48 s, checkStructures 39 s.

**Round 10 (2026-11-27, mc1.21.1, uncommitted): depots out in the country, beside a line** (owner: "Beside a line").
- The station-side depot is gone (Hub's `depot`, `drawDepotCell`, `inDepotYard`). `Railways.depotOf(line)` /
  `depotsTouching`: one line in two (`DEPOT_ONE_IN`), at the first stretch from the line's middle out that runs
  straight along a compass axis on the ground for the yard (120) and both leads (24 each), at most a block off level
  along the yard and two along the leads, 400 from both ends; either side, if clear of cities (64), stations,
  highways and other lines. Named for the nearer city ("<City> Depot", MTR and Navigator).
- Drawn (`drawDepots`, `sweepDepots`): ground cut and banked 1:1 to a level yard 40 wide; stabling roads every 5; a
  low wall between yard and main line along the body (lamps every 16) and round the outside; leads narrowing back to
  the line's bed, easing to its deck, open where a player lays the switch; a 64-block shed in the middle (brick on a
  stone plinth, windows, open ends, glass roof on deepslate ribs every 8, piers, lanterns over the roads).
- Why only ground: in seed City's 26 lines, 224-block straight ground runs exist on 6 lines; 168-block ones on 17.
  Survey `survey:railways` prints `DEPOT x0 z0 x1 z1 level dir side cityX cityZ`; 6 depots within 7000. Probed the one
  at 412..532, 4444..4484 with MTR: drawn as meant (`docs/railways/depot-beside-line.png`), offered as "Shoreley Depot". (A flipped yard, a ladder off the
  main line with the nearest road shortest, was tried and dropped on the owner's word the same hour.)
- **MTR rails: researched here, built in round 11.** Read from MTR 4.1.0-beta.2's jar: a rail is core data, not blocks.
  `UpdateDataRequest` (the request we already send stations and depots in) has a `rails` list; a rail's JSON keys
  are `position1`/`position2` (`{x,y,z}`), `angle1`/`angle2` (one of 16 compass names: E, SEE, SE, … NEE),
  `shape` (QUADRATIC, TWO_RADII, CABLE), `verticalRadius`, `tiltPoints` and the tilt fields (0), `styles` (a list,
  empty = default), `speedLimit1`/`2`, `isPlatform`, `isSiding`, `canAccelerate`, `canTurnBack`,
  `canConnectRemotely`, `canHaveSignal`, `signalColors`, `transportMode` (TRAIN). MTR's own rail tool sends one
  with `PacketUpdateData.sendDirectlyToServerRail(ServerLevel, Rail)` and sets the two node blocks (`mtr:rail`,
  `BlockNode`: `facing`, `is_22_5`, `is_45`, `is_connected` true). CityWorld's lines run on 8 compass directions,
  a subset of MTR's 16 angles, so a line maps to nodes at each straight run's ends and a curve rail across each
  corner; platform tracks would be `isPlatform` rails (MTR then makes the platforms itself), depot roads `isSiding`.
  Unmeasured: whether a rail sent before its chunk's nodes are placed survives, and how much data a world of lines makes.

**Round 11 (2026-11-27, mc1.21.1 `786ddfa2`, pushed; the state before it is tagged `pre-mtr-rails` (`363b3812`)): MTR rails.**
- `integration/mtr/MtrRails`: with MTR installed, node blocks (`mtr:rail`, `MtrBlocks.railNode`, facing by axis:
  `facing` true along x, `is_45` on the diagonals) are set in each chunk as it is swept, and the rails between them
  are queued to MTR from the same chunk (`MtrStations.offerRails`, batches of 256 per dimension, ids saved with the
  stations' in `data/cityworld_mtr_offered.txt`). A line: nodes on whole steps along each straight run, at most 32
  apart, at y = deck + 1; one rail per arc between runs; the first and last runs reach back to the station's fan
  end; a corridor line joins its parent at the parent's nearest node behind it. A station: one `isPlatform` rail per
  platform track, and a fan rail from each platform end to the main line's track **on that platform's side of the
  station's centre line** (owner: right platforms to the right track, left to the left: directional platforms, no
  crossings in the throat).
- Speeds (owner: "300 kmph on straights, slower on curves"): straights 300, the 45° arcs (radius `curveRadius`, 32 by
  default, 40 at most) 80, platforms and throat 80.
- **Styles must be `["default"]`**: MTR's `RenderRails` draws a rail only through its style list, so the first build
  (empty list) made working but invisible track. Seen in game after the fix: platform tracks curving out into the
  line at Deerwick (RailTest, a copy of NewTestCity's level.dat). Not yet seen: the side-split throat, a train at
  300, a corridor junction.
- Self-test PASS 232, hashes unchanged (MODERN c4b95b7c, CLASSIC 5be00684, APOCALYPSE fd6df8c0).

**Round 12 (2026-11-27, mc1.21.1 `9a8f1257`, pushed): MTR track in the depots.** Each yard road is an MTR siding
(`isSiding`, 120 blocks, 40 km/h) on the drawn lane, and both ends of every siding fan out to a junction node on the
line's near track, the first node past the depot's lead (body + `DEPOT_LEAD`). Line nodes now replace the half slab
on a grade (a node there was skipped, leaving gaps of 60+ blocks). Probed at Shoreley's depot: seven sidings on
their lanes, junctions at x 384 and x 574. Not yet seen in game: the fan curves into the line. Self-test PASS 232,
hashes unchanged. Arcadia has `cityworld-7.4-dev-round12`.

**Round 13 (2026-11-27, mc1.21.1 `c15a5aa3`, pushed): train services, Local and Intercity** (owner: "multi station
lines and sprinter and intercity equivalent", "platform numbers and stuff").
- `integration/mtr/MtrServices`: at each station the lines are paired (straight through first, then the leftovers
  with each other: the train turns back at a platform); the pairs make long runs ("strokes"), each line on exactly
  one; a stroke is cut evenly into **Locals** of about 5 lines (every station) and **Intercity** services of up to 14
  (ends, cities of radius 480+, or 400+ with 4+ lines, and turn-backs). Survey of seed 8675309, 12000 blocks: 30
  Locals, 18 Intercity (`scratchpad services.py` draws them from `survey:railways`' SERVICE/CALL rows).
- Home depot (`MtrStations.homeDepot`): the depot on the first of the service's lines that has one, else a depot on
  another line at one of its stops. 29/30 Locals and 18/18 Intercity have one. A yard is split evenly between its
  services (at most 4), each share its own MTR depot ("<city> Depot", "<city> Depot 2", ...). When the depot is
  found, every station the service calls at, every line at those stations (two lines leaving the same end share a
  stretch that belongs to one of them: sending only the service's own lines left an 800-block gap) and the depot's
  rails go to MTR; the routes follow once every stop has platforms. Trains: Class 377 (4/8 cars) for Locals, Class
  802 (5/9, 3 where a platform is short) for Intercity; 8 an hour per depot.
- Platforms: numbered 1..n from the city side, by `hub.across` (MTR names them all "1"; a player's own name stays),
  for every station round a player and every station a service calls at.
- Track: one-way throats (every platform reachable from the inbound track and onto the outbound at each end) and
  depot leads by the tracks' direction; the far-track lead lost its extra 16 blocks (it missed a bend: the yard's
  west exit was cut off). Probed at Amberville: three services (Local Millminster–Pineport, Local Eastside–Market
  District borrowed, Intercity Millminster–Newridge), three depots SUCCESSFUL, one network of 10,227 rails.
- **Probe traps:** MTR saves only on a clean stop (the probe script now enables RCON on port 25598 in the dev
  `server.properties` and sends `save-all flush` + `stop`); its data is under `run/world/mtr/minecraft/overworld`,
  not `mtr/cityworld/city`.
- Self-test PASS 232 without MTR, hashes unchanged. Arcadia has `cityworld-7.4-dev-round13`. Not yet seen in game: a
  train running a whole service, a turn-back, the platform numbers on MTR's screens.

**Round 14 (2026-11-27, mc1.21.1 `2fb1bfb0`, pushed): the metro** (owner, away: "you can start on the metro").
- `Support/Metro`: per vanilla-land city a Red line east-west (floor street-48) and a Blue line north-south
  (street-64), each a straight run across the city's inner disc (`radius*0.88-8`, with a little wobble off the
  centre), stations of 4 chunks spread along it with a gap of 2, Central (the interchange) at the middle, a depot of
  4 chunks past the positive terminal. Island platforms (MTR platform blocks where MTR is in), name signs, a concourse
  8 above the floor, the interchange a zigzag stair between the two concourses. Planned as a pure function of the
  site (memoised); drawn by every lot it crosses (`PlatLot` phase 8) and by the wild chunks inside a city
  (`CityWorldChunkGenerator`, through `ShiftedRegion`).
- Names (`Metro.names`): Central = "<city> Central"; others take a street name from beside the station that no other
  station has, else a compass name (West, Inner West, ...).
- Entrances (`Plats/Urban/MetroEntranceLot`): claimed in `ShapeProvider` before the context fills the platmap, one per
  station per platmap, beside the line and preferably a road, never on the other line, the interchange, or the
  subway station's row/column. A glass pavilion, then a switchback stair to the concourse; where a subway tunnel is in
  the way the sign says "(closed)" (4 of 22 in the test city).
- MTR (`MtrStations.offerMetro`): stations, depots "<city> <Line> Depot A/B", rails (two-way, 80 km/h, siding 40,
  turn-back at terminals and sidings), then each track a shuttle: route there + back, a London Underground 1996 three-
  car train, platforms named 1 (low track) and 2 (high). **MTR will not path a train out of a siding that shares its
  end node with a platform** (all four depots PATH_NOT_FOUND; read from `Depot.finishGeneratingPath`: the main route
  was found, the siding leg was not), so the siding starts 2 blocks past the terminal. Probed on the City world's
  seed at (-4016,-1040): 12 stations, 4 depots SUCCESSFUL, 8 routes.
- Setting `railways.metro` (11th field): on by default for worlds stamped `made_with` 75 (`MADE_WITH` is now 75);
  the owner's City world is 74 and was left alone (an explored city would get a part-built metro). Self-test PASS
  without MTR, hashes unchanged. Arcadia has `cityworld-7.4-dev-round14`. Not yet seen in game: anything of it.
- Not this round: an entrance from the railway station's forecourt; more trains per siding.

**Round 15 (2026-11-28, mc1.21.1): frequent trains, the walk to the metro, chosen trains** (owner: "high frequency
... every minute or so", "different pacing for ic vs local", "a pretty route to metro from the station", "how can we
pick the trains").
- Headways (setting `railways.headway`, default 100 s): a Local every 100 s, an Intercity every 200 s, the metro every
  50 s. MTR's depot frequency f gives a departure every 200/f real seconds in a 20-minute day (`frequencyFor`); a
  siding's `maxVehicles` caps the trains out (Local 20, Intercity 16, metro 12 per service, split over its sidings),
  so a long service reaches its headway once that many fit. Trains keep apart by MTR's block reservation; an
  Intercity may wait behind a Local on shared track.
- The metro is a loop where the line has room: a one-way crossover (40 km/h) past each terminal, one depot
  ("<city> <Line> Depot"), trains down the low track and back up the high one; a line too short for crossovers keeps
  its two shuttles. Probed on the City seed (-4016,-1040): both depots SUCCESSFUL, paths through the crossovers, MTR's
  departures one every 50 s.
- MTR's own journey planner (its web map, port 8888, the "Transport System Map" button) works on these routes:
  `POST /mtr/api/map/directions` from Eastside West to West Lower Clayson Place answered five hops on the Red Line
  from platform 2.
- `Support/MetroWalk`: from each railway station's forecourt a railed stairwell (5 cells open, lanterns, a METRO
  board), down a step a cell along the station (outside the city's ground) to the concourse's height, then a level
  tiled corridor (the line's stripe, a light every 6 cells) into the side of the nearest metro station's concourse;
  boards at the stairs' foot and at the concourse end. Routes are axis legs scored by length, kept off the other
  line's band and its own line's until the last leg, never within 5 blocks of themselves; a pure function of the
  plan, drawn by every chunk it crosses (built lots in `Metro.generate`, wild chunks after the station sweeps).
  Probed: 301 cells, y 65 to y 26, nothing in the way.
- Setting `railways.trains` (`local`, `intercity`, `metro`): MTR vehicle id with `{car}` (cab_1/trailer/cab_2/
  cab_3) or a list of car ids, car length, bogie offset, cars and long cars, front padding. For the owner's own
  Intercity resource pack.
- Street names: "11st/12nd/13rd" (upstream) now "11th/12th/13th".
- `scripts/metro_map.py` draws `run/metromap.txt` (written by the `metro:` probe): lots, lines, stations, entrances,
  depots, the station and the walk.
- Not yet seen in game: the walk, the loop trains, a Local or Intercity at its new headway (no railway service was in
  the metro probe).

**Round 16 (2026-11-28, mc1.21.1 `49e4a585`, Navigator `41eb733`): the stations made beautiful** (owner: "clean up and
refinement and actually beautifying", "in the station include the line map, the station displays should be properly
connected", no frequency changes).
- The railway station redesigned (`drawStation`): a clock tower, a glazed concourse, line maps (MTR route signs) on
  every platform. Its departure boards (PIDS) are linked to their platforms (`integration/mtr/MtrPids`: a platform
  board shows its own platform within 5 blocks and 8 up; a concourse board higher than that shows the whole station).
- The walk to the metro: MTR escalators down each flight (up on the left as one goes down, stairs on the right), a
  glass canopy on four posts over the stairwell with hanging lanterns, a skirting under the tiles, a light every third
  cell.
- Metro stations with MTR (`Metro.fitOut`): platform screen doors along both island edges (glass end, then a pane and
  two pairs of doors over and over, so a car's door always meets a door; doors `unlocked`), a departure board on poles
  back to back and two lit line maps in each middle chunk, fare gates across the concourse at each end (in toward the
  stairs on one side, out on the other: the gates charge MTR balance, topped up at the ticket machines beside them),
  a concourse departure board each end, two travellers (villagers, on `spawnBeings`) waiting on the island.
- Glass panes, bars, walls, stairs and screen doors are reconnected after each station or walk chunk is drawn
  (`Support/Connections`): panes drawn chunk by chunk no longer stand unjoined.
- The metro station the walk reaches is an MTR interchange with the railway station: its area grows along the walk to
  meet the railway station's (`MetroWalk.reach`; its id stays its own box's, so an old world gets no second copy). The
  two lines' central stations grow a chunk each way so they overlap (they only touched at a corner).
- Headways checked against MTR's depot code: Local 100 s, Intercity 200 s, metro 50 s (unchanged).
- Navigator: `/nav metro` lists the metro of the city you are in by line, in each line's colour, every station a link
  to its street entrance (interchange and the walk to the railway station marked), and pins them; `/nav to metro
  [station]`; metro stations are places. Probed: 12 stations in 6.8 s.
- Probed on the City seed: 180 screen-door blocks, 4 boards, 8 gates, 8 ticket machine blocks in one station (renders
  `metro_platform.png`, `metro_concourse.png`). Self-test PASS without MTR, hashes unchanged. Arcadia has
  `cityworld-7.4-dev-round16` + `cityworld_nav-0.2.0-dev-round11`. Not yet seen in game: the doors opening, the gates,
  the boards' times.
- Next: a cable car up to a viewpoint, only where a city has a big mountain close by (owner: "only for ones near big
  mountains or something, it should be tasteful"). Ferries later; aeroplanes after that.

**Round 17 (2026-11-28, mc1.21.1 `5348f406`, Navigator `3b4378e`): the cable car** (owner: "only for ones near big
mountains or something, it should be tasteful").
- `worldgen/CableCars` plans one from vanilla's heights only: a city beside a big mountain on one of its axes gets a
  base station at its edge and a summit station on the peak. Pylons go where the ground rises, plus one on any span
  over 200 blocks (`breakLongSpans`). It is refused where a highway or railway crosses the line. The survey
  (`-Dcityworld.probe=survey:cablecars`) found 3 of 65 cities. It is on in worlds made with the metro (stamp 75+);
  `-Dcityworld.cablecars.on` / `.off` override that.
- `worldgen/CableCarDrawing`:
  - alpine stations: a stone plinth, spruce posts and glass, a plank roof on beams with a slot for the cable;
  - a viewing terrace past the summit (railings, benches, lanterns, flowers on stone piers);
  - steps from the base's back door toward the city;
  - slim concrete pylons with a cross-arm;
  - trees trimmed under the cables.
  It is drawn after vanilla's decoration and reads ground from `getBaseHeight`, never a neighbour chunk.
- MTR:
  - CABLE_CAR rails, one way round: up a cable, round the summit loop, down the other cable, round the base loop.
  - A cable is cut into rails of 32 blocks or less, because MTR lets a cabin onto a rail only when it is clear.
  - Stations "<City> Cable Car" and "<City> Summit", and a depot on the base's siding.
  - **A cable car is MTR's continuous movement.** Its siding must have unlimited vehicles (`maxVehicles` 0, which
    MTR's own screen forces). It then sends a cabin every 8 s whatever the frequency; any cap makes it a single
    departure, one cabin on the whole line.
  - The route is up, down, up, so cabins carry riders both ways.
  - Probed: Echo Park, 538 m, 3 pylons, 50 cabins running about 44 blocks apart, 0 FAILED.
- Navigator: `/nav to cablecar [summit]` looks two rings of cities out and warns that it can take a minute: planning
  a mountain is slow (56 s for 25 cities in the probe, which found Echo Park's). The cable car is also a place.
- Self-test PASS, hashes unchanged. Arcadia has `cityworld-7.4-dev-round17` + `cityworld_nav-0.2.0-dev-round12`. Not
  yet seen in game: riding a cabin; the `/nav` command in a live world (only probed).
- Ferries tried and parked on the local branch `ferries-wip` (mc1.21.1 worktree, `1653be94`, not pushed). The design:
  - a crossing along an axis between neighbouring cities, over the widest stretch of water between their edges;
  - a timber pier from each bank to deep water;
  - an MTR BOAT shuttle with a spruce two-deck boat.

  The survey found **0 in 470 pairs**, even relaxed (96 blocks of water, piers to 72, water within 96 of the edge).
  Most refusals were no neighbour on the axis (276) or no water near the first city's edge (179). Planning also cost
  about 1.2 s a pair, which worldgen would pay outside every city. City to city is the wrong idea. A ferry needs a
  harbour on a coastal city's shore that runs to an island or a far coast; the pier drawing and the BOAT rails
  (`MtrRails.ferryRails`, the `Rail` mode field) carry over.

### Review round (2026-11-28, mc1.21.1 `39e584cf`, Arcadia `cityworld-7.4-dev-round18`)

The owner asked for a cleanup, review and beautification pass before going further. Three reviewers read the 7.4
code (railway planner, MTR integration, metro and cable cars), and the confirmed findings were fixed.

- **Cable cars:** both stations now have a steep slate gable with eaves and boarded ends, and the trees round them are
  trimmed back.
  - The base's steps are no longer cut off at a chunk border.
  - The steps land on the city ring's eased ground.
- **Metro:** the reconnect pass ran under every chunk of a metro city and reshaped buildings' stairs. It is now
  limited to the metro's own chunks.
  - The subway station keeps off the entrances' rows and columns.
- **Railways:**
  - `lineBetween` returns the drawn line, so a depot no longer depends on whether MTR or a chunk asked first.
  - Clean bridges measure the whole water run.
- **MTR:**
  - Planner results from a closed world are dropped.
  - The kill switch resets per world.
  - Platforms are numbered once, not by a full listing every 30 s.
  - Departure boards and line maps get only CityWorld's platforms, so a player's own boards are left alone.
- Self-test PASS, hashes unchanged. The Eastside metro probe is unchanged.

**Paths checked:** `-Dcityworld.probe=road:<n>` generates highway n whole, and both it and `rail:` now check what
stands two to five blocks over the deck. On the City seed, the railway -3664,-4336 -> -1440,-5232 and highways 0 and 2
had 0 blocked samples over about 28,000 columns, with a solid deck under 99% of them (the control).
**Roads missing in a city** are upstream's `oddsOfMissingRoad` (5.16), not a failure: in residential, midrise,
civilized and construction districts, each of a platmap's four inner street stretches is left out at 50%, at most two.
The highway gate streets and the station street are always paved. Arcadia's logs hold no CityWorld FAILED line.
The owner asked for a switch: `roads.oddsOfMissingRoad` (mc1.21.1 `7f46f228`, Arcadia round 19) is "Missing streets" on
the Customize screen, default 0.5 (today's, hashes unchanged), "Never" keeps every street.

**Known, left for later** (each is a planning change or rare):
- A railway candidate can reach past the `touching` pad box, which would leave gaps in its trackbed.
- A planning failure in `line()`/`planned()` is re-planned by every chunk.
- Screen doors at a chunk border join depending on chunk order (`Connections` reads a neighbour that may not be drawn).
- Leaves from a tree decorated later can fall back into the cabins' way.
- An MTR offer whose reply times out is not re-queued.
- Some MTR keys ignore the dimension.
- `Line.inStation` is dead code; removing it would change the drawing's reach.

**RPG ideas (for the owner to choose from, none planned):** nations as regions of cities (a name, a colour, flags on
the stations and town halls, "Entering <nation>" from the Navigator); a capital per nation (a bigger station, a
palace or parliament lot); border posts where a railway or highway crosses into another nation; goods sheds at
stations with crates to haul between cities (a contract board); city ranks that grow with the lines a player builds.

## 3. Survival city-building — idea only

Capture a lot's drawing into an off-world buffer, bill its blocks as a resource cost, and let a builder place it a
few blocks a tick in a live world on levelled ground; a player-placed city site works like `ReservedSites` /
`CitySites`, and linking two cities reuses the highway route search. A separate mod; not started.
