# MTR integration research: pre-creating stations (and platforms) from CityWorld

Research date 2026-11-27. Target: **MTR 4.1.0-beta.2 for NeoForge 1.21.1** (`MTR-neoforge-4.1.0-beta.2+1.21.1.jar`,
in the Arcadia instance), which embeds **Transport Simulation Core (TSC)** as `org.mtr.core.*`.

## Sources and how far each can be trusted

| Source | What it is | Matches the jar? |
|---|---|---|
| `scratchpad/mtrsrc/tsc` | TSC source (`org.mtr.core`) | **Yes.** `javap` on the jar's `Station`, `Platform`, `Rail`, `UpdateDataRequest`, `DeleteDataRequest`, `ListDataResponse`, `NameColorDataBase`, `SimpleAreaBase`, `ClientData`, `Position`, `TransportMode`, `JsonReader`, `Utilities` gives the same signatures. Third-party libraries are relocated: fastutil is `org.mtr.libraries.it.unimi.dsi.fastutil.*` and gson is `org.mtr.libraries.com.google.gson.*`. |
| `scratchpad/mtrsrc/mtr` | MTR mod source, **older generation** (Manifold "mapping" layer, `org.mtr.mod.*`, `org.mtr.mapping.holder.*`) | **No, not by package.** The jar has no `org.mtr.mod` or `org.mtr.mapping`. Its main class is `org.mtr.MTR` (was `org.mtr.mod.Init`), packets are `org.mtr.packet.*`, and it uses vanilla Mojang-mapped types (`ServerLevel`, `Level`, `MinecraftServer`). The logic matches what the jar's bytecode shows (same fields and lambdas), so it is used below for *logic* only, with the jar's real names. |
| `scratchpad/mtrsrc/st` | A few jar-era MTR sources (`org.mtr.block.BlockNode`, `org.mtr.item.ItemRailModifier`, `org.mtr.data.RailType`, ...) | Yes. These use vanilla types and the jar's packages. |

