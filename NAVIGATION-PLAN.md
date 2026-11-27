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

## 2. Metro and railway lines — intercity railways BUILT (mc1.21.1), metro next

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

## 3. Survival city-building — idea only

Capture a lot's drawing into an off-world buffer, bill its blocks as a resource cost, and let a builder place it a
few blocks a tick in a live world on levelled ground; a player-placed city site works like `ReservedSites` /
`CitySites`, and linking two cities reuses the highway route search. A separate mod; not started.
