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

- **Smoother highways** (2026-11-26): the deck follows small bumps in the ground. `Highways` already smooths the
  ground over `SMOOTH` (12) samples and holds a `grade`; what it lacks is a minimum rise worth following, so a deck
  rides every one- or two-block lump. Candidates: a wider smoothing window, or fitting the deck to a curve (cut a
  little, fill a little) and only following the ground when the difference passes `fillMax`.
- Street-name ordinals: "11st", "12nd", "13rd" should be "11th", "12th", "13th".

### Known limits

- Building entrances are not recorded; a POI's address is the road chunk beside its lot.
- Highways meet only at cities (no interchanges), so a route between two cities goes city to city.
- On CityWorld's own land there are no highways; long trips are long street A* searches (node cap applies).

## 2. Metro and railway lines — NEXT, not started

Owner of this fork's requirements (2026-11-26):

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

## 3. Survival city-building — idea only

Capture a lot's drawing into an off-world buffer, bill its blocks as a resource cost, and let a builder place it a
few blocks a tick in a live world on levelled ground; a player-placed city site works like `ReservedSites` /
`CitySites`, and linking two cities reuses the highway route search. A separate mod; not started.