All paths below are relative to `scratchpad/mtrsrc/` (the full path is
`C:\Users\bnair\AppData\Local\Temp\claude\a948c873f693e795\7ce9f2cf-f5bf-4618-8b62-d79245536ad1\scratchpad\mtrsrc\`).
"jar:" means checked with `javap -p` against the real jar.

---

## 1. Where the simulator lives, and how to reach it

**One `Simulator` per dimension, all inside the Minecraft server process.** There is no separate process: the
"webserver" is optional and the core is embedded.

- `org.mtr.MTR` (jar) holds `private static org.mtr.core.Main main` and
  `private static ObjectArrayList<String> WORLD_ID_LIST`. Older source: `mtr/fabric/src/main/java/org/mtr/mod/Init.java:53,72`.
- `main` is built on **ServerStarted**: `Init.java:172-184`. It is
  `new Main(<world>/mtr, port, useThreadedSimulation, useThreadedFileLoading, webserverSetup, WORLD_ID_LIST...)`, and
  `WORLD_ID_LIST` holds one id per `ServerLevel` in the form `"<namespace>/<path>"`, e.g. `"minecraft/overworld"`
  (`Init.java:176-179`, `getWorldId` at `Init.java:318-321`. jar: `MTR.getWorldId(Level)`).
  On NeoForge the hook is `org.mtr.neoforge.MainEventBus.serverStarted(ServerStartedEvent)` with a plain `@SubscribeEvent`,
  so it runs at **NORMAL priority** (jar, `javap -v`).
- `Main(...)` builds one `Simulator(dimension, dimensions, rootPath, threadedFileLoading)` per id
  (`tsc/src/main/java/org/mtr/core/Main.java:103-111`). `Main.simulators` is `private final`.
- **Threading:** if `useThreadedSimulation` (an MTR server config) is on, each simulator ticks on its own
  `ScheduledExecutorService` thread every 10 ms (`Main.java:126-131`). Otherwise `main.manualTick()` runs on the server
  thread at the start of every server tick (`Init.java:237-240`). Either way, **simulator state belongs to the simulator's
  tick**. `Simulator` javadoc: "State mutation is single-threaded: any cross-thread work ... must be marshalled through
  `run(Runnable)`" (`tsc/.../simulation/Simulator.java:23-31`).
- **There is no public accessor for a `Simulator`.** `MTR.main` is private and `Main.simulators` is private. Reaching
  them would take reflection, and then every mutation would still have to go through `simulator.run(...)`
  (`Simulator.java:340-342`, drained at `Simulator.java:483-484`).

**The supported, public path is the message queue that MTR's own packets use:**

```java
// jar: org.mtr.MTR
public static <T extends SerializedDataBase> void sendMessageC2S(
        String key, MinecraftServer server, Level level,
        SerializedDataBase data, Consumer<T> callback, Class<T> responseClass);
```

- Logic: `Init.java:287-291`. It finds the dimension index with `WORLD_ID_LIST.indexOf(getWorldId(level))` and calls
  `main.sendMessageC2S(index, new QueueObject(key, data, callback, responseClass))`. The callback is wrapped in
  `server.execute(...)`, so **it runs on the server thread**. If `main == null` (MTR not started yet), the call is
  **silently dropped**. A `null` level **broadcasts to every dimension**, so always pass the level.
- `Main.sendMessageC2S` puts the object on `Simulator.messageQueueC2S`, a `LinkedBlockingDeque` that is safe from any
  thread (`tsc/.../servlet/MessageQueue.java:18-36`, `Main.java:150-156`).
- On its next tick the simulator calls `OperationProcessor.process(key, data, simulator)` (`Simulator.java:490`).
  `process` first **round-trips the payload through JSON** (`tsc/.../servlet/OperationProcessor.java:122-130`):
  `UPDATE_DATA -> new UpdateDataRequest(jsonReader, simulator).update()`, `DELETE_DATA -> new DeleteDataRequest(json).delete(sim)`,
  `LIST_DATA -> new ListDataResponse(json, sim).list()`. The objects you send are therefore **copied**, and the copies are
  re-bound to the simulator's `Data`. Build them with any `Data` instance, for example `new org.mtr.core.data.ClientData()`
  (jar: public no-arg constructor, core-only, no client classes).
- MTR's own server-side helpers do exactly this. jar: `org.mtr.packet.PacketUpdateData.sendDirectlyToServerRail(ServerLevel, Rail)`
  (source `PacketUpdateData.java:61-63`) wraps `new UpdateDataRequest(new MinecraftClientData()).addRail(rail)` and goes
  through `runServerOutbound` -> `MTR.sendMessageC2S(UPDATE_DATA, ..., responseData -> broadcast to every player in that level)`
  (`mtr/fabric/.../packet/PacketRequestResponseBase.java:52-64`).

**Timing for our mod:** call it only after MTR's ServerStarted handler has run. Use a `ServerStartedEvent` listener at
`EventPriority.LOW`, or queue the work and drain it from `ServerTickEvent`. A guard such as "does
`MTR.sendMessageC2S` do anything yet" cannot be observed, because a drop is silent. Read `MTR.main` by reflection
(non-null) if a guard is needed.

## 2. Creating a Station

Class: `org.mtr.core.data.Station extends StationSchema extends AreaBase<Station, Platform> extends SimpleAreaBase extends
AreaBaseSchema extends NameColorDataBase` (jar). Schema: `tsc/buildSrc/src/main/resources/schema/data/station.json`,
`areaBase.json`, `nameColorDataBase.json`.

| Field | Type / default | Setter (jar) |
|---|---|---|
| `id` | `long`, **final**, default `new java.util.Random().nextLong()` (`nameColorDataBase.json`) | none. Can be set only by building the object from JSON. |
| `transportMode` | final. `new Station(Data)` uses `TransportMode.values()[0]` = `TRAIN` (`tsc/.../data/Station.java:28-30`) | none |
| `name` | String. **An empty name is "invalid" and is never saved** (`SimpleAreaBase.isValid()` = `!name.isEmpty()`, `tsc/.../data/SimpleAreaBase.java:36-38`. FileLoader writes only `isValid()` data, `tsc/.../simulation/FileLoader.java:136`) | `setName(String)` |
| `color` | int 0..0xFFFFFF | `setColor(int)` |
| `position1`, `position2` | `Position(long x, long y, long z)` corners | `setCorners(Position, Position)` |
| `zone1..zone3` | long | `setZone1/2/3(long)` |
| `exits` | list of `StationExit` | `getExits()` |

**Area semantics.** `inArea` tests **x, y and z** (`SimpleAreaBase.java:69-75` -> `Utilities.isBetween`, `tsc/.../tool/Utilities.java:73-77`).
The dashboard sets y to `Long.MIN_VALUE..Long.MAX_VALUE` (`mtr/fabric/.../screen/DashboardScreen.java:391`). Do the
same, or platform rails whose midpoint is outside the y range will not attach to the station. Platforms attach to the
**first** station whose area holds the platform's midpoint (`tsc/.../data/Data.java:276-288`), so overlapping
stations are ambiguous.

**Adding it.** This is exactly what the dashboard sends (`DashboardScreen.java:397,422`), sent from the server instead:

```java
ClientData d = new ClientData();
Station s = new Station(d);
s.setName("Deerford");                 // "|" separates languages in MTR names, e.g. "Deerford|Deerford"
s.setColor(0x3366CC);
s.setZone1(1);
s.setCorners(new Position(minX, Long.MIN_VALUE, minZ), new Position(maxX, Long.MAX_VALUE, maxZ));
MTR.sendMessageC2S(OperationProcessor.UPDATE_DATA, server, serverLevel,
        new UpdateDataRequest(d).addStation(s), response -> { /* server thread */ }, SerializedDataBase.class);
```

`UpdateDataRequest.update()` (`tsc/.../operation/UpdateDataRequest.java:117-154`) does the following:
- If no existing station has that **id** (`data.stationIdMap.get(id)`), it adds the station (`addNewData = true` for
  stations, line 120 and lines 169-177).
- If one exists, it calls `existing.updateData(json of new)`, which **overwrites the name, colour, corners and zones**
  (lines 178-189). That would clobber a player's later rename.
- It then calls `data.sync()`, which re-maps platforms to areas.

**Deterministic id (recommended for idempotency).** Serialize a configured station with
`Utilities.getJsonObjectFromData(s)` (jar: public static, returns a relocated gson `JsonObject`), replace `"id"` with a
stable long derived from (world seed, station key), and rebuild with `new Station(new JsonReader(json), d)` (jar:
`JsonReader(JsonElement)`, `Station(ReaderBase, Data)`). The field names follow the schema JSONs. **Unverified:** the
exact JSON key spellings of a serialized `Position` (`x`/`y`/`z` per `schema/data/position.json`). Read them from one
`getJsonObjectFromData` output before relying on them.

**Saving.** This is automatic. MTR calls `main.save()` every 30 s (`Init.java:242-246`, `AUTOSAVE_INTERVAL = 30000`) and
does a full save on stop (`Init.java:221-224`, `Main.stop`). Files are MessagePack, one file per object, under
`<world>/mtr/<namespace>/<path>/stations/<bucket>/<file>` (root `Init.java:184`, per dimension `Simulator.java:115`, per
type `FileLoader.java:38`). Platforms, rails and the other types sit in sibling folders.

**Client sync: nothing to send.** Clients poll the server every ~0.5 s while moving
(`InitClient.java:432-439`, `PacketRequestData` -> `GET_DATA`), and `DataRequest.getData` returns every station
whose area is within render distance (`tsc/.../operation/DataRequest.java:27-40`). The dashboard and system map read the
same simulator. If you want an immediate push, forward the `UpdateDataResponse` to that level's players with the jar's
`PacketUpdateData.sendDirectlyToClientDepotUpdate(ServerLevel, UpdateDataResponse)` (public static. Despite its name it
just sends any `UpdateDataResponse`, `PacketUpdateData.java:69-71`). This is optional.

## 3. Platforms

**A platform exists only while a platform-type `Rail` sits under it. Platforms cannot be pre-created on their own.**

- Creation: `Rail.checkOrCreateSavedRailAndUpdateTiltAngles` makes `new Platform(position1, position2, mode, data)` for
  any rail with `isPlatform` when no platform already spans both of its end positions (`tsc/.../data/Rail.java:371-376`).
  It is called for every rail in an `UPDATE_DATA` (`UpdateDataRequest.java:140`).
- Removal: on every `sync()` in the simulator, `platforms.removeIf(SavedRailBase::isInvalidSavedRail)`
  (`Data.java:92-95`). A platform whose rail is missing or is not a platform rail is deleted
  (`tsc/.../data/SavedRailBase.java:50-53`).
- In `UpdateDataRequest.update()`, **new** platforms are ignored: `addNewData = false` for platforms
  (`UpdateDataRequest.java:121`). So an `addPlatform` of an unknown id does nothing, and an `addPlatform` of an
  *existing* id updates it.

**What a player does, and what we would have to reproduce** (jar-era `st/ItemRailModifier.java:55-70,76-140`,
`st/BlockNode.java`):
1. Place two **rail node blocks**, `mtr:rail` (registered as `rail`, `org.mtr.block.BlockNode`. Block-state properties
   `facing`, `is_22_5`, `is_45`, `is_connected`, `waterlogged`). The node position **is** the rail end position
   (`MTR.blockPosToPosition`).
2. Connect them with `rail_connector_platform` (`st/Items.java:71`). This builds
   `Rail.newPlatformRail(posStart, angleStart, posEnd, angleEnd, Rail.Shape.QUADRATIC, 0, 0, 0,0,0,0,0,0,0,0,0,0,0, styles, TransportMode.TRAIN)`
   (jar signature: 2x(Position, Angle), Shape, double, long, 11 doubles, `ObjectArrayList<String>`, TransportMode),
   with styles `["default"]` (`PacketUpdateLastRailStyles.getDefaultStyles`, `CustomResourceLoader.DEFAULT_RAIL_ID = "default"`),
   sets both nodes `is_connected=true`, and sends `PacketUpdateData.sendDirectlyToServerRail(level, rail)`.
   Angles come from `Rail.getAngles(posStart, yawStart, posEnd, yawEnd)` (`Rail.java:476-482`). For a straight E-W
   platform, both node yaws are 90 (`BlockNode.getAngle`: facing=false -> 90).
3. The simulator creates the `Platform`, which attaches to the station whose area holds its midpoint. Its default name
   is `""`. The player opens the platform screen and sets a name/number, which sends `addPlatform(existing)` with `setName`.
4. Physical `mtr:platform*` blocks beside the track are separate. They are what trains open doors at
   (`org.mtr.block.PlatformHelper`).

**So, to pre-create numbered platforms we would have to:**
(a) place `mtr:rail` node blocks at each platform track's two ends (`is_connected=true`, the right facing), (b) send an
`UPDATE_DATA` with one `Rail.newPlatformRail(...)` per track, (c) read the created platform ids from the
`UpdateDataResponse` in the callback (`getPlatforms()` is package-private: serialize the response with
`Utilities.getJsonObjectFromData` and read `"platforms"[i].id` / positions), then (d) send a second `UPDATE_DATA` with
`addPlatform(p)` after `p.setName("1")`. Step (d) needs a `Platform` with that **existing id**, which again means
building it from JSON (`Platform(ReaderBase, Data)`).
Shortcut for (d): **unverified, not recommended.** In principle a deterministic platform could be created and named
in one go, but `update()` processes platforms before rails and drops unknown ones, so it takes two round trips.

**Conflicts if we pre-place rails.**
- An MTR rail is a simulator object, not blocks. It renders through MTR's renderer on top of whatever blocks are there.
  CityWorld's own track blocks (or Create track) under it would be drawn *as well*, and Create trains know nothing
  about MTR rails. The two systems never interact.
- If the player later lays MTR track into our node blocks, that works: a node can take more rails. If they break a node
  block, MTR deletes every rail at that node (`st/BlockNode.java:81-85`) and the platform goes with it.
- A rail with **no** node blocks under it is never cleaned up automatically, but the player cannot select or remove
  it with MTR's tools either (they act on node blocks). Never send rails without placing the nodes.
- A platform rail is a whole segment between two nodes. A 96-block platform as one rail is fine (MTR allows it). A
  platform rail with no connecting track is a dead end until the player lays track to the nodes.

**Recommendation:** create **stations only** (name, colour, zone, area). Then platforms appear, already inside the
right station, as soon as the player lays platform rail along our platform faces, and the player numbers them in
MTR's own screen. Pre-creating platforms is feasible (steps a-d) but it commits the world to MTR geometry, places
MTR blocks, needs two asynchronous round trips, and doubles up with our own track. Treat it as a later, opt-in
setting, and if done, place nodes **exactly** on our track centrelines at the platform ends.

## 4. Simpler or bulk paths

- **Commands:** `/mtr` has only `generatePath`, `clearVehicles`, `instantDeploy` (depots) and `restoreWorld`
  (`Init.java:124-169`). Nothing for stations.
- **Datapacks:** none. Stations are runtime data in `<world>/mtr`, not registries.
- **`schema/integration`:** contains one `response.json` (code/currentTime/text/version), an HTTP response envelope.
  It is not an import format.
- **OBA (`schema/oba`) and system-map (`schema/map`):** read-only HTTP output for the webserver (`Main.java:113-118`).
- **The webserver itself** (if enabled) exposes the same operation keys over HTTP. That would need the MTR port and an
  HTTP client: worse than calling `MTR.sendMessageC2S` in-process.
- **Writing MessagePack files into `<world>/mtr/...` before the server starts:** possible in principle (the loader
  reads any file there, `Simulator.java:115-127`), but it is fragile (bucketed file names, hash bookkeeping, DataFixer)
  and only works before load. Not recommended.

**So `UPDATE_DATA` through `MTR.sendMessageC2S` is the path.** One `UpdateDataRequest` can carry many stations:
call `addStation` repeatedly, which makes it a natural batch.

## 5. Pitfalls

- **Idempotency.** MTR keys everything by `id`. Re-sending a station with a *random* id creates a duplicate with the
  same name. Re-sending with the *same* id overwrites player edits. Do both of these:
  1. Use a **deterministic id** per CityWorld station (for example a 64-bit hash of seed, dimension and station grid key).
  2. Before adding, ask the simulator what exists: `MTR.sendMessageC2S(OperationProcessor.LIST_DATA, server, level,
     new ListDataResponse(new ClientData()), callback, SerializedDataBase.class)`. The response holds **all** stations
     and platforms of that dimension (`tsc/.../operation/ListDataResponse.java:57-66`). Skip ids that already exist, and
     also skip a station whose area already contains our rectangle's centre (a player made one by hand).
     Also keep our own "already offered to MTR" record (CityWorld SavedData), so a station the player deleted is not
     re-created on every start.
  The response objects in the callback are **live simulator objects** read from the server thread. MTR's own
  `runServerOutbound` does the same, so this is accepted practice, but read only ids, names and corners, and copy them
  out at once.
- **Do not use `GET_DATA` as a probe:** `DataRequest.getData` registers a `Client` for the request's UUID, and that
  client then receives vehicle and lift updates (`DataRequest.java:107-121`). A fake UUID would leak a phantom client.
- **Dimension:** the dimension index comes from the `Level` you pass. MTR knows only the levels that existed at
  ServerStarted (`Init.java:176-179`). A dimension created later has no simulator, and messages for it are dropped
  (`Main.java:150-156`, index -1).
- **Empty names are not saved** (section 2). Station names use `|` to separate languages, as in MTR's UI.
- **Order of arrival:** CityWorld plans stations at chunk generation (worker threads). Do not call MTR from worldgen.
  Collect station descriptors (thread-safe queue or SavedData) and flush them from the server thread on the next
  server tick, in batches. The simulator does the heavy work on its tick.
- **MTR shutdown guard:** MTR stops the server if TSC stops answering its time messages (`Init.java:203-206`). A huge
  `UPDATE_DATA` makes one simulator tick longer (each `update()` runs `data.sync()` once). Batches of a few dozen
  stations are fine. **Unmeasured.**
- **Optional dependency:** every MTR class (`org.mtr.MTR`, `org.mtr.core.*`, relocated fastutil/gson) must stay inside
  one compat class that is loaded only when `ModList.get().isLoaded("mtr")`. Compile against the jar as
  `compileOnly`, and expect breakage between betas: the core API has been stable, but the mod layer was renamed
  wholesale between 4.0 and 4.1.
- **Transport mode** is fixed at construction. `new Station(Data)` is TRAIN. A BOAT or CABLE_CAR station would need the
  JSON route (`transportMode` key). Platforms attach only to a station of the same mode (`Data.java:281`).

## Secondary: Create (create-1.21.1-6.0.10.jar)

Create's station is a `StationBlockEntity` placed **on a track**. Its name lives on a `GlobalStation`
(`public String name`, jar: `com.simibubi.create.content.trains.station.GlobalStation extends SingleBlockEntityEdgePoint`),
an edge point in a `TrackGraph` held by `GlobalRailwayManager`. The edge point exists only once the station block has
been placed against a track that already belongs to a graph. There is no free-standing station or area object to
create ahead of time, and no command or data hook for it. Pre-naming would mean generating real Create track **and**
the station block, then setting the name through the block entity or the `GlobalStation` after the graph exists
(normally done by `StationEditPacket`). That is technically possible but deep in Create internals, and only once
track is there. Not worth it for "player lays the track". The practical Create equivalent is a sign or nameplate in
the station building. (Inspected only at class level. Not traced in detail.)
