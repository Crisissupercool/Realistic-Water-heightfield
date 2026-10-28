# Multi-layer heightfield water: design

Status (2026-10-21): milestones 1 and 3, paged memory and block edits run in game (sections 10-13). The heightfield owning world water
(section 14) is written and passes the CPU tests, not yet run in game.

## Goal

Large-scale water (floods, rivers, lakes, dam breaks) that can run over an area of hundreds of blocks and that also handles
Minecraft's 3D structure: water flows through doorways, under bridges, into caves and basements, and fills rooms to the ceiling.

The existing FLIP solver (`WaterRegion`, `PressureSolver`) stays as it is. The heightfield is a second, independent solver that renders
through the existing water pipeline (`WaterRenderer`, `composite.frag`).

## 1. Core model: spans, not columns

A plain heightfield stores one water depth per (x, z) column. This design stores **up to L open spans per column** (L = 4 by default).

A span is one vertical run of non-solid space in a column:

```
floor  = top of the solid below it (blocks, float)
ceil   = bottom of the solid above it (blocks, float; +inf for the sky span)
h      = water depth in this span (blocks)
```

The water surface of a span is `floor + h`. A span is full when `h >= ceil - floor`.

Example column through a two-storey house standing on a cave:

```
 sky span        floor=72  ceil=+inf
 (roof)
 upper floor     floor=68  ceil=71
 (floor slab)
 ground floor    floor=64  ceil=67
 (terrain)
 cave            floor=50  ceil=55
```

### Horizontal cells

* Cell size: `hfCellsPerBlock` cells per block on x and z (default **2**, so a cell is 0.5 x 0.5 blocks).
* Vertical positions are floats. A span's floor and ceiling come from the sub-block voxelization in `SolidCache`, so slabs, stairs and
  similar blocks give fractional floors.

### Links between spans

Water moves between a span and the spans in the 4 neighboring columns **whose vertical ranges overlap** (the opening).

* `opening(A, B) = [max(floorA, floorB), min(ceilA, ceilB)]`, used only if it is at least `hfMinOpening` tall (default 0.25 blocks).
* Each span stores up to **2 links per direction** (8 in total). That covers a wall with both a doorway and a window, for example.
  If there are more candidates, the 2 with the largest openings are kept.
* Each link also stores its **reverse slot**, the index of the matching link in the neighbor's table. The depth pass reads inflows
  through it without searching.

The topology (spans and links) depends only on blocks, not on water. It is rebuilt only when blocks change.

## 2. Simulation: virtual pipes over the span graph

The pipe model (O'Brien and Hodgins 1995; Mei et al. 2007 for the GPU form) is used, with links instead of grid neighbors.
Each step runs 2 compute passes.

**Pass 1, `hf_flux.comp` (one thread per span):**

```
headA = floorA + hA                     // water surface height
for each link k to span B:
    headB = floorB + hB
    // only the part of the water that is inside the opening can move
    wetA  = clamp(headA, open.lo, open.hi) - open.lo
    wetB  = clamp(headB, open.lo, open.hi) - open.lo
    area  = cell * max(wetA, wetB)       // cross-section of the pipe
    f[k]  = max(0, f[k] * damping + dt * g * area * (headA - headB) / cell)
scale all f[k] so total outflow * dt <= volume in A     // never go below zero
```

**Pass 2, `hf_depth.comp` (one thread per span):**

```
inflow  = sum over links k of neighbor.f[reverse[k]]
outflow = sum over links k of f[k]
hA += dt * (inflow - outflow) / (cell * cell)
```

Velocity, which foam, sound and entity drag need, comes from the fluxes: `v = (f_east - f_west, f_south - f_north) / (cell * h)`.

### Full spans (rooms filling to the ceiling)

When a room is completely flooded, the water level is capped by the ceiling, but the water should still feel pressure from the
water higher up outside. This is handled with a **Preissmann slot**, the standard trick in sewer and pipe-network models:
`h` may exceed `ceil - floor`, but the excess counts with a tiny storage width (for example 1% of the cell area). So the head rises fast
and pushes water on, and almost no extra volume is stored. Rendering and block sync clamp the depth to `ceil - floor`.

### Stability

* Time step: `dt <= hfCfl * cell / (sqrt(g * h_max) + |v|_max)`. Substep like `WaterWorld` already does for FLIP
  (`maxStepsPerFrame`).
* Flux damping (`hfDamping`, around 0.995) and bed friction (`hfFriction`) remove the endless sloshing the pipe model is known for.
* Wet/dry fronts: spans with `h < hfDryDepth` (around 0.002 blocks) are treated as dry for rendering, but keep their volume, so mass is
  conserved.

## 3. Sparse tiles: making it large

* The world is split into **tiles of 16 x 16 blocks** (one chunk column), which is 32 x 32 columns at the default cell size.
* A tile is allocated only while it, or a neighbor, holds water. A GPU **tile pool** plus an indirection table
  (tile coordinate to pool slot) lets tiles come and go without moving the rest.
* **Sleeping:** a tile whose largest `|dh|` stays below `hfSleepEps` for 60 frames stops simulating until a neighbor or a block change
  wakes it. A calm lake then costs almost nothing, which is the main thing the FLIP solver can't do.
* Links across tile borders store a global span index (pool slot * spans per tile + local index), so the passes ignore tile borders.

### Rough cost (estimates, not measured)

Per span: floor, ceil, h, flux[8], links[8], reverse[8] in 4-byte values, about **108 bytes**.

| Scenario | Area (blocks) | Spans | Memory | Notes |
|---|---|---|---|---|
| Village flood | 256 x 256 | 262k columns x 4 = 1M | ~110 MB | all tiles awake |
| River valley | 1024 x 128 | 524k x 4 = 2.1M | ~225 MB | most tiles asleep |
| Same at 1 cell/block | 1024 x 128 | 131k x 4 = 0.5M | ~56 MB | chunkier surface |

Each pass reads and writes about 100 bytes per span. That makes about 1M spans x 2 passes x 2 steps per frame ~ 0.4 GB/frame of
memory traffic, roughly **1-2 ms** on a mid-range GPU. Most spans in a column are empty and can exit at the first instruction, so the
real figure should be lower. **Measure this in milestone 3 before relying on it.**

## 4. Topology builder

Runs on the CPU first, since that is easier to debug. It can move to the GPU later if rebuilds become a bottleneck.

1. For each column in a tile, take the voxel solidity from `SolidCache` (sub-block resolution `hfCellsPerBlock`) over the tile's
   Y range.
2. Collect runs of empty voxels from bottom to top. Each run is a span; drop runs shorter than `hfMinOpening`.
3. **Cap at L spans.** Keep the sky span, plus the L - 1 spans that are either wet already or closest to the sky span. Spans dropped
   this way behave as solid, and the builder logs a warning, so deep cave systems under a flood are the known limit of this design.
4. Build links by comparing every span with the spans of its 4 neighbor columns (at most 4 x 4 comparisons per direction).
5. Upload the changed tile.

**Block changes:** rebuild only the affected column and its 4 neighbors' links, then **remap water**:

* Old and new spans are matched by vertical overlap, and the water volume moves to the matching new span.
* Water displaced by a placed block goes to the span directly above. If there is none, it is spread over the neighbors, so volume is
  never deleted silently.
* A broken block merges two spans into one, and the water from both stays in the merged span.

## 5. Rendering through the existing pipeline

`WaterRenderer` already draws any mesh it is given (`verts` = vec4 position + packed normal, `quads` = 4 vertex indices,
`meshCounters` = indirect draw args). The heightfield gets its own mesher, `hf_mesh.comp`, that fills buffers in this exact format.
Shading (absorption, refraction, screen-space reflections, ripples, sun glints, foam compositing) is then reused unchanged.

**Important: the thickness pass needs a closed volume.** `mesh_thickness.frag` adds `-distance` for front faces and `+distance`
(clamped to the scene depth) for back faces. A mesh with only the top surface would give **zero thickness**, and the water would look
clear. So each wet span emits:

* the **top surface**: one quad per cell, with corner heights averaged over the linked wet neighbors so that the surface is smooth;
* the **bottom**: one quad at `floor`, back-facing (the scene-depth clamp hides it behind terrain anyway);
* **side walls**: at links where the neighbor is dry or lower, so the water looks correct from the side and through windows.

Side and bottom quads set the `wall` flag (`packNormal(..., true)`), which `mesh_front.frag` already uses to hide them when the camera is
underwater.

**Underwater detection** gets a small heightfield version of `probe.comp`: the camera is underwater if it is inside a span with
`floor < camY < floor + h`.

**Refactor (done in milestone 1):** `WaterRenderer.Body` now holds either a `WaterRegion` or an `HfDomain`
(`Body(region, heightfield, ubo)`, with the old 2-argument constructor kept). The mesh, thickness and composite passes read the
mesh buffers through `Body`; droplets and whitewater are skipped for heightfield bodies.

## 6. Gameplay sync

* **Blocks:** the existing `Fill` record goes from GPU to CPU to `WaterSyncPayload` and on to the server. `hf_fill.comp` writes the same
  per-block "fullness + speed" bytes from `h`, so vanilla swimming, drowning and boats work through the existing block sync.
* **Entities (later):** drag from the flux velocity at the entity's position, plus displacement (entities push water out of the cells
  they occupy). Needs a small readback around tracked entities.
* **Sources:** springs add inflow into their span. Buckets add a volume. New command `/water flood <radius> <height>` raises the water
  level over an area, for testing.

## 7. Code layout

New code lives in `me.skaffy.client.water` with an `Hf` prefix. A separate package would not work, since `SolidCache`,
`Material`'s helpers and the renderer internals are package-private, and widening them would mean more upstream diffs.

```
src/client/java/me/skaffy/client/water/
    HfTopology.java    CPU span and link builder (no Minecraft types, unit-testable)
    HfDomain.java      GPU buffers, flux/depth/mesh/probe dispatches, stats readback
    (milestone 3+)     HfTile / tile pool, sleeping; (milestone 4) water remap on block change
src/client/resources/assets/realistic-water/shaders/
    hf_common.glsl  hf_flux.comp  hf_depth.comp  hf_mesh.comp  hf_probe.comp   (hf_fill.comp in milestone 5)
tools/hf-cpu-test/   CPU tests: topology cases and a Java mirror of the flux and depth shaders
```

Settings in `WaterSettings` (change with `/water set`): `hfCellsPerBlock` (2), `hfLayers` (4), `hfMinOpening` (0.25),
`hfMaxDomainBlocks` (256), `hfCfl` (0.3), `hfDamping` (0.995), `hfFriction` (0.2), `hfMaxSpeed` (30), `hfRenderDepth` (0.01),
`hfMaxStepsPerFrame` (8). Topology settings apply to the next `/water flood`. Later milestones add `hfSleepEps` and a solver choice
for placed water.

## 8. Milestones

| # | Milestone | Exit test |
|---|---|---|
| 1 | Layered data structures, but only the sky span filled (L = 1 in practice). Flux, depth and mesh passes, rendering through the refactored `WaterRenderer`, `/water flood`. | Dam break in a flat valley: stable, and total volume stays within 0.1% over 10 minutes. |
| 2 | Topology builder with all L spans and links. | Water flows through a doorway into a house and fills the room, and a tunnel under a river stays dry until breached. |
| 3 | Sparse tile pool and sleeping. | 256 x 256-block flood at 60 fps on the dev GPU; a calm lake costs < 0.2 ms. |
| 4 | Block edits, water remap and Preissmann slot. | Breaking a dam wall releases the water, placing blocks in water conserves volume, and a basement fills to the ceiling. |
| 5 | Block sync and entity drag. | Player can swim in it, and boats float and drift with the current. |
| 6 | Waterfall spray through the existing whitewater particle path. | Water falling off a cliff shows a spray sheet and foam at the bottom. |

## 9. Open questions and risks

* **L = 4 may not be enough** for cave-riddled terrain. The builder logs every dropped span; measure how often it happens before
  raising L, since memory scales linearly with it.
* **Remap on block change** is the most likely source of lost or duplicated water. Add a debug command that prints total volume per
  tile.
* **Pipe-model oscillation** at sharp wet/dry fronts. If damping is not enough, fall back to a finite-volume (HLL) scheme for the
  flux pass only. The data layout stays the same.
* **Upstream merges:** the `FluidBody` refactor touches `WaterRenderer`, `WaterRegion` and `WaterWorld`. Keep it small and do it in a
  single commit so it is easy to reapply.
* **Which water uses which solver:** proposed default is heightfield for placed and spring water, and FLIP for `/water ball` and
  splashes. Needs a decision before milestone 5.
* **License:** this fork must keep Skaffy's license, credit Skaffy, link the original project and say clearly that it is a modified
  version (see `LICENSE`, "Fork conditions"). The README notice added with this document covers that.

## 10. Milestone 1: what exists

**Commands**

* `/water flood [radius] [height]` builds a heightfield domain around you (up to `hfMaxDomainBlocks`, default 256 x 256 blocks)
  and raises the water to your feet + `height` inside `radius`. On flat ground that is a cylinder that collapses like a dam break.
  Running it again replaces the previous domain.
* `/water hf stats` shows the domain size, span count, GPU memory, total water volume and its drift since the flood, max depth,
  max speed, and substeps per frame.
* `/water hf clear` removes it.

**How a frame runs:** `WaterWorld.recordHeightfield` reads last frame's stats (volume, max depth, max speed), picks the substep count
from the CFL limit, writes the UBO (shared shading fields via `writeShading`), records flux + depth passes per substep and the
underwater probe. `WaterRenderer` then builds the mesh (`hf_mesh.comp`) and draws it with the existing passes.

**Tested on the CPU** (`tools/hf-cpu-test`, a Java mirror of the two shader passes, run with plain `javac`/`java`):

| Case | Result |
|---|---|
| Spans for a two-storey house over a cave | sky 72+, upper 68-71, ground 64-67, cave 50-55; links symmetric |
| Span cap (6 gaps, L = 4) | 2 dropped per column, counted |
| Dam break, r = 6, 2 blocks high, 20 s | volume drift 0.0000%, spreads over the whole 32 x 32 domain and settles flat |
| Water outside a closed room with one doorway | flows in through the doorway, roof stays dry, no volume drift |
| Tunnel under a river | tunnel stays dry |

**Not covered yet:** running it in game. Milestone 1's exit test (dam break in a real world, volume within 0.1% over 10 minutes,
looks right) has to be done on a machine with Java 25 and a Vulkan GPU.

**Known limitations of this milestone**

* Block changes inside the domain are ignored until the next `/water flood` (milestone 4).
* The topology build runs on the main thread when you flood: expect a hitch of up to a second or two on a 256 x 256 domain.
* Top-surface normals are per cell (flat-shaded); the ripple detail normals hide most of it. Corners are averaged per span, so tiny
  cracks between neighboring cells are possible where neighbors pick different links.
* Water piles up against the domain edge (it is a wall).
* Not synced to blocks, so swimming and boats don't react to it yet (milestone 5).

## 11. First in-game results and the performance pass

Measured on a laptop with an RX 6500M at 1920×1200 (`/water flood 60` = 240×240-block domain, 921,600 spans):

| | fps | frame time |
|---|---|---|
| no water | 549 | 1.8 ms |
| flood, simulation paused | 97 | 10.3 ms |
| flood, simulating | 58 | 17.2 ms |

So about 8.5 ms to draw and 6.9 ms to simulate, while the water sat still. Volume drift stayed at 0.000%. Changes made in response:

- **Sleep:** `hf_depth.comp` now also reduces the fastest surface rise or fall (blocks/s). When that stays below `hfSleepRate` (0.002) for `hfSleepSeconds` (1 s), the solver stops stepping. Only a new flood wakes it until block edits exist (milestone 4). CPU tests: a dam break falls asleep after 24–37 s.
- **Mesh caching:** `hf_mesh.comp` only runs after the solver stepped (or `hfRenderDepth` changed); otherwise last frame's mesh is drawn again.
- **No bottom faces:** heightfield vertices carry an OPEN flag (bit 31 of the packed normal). For open faces `mesh_thickness.frag` measures every face from the scene depth, so an entry with no matching exit is closed by the terrain and entry/exit pairs still cancel. This halves the geometry and removes one full-screen layer of additive overdraw.
- **Visuals:** smooth per-corner normals (no facets), and the ripple detail now fades by grazing angle with a stricter footprint, which removed the moire rings.

Not yet done: half-resolution thickness/composite passes, and moving the topology build (236 ms for the radius-60 domain) off the main thread.

## 12. Milestone 3: sparse tiles (as built)

The dense rectangle is gone. Water lives in 16x16-block tiles (32x32 columns x 4 layers = 4,096 spans at the defaults), aligned with
chunks, in a fixed GPU pool of `hfMaxTiles` slots (256 by default, about 54 MB of solver data plus 31 MB of mesh).

* **`HfTileGrid`** (pure Java, shared with the CPU tests): tile coordinate to slot map, neighbor table, per-tile stats, sleep timers,
  growth requests and the per-frame dispatch set.
* **Topology per tile** is built with a one-block halo, so links across a tile border come out exactly as the neighbor computes them.
  Links are one byte (`targetLayer << 3 | reverseSlot`); the target column follows from the link's direction. This differs from
  section 3's plan (global span indices): nothing has to be patched when a neighbor tile appears later.
* **Dispatch set** = awake tiles plus their neighbors. Links into tiles outside the set act as walls, and the fluxes of tiles that leave
  the set are zeroed, so the volume stays exact while most tiles sleep.
* **Growth:** the depth pass flags water at a tile border where no tile exists yet; the CPU builds up to `hfMaxNewTilesPerFrame` (2)
  tiles per frame there. When the pool is full, the border stays a wall.
* **Minimum flow depth:** water thinner than `hfMinFlowDepth` (0.03 blocks) no longer flows out of a cell. Without it a flood on open
  flat ground spreads forever as an ever-thinner film and keeps allocating tiles.
* **Film sleeping:** tiles whose deepest water is under `hfFilmDepth` (0.1) may sleep while the surface still moves up to
  `hfFilmSleepRate` (0.02 blocks/s), because a creeping film keeps the strict 0.002 limit from ever being met.

CPU tests (`tools/hf-cpu-test`): the tiled solver gives bit-identical depths to the dense solver over 3x3 tiles with a room and a dam
break across tile borders; water grows from 1 tile into new ones with 0.0000% volume drift; on open flat ground every tile falls
asleep (after about 4 minutes for that test) with 0.0000% drift.

Not done yet: freeing tiles that dried out, building growth tiles off the main thread, per-tile mesh caching (the whole mesh is rebuilt
when any tile moves), and coarser far-away tiles for floods larger than the pool.

## 13. Paged tile memory and block edits (as built)

**Memory.** Measured layout before: every tile stored 4 layers for every column and full flux for every span, 52 B per span,
213 KB per tile whether it held a lake or nothing. Now:

| Per span | Before | Now |
|---|---|---|
| floor / ceil | 8 B (2 floats) | 4 B (2 x 16-bit sub-block steps, exact) |
| depth | 4 B | 4 B |
| links | 8 B (8 bytes) | 4 B (8 x 4 bits: target layer + which of the 2 reverse slots) |
| flux | 32 B, always | 32 B, only while the tile is simulated (separate flux pages) |

A page holds one layer of one tile (32 x 32 spans). A tile only gets pages for the layers it uses: open ground 1, the 3x3-tile
test with a two-storey room 13 pages for 9 tiles instead of 36. Result: a sleeping open-ground tile costs 12 KB (was 213 KB);
an awake one 44 KB. Defaults: 256 tiles, 512 span pages (6 MB), 384 flux pages (12.6 MB), 400k mesh quads (32 MB). The mesh is now
the biggest item; per-tile mesh caching and distance LOD are the next memory step.

**Block edits.** `ClientLevelMixin` already reports block updates; when the collision shape changes inside a heightfield tile (or
in the one-block halo of a neighbor), the tile is marked dirty. Chunk loads mark the tile on that chunk and its 4 neighbors.
Every frame, all dirty tiles are rebuilt together (so both sides of a border agree), on fresh span pages. `hf_remap.comp` then moves
each column's water from the old pages into the new spans: each old body of water goes whole into the new span it overlaps most,
or the nearest one if a block now fills it. The volume is exact; water squeezed into less room is under pressure and flows away.
Tiles over unloaded chunks are not rebuilt (they would read as solid).

CPU tests: the paged solver is still bit-identical to the dense one; open-ground tiles use 1 page each; sleeping tiles hold no
flux pages; breaking an 8-block gap in a dam at a tile border lets the water through with 0.00001 % drift; placing 32 blocks in a pool
raises the floor and puts the displaced water on top with no volume change.

**Follow-ups from the first block-edit test (2026-10-21).** A thin film (under `hfMinFlowDepth`) used to stop at the edge of a
hole; it now still drains into a neighbor whose water surface lies below the film's floor, so it only refuses to spread sideways
on level ground. Known visual issues, left for the rendering pass: the half-block cell grid and 16-block tiles are visible in the
surface; a sheet of water standing against walls near a breach (debug with `hfDebugMesh` and `/water hf column`).

## 14. The heightfield owns world water (as built)

Skaffy's realistic water blocks are the mod's water in the world: invisible to vanilla rendering, static (they never tick), not
replaceable by vanilla water, swimmable, and saved with the world. The heightfield now uses them as its saved and gameplay state
(`HfWorld`, setting `hfOwnsWater`):

* **Import:** when a chunk loads, its realistic water goes into a heightfield tile (built if missing) instead of particles; springs
  (infinite blocks) become fixed-level reservoir cells at the top of the block. Water the particle simulation owns stays with it.
  A tile that already exists is authoritative and is not re-imported.
* **Placed / picked-up water** (not our own writes, see the echo bookkeeping) is injected into or removed from the span at that block.
* **Write-back:** a tile's depth is read back when it falls asleep and every `hfSyncInterval` (1 s) while awake, at most
  `hfSyncTilesPerFrame` tiles per frame. `HfBlocks` turns each block column's spans into fill fractions and amounts 0..8 (with a 3/4-step
  hysteresis against flicker; films under 1/16 block write nothing), and only changed blocks are sent (`HfBlocksPayload`, mode WRITE).
* **Capture** (`/water hf capture [radius]`): vanilla water blocks in a circle become realistic water (mode CAPTURE) and heightfield
  water. Edge columns whose water continues outside become fixed-level reservoirs, so a lake connected to the sea cannot drain below
  sea level. Waterlogged blocks and plants (kelp, seagrass) are left vanilla.
* **Release** (`/water hf release`): the solver pauses, every tile is written back as vanilla water (mode RELEASE), then the heightfield
  closes. **Clear** (`/water hf clear`) removes the heightfield and the realistic water blocks in its tiles (springs stay).
* **Reservoirs:** `hf_reservoir.comp` runs after each depth pass on simulated tiles and sets a cell's depth so the surface sits at the
  reservoir level (springs too: they refill and also take back overshoot, like a vanilla source).
* **Height window:** a heightfield covers `hfWindowBelow` (48) below to `hfWindowAbove` (64) above where it was created; water outside is
  left as static blocks.

Block writes go through the integrated server (singleplayer or LAN host, operator check on the server side). Other clients only show
the heightfield. CPU tests: blocks -> spans -> blocks round trip is exact, a pool fills or drains to a fixed edge level, a spring fills
its basin to its top and stops, inject adds exactly depth x cell area.

**Fixes after the first reload test.** Water shrank or vanished after leaving and rejoining. Two causes: (1) tile growth created dry
tiles over neighbor chunks before those chunks were scanned, so their water was never imported and the block sync then deleted it;
growth now builds tiles from the blocks, and never over unloaded chunks. (2) The heightfield treated vanilla water as empty space,
so captured water leaked into the surrounding sea (invisibly, rendered under the vanilla water). Every fluid that is not realistic
water now counts as solid for the heightfield; capture opens exactly the cells it converts (and rebuilds tiles that existed before).
Edge reservoirs are not saved; after a reload the sea boundary is a wall, which holds the captured water at its level.

## 15. Rendering pass 1 and player currents (as built)

* **Falling sheets:** `hf_mesh.comp` emits a vertical sheet where a span pours over an edge (its outflow in that direction is above
  `hfCurtainMinFlow` and the water it flows into lies below its floor), from the lip down to where it lands, 0.06 blocks out from the
  edge. Sleeping tiles have no flux pages and draw none.
* **Surface height like vanilla:** the drawn surface is lowered by `hfSurfaceDrop` (1/9 block, scaled down in water under a block deep),
  so heightfield water meets vanilla water flush.
* **Still films hidden:** `hfRenderDepth` is now 0.035, just above `hfMinFlowDepth`: films that cannot move are not drawn.
* **Currents (player only):** `hf_sample.comp` samples the span at the player's feet each frame (surface, depth, velocity from the
  outflows through its faces); a few frames later the client tick pulls the player's horizontal velocity toward the water's,
  `hfCurrentDrag` (0.15) per tick when fully submerged, fading in with the current's speed (none in still water) and the submerged
  fraction. Flying and spectators are left alone. Other entities and boats come later (needs a sample per entity, batched).

## 16. Stirring, foam and falling sheets 2 (as built)

* **Bodies stir the water (player only for now):** `HfWorld.stir` puts the player's box and velocity (blocks/s, tick to tick) into
  the heightfield Globals' entity slots (`G.ent`, count in `G.caps.z`, the same 3 x vec4 layout the particle domains use), with
  `hfStirStrength` and a radial speed while the body crosses the surface (4x the displacement rate: the push is short and the ring
  spreads thin). `hfStir` (hf_common.glsl) gives each column the velocity the bodies push it at, weighted by distance (body radius
  plus 0.35 blocks), only where the body overlaps the water column and fading for a body over 1.5 blocks below the surface. The flux
  pass drags the outflow through each link facing that velocity toward speed x wetted face at 8/s, `q += (1 - e^(-8 dt)) * max(target - q, 0)`:
  water piles up ahead (bow wave), drains in behind (wake) and spreads as waves; volume stays exact because only fluxes change.
  A moving player in water keeps the tile under them awake. CPU numbers (`StirExp`-style runs, strength 1): wading at 2.5 b/s
  through 1.2 deep water makes a ~0.2 bow wave and a 0.02 wave 2.5 blocks to the side; a surface swimmer in deep water ~0.03;
  jumping in a ~0.015 ring at 3 blocks. A hard "at least the body's speed" push was tried first and shoved a block-high wall of
  water ahead at 4 b/s.
* **Foam:** one float per simulated span (0..1), stored on the flux pages twice (binding 15): the depth pass reads one half and
  writes the other, swapping each step, because it reads its neighbors' foam. It is carried with the water (upwind, the same
  fluxes as the depth), fades with `hfFoamLife` (2.5 s), and is renewed where water drops in from a span whose floor is above our
  surface (up to 0.9 at 1.5 blocks/s of inflow), where it runs faster than 4..9 b/s (up to 0.5), and where a body stirs it
  (1.5..4 b/s, up to 0.35). Pages are zeroed when a tile gets flux pages, and tiles that are not simulated have none, so sleeping
  water is clear. (First in-game test: with lower thresholds and a 2.5 s life a whole flood turned white, and the whitewater
  lace pattern read as a net; foam now shows as soft drifting value-noise patches whose coverage grows with the foam amount.)
* **Aeration in the mesh:** heightfield vertices now pack a 12 + 12 bit octahedral normal and 6 bits of aeration (OPEN set; the
  particle meshes keep `packNormal`). Tops average the foam of the joined spans at each corner (times `hfFoam`); sheets go from
  the lip's foam (at least 0.1) to 0.3 + 0.25 per block of drop at the bottom. `mesh_front.frag` adds aeration x 0.2 to the
  material id as 0..0.1 for surfaces and 0.12..0.22 for sheets (aerated wall faces), inside composite's 0.25 tolerance;
  `composite.frag` turns it into foam patches on surfaces and streaks scrolling down sheets with a bumped normal. Sheets also skip the thin-film rule that forced their normal up (which
  was what made them look like panes of glass).
* **Sheet shape:** three segments along the arc the water takes leaving the edge at its overflow speed (outflow / (cell x depth)),
  `y = top - drop s^2`, thrown out up to 0.45 blocks, with per-vertex normals along the arc. At the brink the corner height stays at
  70% of the water depth instead of dropping to the floor, so the surface no longer bulges up from the edge.

* **Fine ripples (`HfRipples`, `hf_ripple.comp`):** the first in-game test showed no visible waves: a surface swimmer in a deep lake
  moves the half-block solver by ~3 cm. Small waves now come from a separate 256 x 256 wave-equation field at 8 texels per block
  (32 x 32 blocks around the player, 2 x 512 KB), stepped at 60 Hz with waves at 1.5 b/s and 0.995 damping per step. It is
  anchored to the wrapped world and addressed modulo 256 with a world-texel stamp per texel, so the window follows the player
  without copies (stale texels read as calm). While the player is at the heightfield surface (from the gameplay sample), the
  texels under them relax toward a depression of 6 cm x speed/3 b/s, plus a slow bob when nearly still and a dip of up to 15 cm
  when crossing the surface fast; moving, bobbing and jumping in change it and send waves out (numpy runs: 2 b/s gives ~0.07
  slope 2 blocks to the side). `composite.frag` bends the normal of water within 0.6 blocks of that level by the field's slope
  x `hfRippleNormal` (2), fading with distance and toward the field edge. It moves no water and ignores shores (waves run under
  land and vanish). `hfRipples` scales the source, 0 turns it off.
* **View bobbing broke depth (fixed):** the captured level projection includes bobbing (a small rotation and shift), but every
  fragment shader unprojected scene depth at the screen centre (`invProj * (0, 0, d, 1)`), which is only exact for a pure
  perspective matrix. Off-centre the error was a few percent of the distance and swung with each bob, so water thickness, the
  refraction validity test and the reflection hit test all jittered while walking. `linearDepth(uv, d)` now unprojects at
  the pixel (composite, mesh/droplet thickness, whitewater).
* **Reflections and refraction:** SSR refines a crossing with 6 bisection steps and rejects crossings far behind the surface;
  refraction offsets by the normal's lean from vertical (flat water no longer shifts the bed) and is capped at 3% of the screen.
* **Cascades:** adjacent sheets over the same edge share their corner vertices (average landing height and throw), so a
  stepped slope shows one curtain per step instead of a row of arches; sheet whiteness scales with the outflow (trickles stay
  clearer); falling-water foam is weighted by drop height (full at 3 blocks/s x blocks), so 1-block steps barely foam.
* **Hiding the cell grid:** heightfield quads pass all four corner normals and aerations (flat) plus the position in the quad
  to `mesh_front.frag`, which blends them bilinearly; per-triangle interpolation left a diagonal crease in every half-block
  cell (four diamonds per block in shading and foam). Thin water between 0.008 and `hfRenderDepth` is drawn while it moves
  (outflow > 0.002 blocks^3/s on a simulated tile), so shallow streams down steps no longer drop out cell by cell; still films
  stay hidden. Still per cell: shoreline outlines follow the 0.5-block grid (smooth contours would be the next step).
* **View bobbing, the real cause:** `viewPosAt` rebuilt a pixel's view position by scaling one point unprojected at device
  depth 0.5, assuming the ray passes through the view-space origin. Bobbing is a shift inside the projection, so the ray
  starts ~0.05 blocks off the origin, and the depth-0.5 point lies ~0.1 blocks from the eye: direction, position, V and the
  world coordinates of ripples and foam swung with every bob. Rays are now rebuilt from two unprojected points, and V points
  at the true (bobbed) eye. (The per-pixel `linearDepth` fix was real but secondary.)
* **One continuous surface (no puzzle pieces):** `linked(a, dir, t)` = both wet and (columns overlap, or water flows between
  them over a drop up to 1.6 blocks), symmetric by construction. Streams over steps become sloped ramps instead of separate
  pieces with sheets; bigger drops keep falling sheets. A corner's height is the average over the part of its four-span loop
  linked to the span (closure over S-X-D-Z, so every sharing span finds the same set) plus shoreline samples from dry
  neighbors relative to that water; walls and separate surfaces add nothing. The old rule filled missing neighbors with each
  span's own height, which split shared corners wherever surfaces differ: a CPU mirror (`HfMeshMirror`) found 86 of 387
  shared edges cracked (up to 0.6 blocks) on a stepped stream; now 0 cracks and 0 slope mismatch on a stepped stream, a bumpy
  slope and a lake shore.
* **Reversed depth:** the game's depth buffer is reversed (near = 1, the renderer tests GREATER). The eye direction helper
  assumed depth grows with distance, so V pointed away from the eye and broke reflections everywhere; it now picks the nearer
  unprojected point by distance.
* **Block edits keep the flow:** `HfTileGrid.replace` used to free a tile's flux pages, so breaking one block stopped all flow in
  that 16x16 tile (and any border neighbors rebuilt with it) while untouched neighbors kept pushing: the water surged. A
  simulated tile now keeps its flux pages when it has one for every new layer, and `hf_remap.comp` moves each column's flux and
  foam with the water body that fills each new layer most. CPU check: a steady channel's total flux went 151 -> 1.25 across a
  rebuild before, 151 -> 151 now.
* **Splashes:** `HfWorld.splash` (each tick) detects the player crossing the surface faster than 2 b/s (from the gameplay
  sample) and opens a paraboloid crater, its water injected into a ring r..2r (volume exact): displaced volume
  0.4 x (speed / 5)^1.5 blocks^3 x `hfSplash` (cap 40), radius 0.5 + 0.35 sqrt(volume) (0.6..4), depth limited to 80% of the
  water there. Gravity turns it into a rebound jet and an expanding ring. CPU numbers in 4-deep water: 5 b/s (a 1-block jump)
  0.015 wave at 3 blocks; 20 b/s 0.13; 78 b/s (terminal speed) 0.79 at 3 blocks, 0.15 at 8, rebound jet 1.9 blocks.

## 17. Wake synthesis and less repetitive ripples (as built)

* **The ripple grid is gone.** A plain wave equation on a 1/8-block grid (HfRipples v1) moves every wavelength at the same speed,
  so a moving player only made a fading ring, and the grid looked blocky up close. Real wakes come from dispersion (long waves
  outrun short ones). A fitted 13x13 vertical-derivative kernel (iWave) was tried in numpy first: it cannot represent |k| at
  wake wavelengths (2.6-10 blocks for a swimmer at 2-4 b/s, i.e. 20-80 texels), relative error > 50%.
* **Instead, per-pixel Cauchy-Poisson synthesis:** while the player is at the surface, `HfRipples` records 20 points per second
  (wrapped world x, z, time, signed volume: 0.05 blocks^3 per (b/s) of horizontal speed up to 6, 0.12 per (b/s) of vertical
  speed, so bobbing pushes and draws water alternately) and splashes add one point with the impact's displaced volume (up to 4).
  Up to 160 points live ~2 x `hfWakeLife`. `composite.frag` sums, per water pixel within 48 blocks of the player and 0.6 blocks
  of the wake level, the deep-water ring wave of each point: eta = V k / (pi r) cos(g t^2 / 4r), k = g t^2 / 4r^2, enveloped by a
  0.8-block cut-off (exp(-(k/8)^2)), the source size (core 0.15 + 0.35 sqrt|V|, exp(-(k core)^2 / 2)), `hfWakeLife` (4 s), the
  leading edge, and a fade for waves shorter than ~4 pixels. Superposition gives a true Kelvin wake (cusp lines and transverse
  waves) and ring trains around splashes, sharp at any distance. Its slope (x `hfRippleNormal`, 1.5) bends reflections fully and
  refraction by half; crests (eta > 0) brighten slightly; churned water right behind the swimmer (points within ~0.5 blocks,
  fading over ~1 s) shows as foam (`hfWakeFoam`). Numpy check with the shader math: swimming at 3 b/s gives 7 deg typical,
  16 deg peak tilt along the wake arms; a 1.6 blocks^3 splash ~21 deg on its first ring after 2 s.
* **Detail ripples:** 12 waves (frequencies x1.31 apart) instead of 5, directions stepped by the golden angle within a spread
  widening for short waves, deep-water speeds, a static crest warp and two slow noise fields varying their strength, so the
  surface no longer shows the regular stripes of the old pattern (rms slope 0.24 vs 0.35 at the same `detailStrength`).
* **Foam, second pass:** churned-water foam came from every wake point, including bobbing, so treading water stacked a solid
  white disk; and the foam texture (soft value noise) looked blurry. Now each point carries its own foam amount: only horizontal
  speed churns (smoothstep 1.5..4.5 b/s, so wading and normal swimming stay nearly clear, sprint-swimming leaves a trail a few
  blocks long) plus splashes (half the displaced volume, up to 1.5, spread wider). Bobbing counts only as vertical motion around a
  1 s running average, so sinking slowly makes no waves. `foamTexture` (also used for aerated water) has crisp patch edges and
  round bubbles of random size from a jittered grid, with detail fading by pixel footprint. Crests only brighten (troughs no
  longer darken, which drew a dark ring around a bobbing player).

## 18. Flow-mapped shading and a visible wake (as built)

* **Water velocity reaches the shading:** `hf_mesh.comp` writes each vertex's horizontal water velocity (average over the corner's
  linked spans of outflow difference / (cell x depth), capped at 8 b/s) as packed halves into `vertFlow` (binding 16, 4 bytes per
  vertex); `mesh.vert` (compiled with `MESH_FLOW` for the front pass only, binding 4) reads it for heightfield vertices.
  `mesh_front.frag` packs heightfield aeration (6 bits), the sheet flag (1), flow direction (8) and speed (8, 0..8 b/s) into the
  mantissa of a material value in [0.125, 0.25) (exponent fixed at 2^-3), inside composite's 0.25 water tolerance; the particle
  meshes keep writing exactly 0.
* **Flow maps:** `composite.frag` carries the detail ripples and the foam along the current: two copies offset by flow x phase,
  restarting every 1.6 s half a cycle apart, blended by a triangle weight (the standard flow-map technique), so rivers and
  cascades move instead of showing a frozen pattern. Foam is stretched into streaks along fast water (x(1 + 0.35 speed)); faster
  water gets rougher ripples. Foam on aerated water shows at 0.7 x coverage 0.85 x aeration, bubbles subtler and only up close;
  falling-water foam is full at 6 (blocks/s x blocks), fast-flow foam capped at 0.35.
* **Wake visible from above:** seen steeply little is reflected, so slope alone barely shows. The wake now also lights the water
  like relief (slopes facing the sun scatter more light up, +-35%), its source is 2.4x stronger (0.12 blocks^3 per (b/s) per s),
  `hfRippleNormal` defaults to 2.5 and the slope is capped at 0.9. Numpy with the shader math: swimming at 2-5.6 b/s, the wake
  covers ~25% of the 17 x 12 blocks behind the player, median tilt 8 deg, 95th percentile ~30 deg, brightness change up to 35%.
* **Sheets joined to the brink:** a falling sheet now starts exactly at the brink edge (it moves out from the wall only as it
  falls: CURTAIN_OUT eased in over the first quarter, plus the throw), its normal turns from the top surface's (up) to the arc's
  over the first 30% of the fall, and it has 4 segments. A second sheet hangs 0.06 lower at 45% of the distance from the wall with
  half the aeration; the parallax between the two layers gives the fall depth. Sheet vertices store their outward direction with
  a falling speed (1.5 + 5 s blocks/s) in the flow channel, and composite.frag shades sheets in coordinates across the fall and
  down it (continuing the top surface's along-flow coordinate at the brink), with the same flow-mapped foam as the surface, so
  foam that rode the current to the edge keeps pouring down the sheet.

## 19. Falling sheets as closed ribbons, weir flow at the brink, edits that add a layer (as built)

* **Why sheets looked like flat cards:** the thickness pass treats every heightfield face as open (an entry face without a matching
  exit is closed by the terrain behind it). A single-faced sheet therefore counted all the air between itself and the cliff (or
  the pool below) as water, so it shaded as a tinted slab. Sheets are now closed thin ribbons: an outer surface, an inner surface
  facing the cliff and, at each free end, a cap; entry and exit cancel and the thickness is the sheet's own (0.012 blocks at
  least), so glassy water is see-through and foam reads as foam.
* **Sheet shape:** the outer surface leaves the water surface at the brink horizontally at the overflow speed v0
  (outflow / (cell x depth), 0.2..4 b/s) and falls freely, so the lip curls over with the radius real water has (~v0^2 / g)
  instead of a hard corner; its outward travel eases toward 0.42 blocks (x = R (1 - exp(-v0 t / R)), it lands in the next cell,
  where the solver puts the water). The inner surface takes the same path from the floor at the brink and lands at the same place,
  so the sheet starts as thick as the water at the edge and thins as it steepens. Six segments at fall-time fractions
  0, .05, .13, .26, .45, .7, 1 (dense at the lip). Offsetting the inner surface along the turning normal was tried and rejected:
  it folded for deep, slow pours (numpy-style check of both models).
* **Joined to the top surface:** the sheet replaces the side wall at its edge (the body continues into the ribbon: outer = top,
  inner = floor), its first row takes the top surface's corner heights, normals and foam, and free ends narrow by up to 0.1 as they
  fall and whiten first. Sheet foam whitens with the drop (1 - exp(-drop / 1.4)) toward 0.9 x body, where body comes from the
  outflow per width (v0 x depth at the edge), values both sheets at a shared end agree on.
* **Seamless texture over the lip:** sheet vertices carry 16 (d + 1) + speed and the drop in their flow word; `mesh_front.frag`
  packs the sheet's edge direction (2 bits), speed (6) and the drop up to 0.5 blocks (8) instead of the surface's flow angle and
  speed. `composite.frag` gives a sheet the top surface's foam coordinates unrolled over the edge (world xz + outward x drop)
  near the lip and its own coordinates (world xz - outward x y) lower down, crossfaded at 0.32..0.48 blocks; ripples and the
  streak bump fade in over the first 0.3 blocks of drop. At the lip the sheet is shaded exactly like the surface it continues.
* **Foam streaks without scrambling:** `flowFoam` used to rotate absolute world coordinates (thousands of blocks) into the flow
  direction, so any change of direction across the surface reshuffled the pattern into scratchy lines. Streaks now come from
  averaging the two coarse noise octaves over +-0.12 x speed blocks along the flow (contrast restored), split into `foamField`
  (pattern) and `foamShape` (threshold and bubbles); still-water foam is unchanged.
* **Weir flow at a brink (solver):** the flux pass drove water over an edge with the head difference down to the water below, so
  a 10-block drop pulled the edge cell empty every step: it held under 0.01 blocks, counted as dry, the stream's surface ended a
  cell early and the sheet flickered. Over a free overfall (the neighbor's surface below our floor) only the water above the edge
  drives the flow now (`headA - max(headB, floor)`), like a weir. In the waterfall test the edge holds a steady 0.065 blocks at
  0.086 blocks^3/s instead.
* **Block edits that add a layer:** breaking a block into a bank or hillside can open a pocket above or below the water, so the
  rebuilt tile needs a layer it had no flux page for; `HfTileGrid.replace` then dropped the whole tile's flow (flux 151 -> 1.25
  in the test: the surge again). It now takes fresh flux pages for the new layers (zeroed with their foam before the remap) and
  keeps the rest; it only starts over when no free pages are left.
* **Tests:** a tile that gains a layer keeps its flow (151.26 -> 151.26, still 150.9 a second later). `HfMeshMirror` mirrors
  curtainOf / curtainEnd / sheetPoint on a 5-block stream over a 10-block cliff: 10 cells form one curtain, 18 shared ends
  with identical vertices, free ends only at its 2 sides, the ribbon at least 0.012 thick, never inside the cliff, its inner
  surface only going down.
* **The water tower on block edits (root cause):** `HfWorld.voxels` counted every fluid that is not realistic water (vanilla
  water, waterlogged blocks, kelp, seagrass) as solid; only a capture passes the captured positions as open. Water the
  heightfield holds in cells that are still such blocks (kelp and seagrass in a captured lake, water the capture could not
  convert, vanilla water that seeped in; the block sync never overwrites them) was consistent until any edit rebuilt the tile:
  then those cells turned solid, the remap moved each column's water whole onto the nearest open span above them, and the lake
  stood as a tower over them for a frame before collapsing into sloshing waves. Such fluids now keep the current topology's
  openness (`HfTileGrid.openVoxels`): open where the heightfield already holds the space, solid where it never did, so it still
  does not flow into vanilla water. Test: a captured lake whose blocks stay vanilla water, a log broken on its shore: the old
  rule lifts it 64.80 -> 70.80, now it stays at 64.80. `/water hf stats` reports how many such blocks edits kept open.
* **Remap keeps water where it is:** the remap used to move each old water body whole into the one new span it overlapped most.
  A block placed into a lake splits the column, and the water below the block was moved over it: the surface there jumped by
  up to two blocks, standing the water up as a column that then collapsed. Now each new span takes the part of every old body
  that lies in its height range; water an old body held above its ceiling (pressure) goes with the span it overlaps most; water
  inside cells that are solid now is gone, as when a placed block replaces a water block (the client's inject for that water
  resolved after the remap, into the solid cell, so it never applied). A floor that drops (a block broken under water) keeps the
  depth, so the surface dips there and fills in from around. Tests: a block placed at the bed, mid-water and at the surface of a
  5.8-deep lake leaves its surface at 64.80 and removes exactly the water in its cell (1.0, 1.0, 0.8); a 4x4x2 patch removes
  the 25.56 in its cells and stands nothing up on top.
* **Edit probe (`hfEditProbe`, off by default; in game it showed no surface jump through edits once the remap kept water in place,
  only normal volume changes from flow into and out of the tile, which it no longer reports):** for each rebuilt tile the depth is read back just before the remap, just after it and
  12 frames later; when the surface anywhere in the tile rises by more than 0.4 blocks, chat shows where,
  at which stage (remap or solver), how many columns changed shape (and where), and the column's spans and depths before, after
  the remap and later. The CPU mirror reproduces neither the log-on-the-shore tower nor any jump through edits on several
  scenes (tile borders and corners, moving water, breaking and placing), so this is how the in-game case gets pinned down.

## 20. Spray (as built, milestone 6 part 1)

* **Upstream check:** sskaffy/realistic-water moved to branch `26.3-1.2.2` (15 commits since our fork base e29763e: a shader-pack
  system, a GUI toolkit, slime, a reshaped Globals block with shadow and light fields). The whitewater path this builds on
  (`ww_update.comp`, `whitewater.frag`) is unchanged there; `particle.vert` only moved the droplet culling to a compute pass, which
  the whitewater draw does not use. Nothing was merged: the Globals changes would collide with the heightfield's entity entries.
* **Particles:** `HfDomain.spray` holds two halves of 65,536 particles in the regions' whitewater layout (pos relative to the
  heightfield origin in blocks with life in w, velocity with the type in w: 0 droplet, 3 mist), so `whitewater.frag` /
  `particle.vert` draw them in the existing whitewater pass (drawIndirect with firstInstance selecting the current half).
  Mist is a new type: its own radius (`hfMistSize`, 0.35) and faint alpha (`hfMistAlpha`, 0.05) fading with life; regions never
  emit it. Bindings 17 (particles), 18 (counters and draw args), 19 (tile lookup: 64 x 64 tiles around the origin, uploaded with
  tileInfo) joined the shared heightfield layout.
* **Emission (`hf_mesh.comp` emitSpray, per falling sheet edge):** rates grow with the energy the water brings down, outflow x
  drop (faded in over 0.3..1.5 blocks of drop): splash droplets where it lands (170 per s per unit, thrown up and out over +-75 deg
  at 10..40% of the impact speed sqrt(2 g drop)), droplets peeling off the sheet (60, at the sheet's own velocity plus a push
  away from it) and mist at the foot (10, slow, 1.5..3 s). Emission covers the time the water moved this frame (none while
  paused or asleep), times `hfSpray`. For the 5-wide, 10-high test waterfall (0.09 blocks^3/s per cell): ~1,500 splash, 540
  sheet and 90 mist particles per second, ~1,300 alive, splash up to 1.3 blocks high and 2.5 out.
* **Update (`hf_spray_update.comp`):** droplets fly with gravity and 0.5/s drag and are gone when they hit the ground or fall
  back into the water (found through the tile lookup and the column's spans); mist slows at 2/s, lifts 0.4 b/s^2 and stays just
  above the water. Mode 1 throws a crown from a player's splash: 150 droplets per block^3 displaced (cap 4,000) from a ring of
  0.6 x the crater radius at 20..55% of the impact speed (capped at 12 b/s), 6% of them mist.

## 21. Splashes with water in the air, foam that reads as foam (as built)

* **Water drops:** a second particle set after the spray in the same buffer (two halves of 16,384; counters [2], [3], draw args
  at byte 32), vel.w holding each drop's radius as a multiple of `hfDropRadius` (0.07 blocks). Drawn by the regions' droplet
  passes (`particle.vert` with `HF_DROPS`: no density culling, per-drop radius; sphere impostors into the front and thickness
  passes), so the composite shades them as water (refraction, reflections, thickness), not white. Same update as the spray
  (0.15/s drag, gone in the water or on the ground).
* **Player splash:** besides the crater, ring wave and white droplets, a crown of drops from the cavity's rim (radius
  max(0.45, 0.55 x crater radius), 55..80 deg, 2.5..7 b/s, 100 per block^3 displaced, 40..900) and, for entries faster than
  6 b/s, a jet of drops up from the middle 0.18 + 0.03 sqrt(volume) s later (0.55 x impact speed up to 9 b/s, 35 per block^3,
  10..300). Bursts wait in a queue with their delay. Sizes: 1 block fall ~40 drops 0.3 high; 3 blocks 76 + a 0.9 jet; 10 blocks
  188 drops ~2 high + a ~3 jet; 30+ blocks ~2.4 crown and ~4 jet (capped). Waterfall feet also throw a few drops (12 per s per
  unit of energy).
* **Foam lace:** `foamShape` no longer puts round bubble dots on flat white (they read as a printed, stucco-like texture on
  sheets, cascades and the splash disk). Up close a patch is a lace: white walls between bubble cells (F2 - F1 of a jittered
  7-per-block cell pattern) over 30%-white cell interiors, thin at a patch's edge and filling in toward its middle; far away its
  average.
* **Sheets:** whiten slower and less (toward 0.7 x body over drop / 2.5, free ends +0.2) and show white in streaks with clearer
  gaps (two valueNoise streak layers down the fall, 3.5 and 1.3 per block across, gap the foam 0.35..1.25x; total capped at
  0.82); the streak bump is half as strong and coarser (the fine 7-per-block stripes looked like a comb).
* **Thin films:** foam fades out under 0.15 blocks of depth (`foamOf`): cascades over steps put white patches with holes on the
  terrain. **Mist:** fainter and softer (alpha 0.02, radius 0.5, exp(-4 r^2), 6 per s per unit of energy).

## 22. Foam and splash look, checked offline first (as built)

* **Preview tooling:** `scratchpad/preview/` ports composite.frag's hash, value noise, foam and sheet functions bit for bit to
  numpy and renders them to images, so a texture is judged before it ships (the lace of section 21 rendered there exactly as
  the cracked-tile pattern seen in game). Mirror any change to these functions there.
* **Foam:** organic patches of value-noise octaves on coordinates warped by slow noise (0.6 per block, +-0.45 blocks), feathered
  edges (wider inward), and up close bubbly holes of varied size (two noise scales, 13 and 29 per block) that are more and bigger
  at a patch's thin edge and fill in toward its middle. No cells, no dots. `foamField` returns the pattern and its warped
  coordinates; flow smearing still averages the two coarse octaves along the current.
* **Falling sheets:** below the first half block, foam torn into streaks: patches stretched four times down the fall plus thin
  strands (7 and 16 per block across, wiggling +-0.15 blocks) whose density follows the aeration (threshold 1 - 0.9 aer, aer up
  to 0.65 x body), half each, capped at 0.88. The pattern scrolls down at one speed for every sheet (5 b/s): a speed varying
  down the sheet would shear it more and more over time.
* **Drops:** shaded on their own (`shadeDrop`, material -0.2 from droplet_front.frag with HF_DROPS): the scene behind almost
  straight through, sky reflected toward the rim (Fresnel), the sun's glint and 25% white. Shaded like the lake they refracted
  the dirt behind them and got the wet-bed darkening: dark pebbles. Radius 0.045 (was 0.07); crown 150 per block^3 (60..1200),
  jet 50 (15..400). Mist emission is off (it read as bright puffs); its type and rendering stay.
* **Drops redone (same day):** as refracting spheres they looked like glass marbles: too big, and tinted by whatever the scene
  copy holds behind them (the lake bed, not the lake surface, which is drawn later; the wooden pillar). Splash drops are now
  drawn in the whitewater pass like the spray, as type 4 + size (the drop set's vel.w): a soft bright core (0.85 exp(-2.2 r^2)),
  radius 0.03 x 0.5..1.4, and the heightfield's spray and drops are motion-blurred: stretched along their screen-space velocity
  by the distance moved in 0.02 s (up to 7x), alpha / sqrt(stretch). The sphere path (HF_DROPS, shadeDrop) is gone. The
  particle regions' foam lace is no longer applied over heightfield water, where it cut holes into the spray flying in front.
  Region whitewater draws now push all 8 values (push constants persist between draws).
* **Drops crisp (same day):** the gaussian sprite and up to 7x stretch read as blur (`scratchpad/preview/drops.py` renders the
  sprite as whitewater pass + composite draw it, shipped vs candidate). Now a crisp disc (anti-aliased over ~1 px via fwidth),
  alpha 1.5, a bright core and a bluer rim; motion blur over 12 ms, at most 4x, alpha x stretch^-0.3; a drop is at most 12 px
  in radius and fades in over 2 blocks from the camera, so drops flying past do not smear across the view.
* **Drops up close (same day):** from a few blocks away a splash was a heap of equal white ovals ("rice"): every near drop hit
  the same 12 px cap and stretched 4x. Real splash drops are millimetres, so they stay specks even next to the camera. A drop
  now stops growing at `5 px x (size / 1.5)^1.5` (about 1 to 5 px), so a near splash keeps its mix of big and small drops.
  Drops wider than 1.5 px stretch less, down to at most 1.6x at 4 px; thin streaks only come from small, distant drops.
  The rim is less blue. `scratchpad/preview/drops7.py` projects a crown as the game does (1080p, 70 degree FOV) at 2.5 and
  12 blocks: the near splash reads as spray, and the far one is unchanged. Rejected in the preview: glassy drops with a dark
  lens rim or a bright Fresnel rim read as soap bubbles or coins, and long capsule streaks read as pills.
* **Water past the render distance (Oct 22):** the mesh pass drew every tile, so water stayed visible floating past the end of
  the terrain. `HfDomain.setView` now hides a tile when its chunk is outside vanilla's render-distance circle (vanilla's own
  test, one chunk of slack) or is not loaded on the client. The hidden flag lives in the spare `tileInfo[tile*4+1].w` slot, and
  `hf_mesh` skips those tiles, so they emit no spray either. Hidden tiles keep simulating; only drawing stops. The flags are
  recomputed every frame (one test per tile), and the mesh rebuilds only when one changes. `/water hf stats` reports the count.
* **Stats readout fixed (Oct 22):** a 4070 Ti test reported "+1,685,497% since start". Bucket water and new tiles already moved
  the baseline, but reservoirs (springs, open edges) add and remove water on the GPU every step and were counted as drift. Now
  `hf_reservoir.comp` sums its change into one int past the per-page stats, in fixed point (1/65536 block^3, rounded). The CPU
  reads it back with the stats, adds it to the baseline and clears it after each copy. The line now reads "solver drift X%,
  springs/edges +N". "Spans dropped" was also misleading: it only grew as tiles were added. It is now kept per tile, replaced on
  rebuilds and labelled as what it is. These are deep cave pockets below a column's 4 uppermost air gaps, treated as solid
  (HfTopology keeps the top ones). They are dry, so no water is lost, but water flooding down that far would meet a wall.
  Raising the cap past 4 needs the 2-bit layer field in the links and the 4-per-tile `tileInfo` layout widened in every shader.
* **Measured cost (4070 Ti, 4K, 48 tiles):** frame 3.1 ms, of which the mod's GPU time is 2.04 ms. composite 0.98, scene copies
  0.29, mesh build 0.28, front pass 0.24, solver 0.10, thickness 0.09, whitewater 0.05. The solver is cheap. Composite scales
  with how much of the screen is water. Its main cost is the screen-space reflection trace (48 march steps + 6 refinement steps,
  each a depth read). It is now skipped where the surface reflects under 3.5% of the light (Fresnel). There the traced scene
  could change the pixel by at most that fraction of (scene - sky), so the sky stands in. The trace fades in up to 7%, so it
  is skipped when the view meets the surface steeper than about 35 degrees below the horizon and fully kept below 27 degrees.
* **Pools sized up (Oct 22):** a 4070 Ti flood reached 166/256 tiles, 481/512 span pages and 384/384 flux pages. With flux full,
  a tile that should wake gets no pages and stands as a wall that frame, and with span pages full the flood stops growing. The
  pools are small (a span page is 12 KB and a flux page 32 KB at 2 cells per block), so the defaults are now 1024 tiles, 2048
  span pages and 1536 flux pages (about 87 MB). The mesh pass used to dispatch over the whole span pool, so a bigger pool would
  have cost every frame. Free span pages are now reused lowest first (a min-heap, not a FIFO), so the pages in use stay packed,
  and the mesh pass covers only up to `HfTileGrid.pageEnd()`, the highest page a tile owns.
* **Composite breakdown:** skipping the reflection trace barely changed composite per water pixel (1.60 ms at about 60% of a
  4K screen looking down, against 0.77 ms at about 25% looking along a river), so the trace is not the main cost.
  `hfCompositeOff` (bitmask: 1 reflection trace, 2 ripples, 4 foam, 8 wake) turns parts off to measure them with /water hf stats.
* **Leaving and coming back lost water (Oct 22):** a 4070 Ti test went 45,840 blocks out, kept simulating correctly while away
  (drift -0.003%), then came back at -44.8% drift, with stutters as the chunks streamed in. Every chunk load rebuilt its tile and
  the 4 next to it. A tile is built over a one-block halo, and an unloaded chunk reads as solid there. So a tile rebuilt while a
  neighbor chunk was still missing had no links toward that neighbor, while the neighbor (built from loaded terrain) still linked
  back. Water flowing out through the one-sided links was subtracted on one side and never added on the other.
  `HfCpuTest` "Chunks streaming back in" reproduces it: one blind rebuild, 819.2 -> 593.1 blocks in 2 s; the same rebuild with
  the neighbor loaded, 819.2 -> 819.2. Now no tile is built or rebuilt until the chunks of the tile and all 8 around it are loaded
  (`HfWorld.neighborhoodLoaded`); a deferred rebuild stays queued, and the old topology stays until then. A chunk load only
  rebuilds tiles flagged `blind` (built while a chunk around them was missing; only the /water flood and capture commands can do
  that now). That also removes the burst of CPU rebuilds, since tiles built from loaded terrain do not need one. A chunk whose
  water could not be imported yet goes into `pendingImports` and is rescanned when a chunk next to it loads.
* **Mesh pass at scale (Oct 22):** at 345-474 tiles (431 awake, kept moving by springs) the mesh pass cost 3.0-3.25 ms, against
  0.5 ms at 165 tiles: 2-3x the tiles for 6x the time, and 7x the solver. Every wet span reserved its quads with an `atomicAdd`
  on the one quad counter, hundreds of thousands per frame on a single address, and that contention grows faster than the work.
  The allocation is now one atomic per subgroup with a prefix sum over the active spans (the pattern `g2p.comp` already uses).
  The quad count is copied into the stats tail and shown as "quads N/cap", flagged FULL past `hfMaxQuads` (400k), because
  overflowing quads are simply not drawn. If the mesh pass is still expensive after this, the next step is a pre-pass that packs
  each span's surface, wet flag, velocity and foam once. Today every corner re-derives them from geo, depth, pageOwner, 8 flux
  values and foam, for up to 4 neighbors, 4 times per span. The stats also summarize the particle-sim regions (water it still
  owns, lava, sand), which the GPU timer's "particles" section covers.
* **Measured after the subgroup allocation:** mesh build 1.54 ms at 469 tiles (from 3.0-3.25 ms at 345-474) and 1.49 ms at 665
  simulated. The new quad readout then showed 434k and 494k quads against the 400k capacity, so 8-19% of the surface was not
  drawn (which also flattered that timing a little). The mesh buffers now grow by half once a mesh uses over 90% of them
  (`HfDomain.growMesh`, up to `MAX_QUADS` = 2M, 96 B per quad). Nothing is copied: the mesh is rebuilt in full the same frame,
  and the old buffers go through `Vk.destroyLater` because earlier dispatches of the frame still bind them.

## 23. First-use freeze, pool warning, entities in the water (as built, milestone 5 part 2)

* **First water froze 2-3 s:** every shader was compiled from GLSL by shaderc on the render thread the first time it was used.
  The first water frame built the renderer (6 graphics programs, 12 shaders), the sand programs and the 11 heightfield programs,
  about 30 compiles in one frame. `Shaders.prewarm` now compiles to SPIR-V on two low-priority background threads, starting at
  client init (`ShaderWarmup`: every water, sand and particle-sim shader with the defines its program uses). `Shaders.module`
  only creates the module when the SPIR-V is ready; a shader missing from the list is compiled on first use, as before. At
  world join (`WaterWorld.warm`) the renderers and the heightfield and particle-sim programs are built during the loading screen.
* **Pools:** growing the tile, span and flux pools live would mean copying depth, flux and both foam halves (their offsets
  depend on the pool size) and resizing the stats and readback rings in flight. That is left for the radius-replacement step,
  where far tiles should be evicted instead. For now /water hf stats warns POOL NEARLY FULL above 90%.
* **Entities sampled in one batch:** `hf_sample_many.comp` samples up to 128 entities a frame, nearest the player within
  `hfEntityRange` (48 blocks), skipping spectators and passengers. Entries are (tile, column, height) in a host-visible ring
  (aux binding); results are two vec4 each (as `hf_sample`) on binding 12, copied to a readback ring and read
  `Vk.FRAME_RING` frames later (`HfDomain.sampleEntities` / `takeEntitySamples`).
* **Currents carry entities (`HfWorld.carry`):** mobs are pulled toward the water's horizontal velocity by `hfEntityDrag`
  (0.15/tick) times how much of them is under water, plus `hfFloodPush` (0.35) times min(1, depth x speed^2 / 8). A plain drag in
  shallow water is cancelled by ground friction (0.546 a tick), so a knee-deep 4 b/s flood would only nudge a mob; with the
  flood term a 1-block-deep 4 b/s flood carries it at about a third of the water's speed. Items, boats and other non-living
  entities float with the surface current (`hfFloatDrag` 0.25) once the water reaches them. Mobs, items and unsteered boats are
  moved by the integrated server, so each client tick the samples go to its thread with `server.execute` and are applied there
  by entity id (singleplayer only for release 1). Players, passengers and anything a player rides are skipped there. The boat
  or mount the player steers is moved by this client, so it is pushed client-side like the player.
* **Entities stir the water:** up to `hfEntityStir` (16) entities, nearest first, that are in water and moving against it by
  more than 0.3 b/s (or crossing the surface) go into the stir slots next to the player, with the same plunge rule. Drifting with
  the current moves no water relative to it, so it does not take a slot. The flux pass loops over these bodies per span, so the
  cap bounds its cost.
* **Boats sank (`AbstractBoatMixin`, superseded in section 25):** when a boat's top is under the surface, `AbstractBoat.isUnderwater` asks
  `FluidState.isSource()`. For non-source water it returns UNDER_FLOWING_WATER, and `floatBoat` pushes the boat down each tick
  (vanilla: waterfalls sink boats). Realistic water is never a source block (a source is an infinite spring), so any boat that
  dipped under once, landing or riding a wave, kept sinking. A redirect makes realistic water count as a source there, so the
  boat gets UNDER_WATER and vanilla's buoyancy. Checked against the 26.3 bytecode (javap on the loom-mapped jar): one
  `isSource` call in `isUnderwater`.
* **Water that did not reach the ground for entities:** WRITE only replaced air and realistic water. Grass, ferns and flowers
  have no collision, so the heightfield fills them, but no block was written there. Over grassland the whole ground layer stayed
  dry for gameplay: mobs walked and zombies burned under drawn water. Replaceable blocks (`BlockState.canBeReplaced()`: grass,
  ferns, vines, a single snow layer; not torches, rails or redstone) now give way to the water as they do to vanilla water.
  They drop their loot, a tall plant's top half goes too, and the client write-back no longer skips them. No tile rebuild
  follows: both have no collision and the write is consumed as our own echo.

## 24. Window floor, fall time, turbulence foam, smooth shorelines (as built)

* **Invisible floor:** tiles covered `refY - 48 .. refY + 48` around the player when they were built. Water flowing down a
  long slope or a deep shaft hit the bottom of that window and stopped in the air (and boats there sank: below the window
  nothing is simulated, so no block is written). The window is now `min(playerY, sea level) - hfWindowBelow` (64) up to the
  build limit (`hfWindowAbove` -1). To keep the build cheap the topology scan starts at the `WORLD_SURFACE` heightmap
  (`Voxels.top`): above it a column is open sky, one run with no blocks to test. Sections with no realistic water
  (`maybeHas`) are skipped when depths are imported. Tile build (CPU bench, hilly 16x16 tile): 3.90 ms for the old window,
  7.70 ms for full height without the skip, 2.81 ms with it. The sky-skip build matches the full scan cell for cell (test).
* **Water reached the bottom of a fall at once:** the solver moves water between neighbouring spans each step, so a pour off
  a 30-block cliff appeared at the bottom the next frame. Inflow from a neighbour whose floor is more than 1.5 blocks above
  our surface now goes into the air first: per span, 3 floats (`Air`, binding 20) hold the volume in flight, how long the
  front has been falling and the mean drop. It lands after the fall time of a body with drag (terminal speed 12 b/s):
  1.5 blocks 0.56 s, 10 blocks 1.59 s, 30 blocks 3.34 s, 100 blocks 9.18 s, then at the rate it arrives. Volume in flight
  counts in the stats total and keeps the tile awake; a remap lands it at once. The mesh draws the falling sheet only down to
  where the front has reached (`hfFallDistance`), and splash, drops and mist start when it lands, at the drag-limited impact
  speed. CPU test: a 30-block drop lands after 3.38 s (expected 3.34 s), up to 149 blocks^3 in the air, volume exact.
* **Foam and spray where water is churned:** foam used to come only from the brink and the plunge. Each span now measures
  churn: inflow times the head it loses on the way in (surface drop plus the change in velocity head, (u_b^2 - u_a^2)/2g,
  clamped 0..4 blocks) per unit area. Calibrated on the CPU sim (p99): a still lake 0, a flood front 2.7-6, rapids down a
  slope 20-50, a hydraulic jump up to 93. Foam gains 0.85 x smoothstep(1.5, 12, churn), so fronts get a light edge and
  rapids and jumps go white (checked as foam maps: foam sits at fronts, rapids and the jump, none on still water). Above 8,
  churn throws spray, 0.12 droplets a second per unit over (at most 4 a step per span), along the current and upward.
* **Blocky spreading:** the surface met dry ground along cell edges, so a front advancing diagonally drew a half-block
  staircase. Where a corner touches flat dry ground (same floor, no bank or drop), it is now placed as in marching squares:
  `lo + (level - lo)(2w - 1)` with w the wet share of the four cells, so the waterline crosses the ground part way along the
  edge. Dry cells next to water get a shore quad whose corners are the wet neighbour's own corners (no crack), and corners no
  wet cell touches sit 0.25 under the ground; walls toward flat dry ground are no longer drawn. Banks, drops and steps keep
  the old rule. Mesh mirror on a 45 degree front: the waterline is 0.163 blocks RMS off the straight line (0.696 with cell
  edges), 0 shore corners that miss a wet cell's.

## 25. Boats that ride rising water, small streams that pour off whole edges (as built)

* **Boats still sank: the section 23 fix was wrong.** Checked in the 26.3 bytecode (`floatBoat`): vanilla's UNDER_WATER status
  does not float a boat, it pulls it down (gravity, then (vy + 0.01 x 0.04 / 0.65) x 0.75: about 2.4 blocks/s), and
  UNDER_FLOWING_WATER sinks it slowly; neither ever pushes it up. Vanilla only floats a boat (IN_WATER) on the surface of the
  block its bottom is in, which works for still lakes. On heightfield water a boat went under in two ways: the written top
  block is 8/9 high, so crossing a block boundary moves the boat-visible surface by 2/9 (8/9 to 1 + 1/9) at once, more than a
  floating boat's 0.2 block freeboard; and water rising faster than the boat (a flood) closes over it. Once under, nothing
  lifted it. The mixin now (1) never counts realistic water as over the boat (`isUnderwater`), and (2) floats the boat
  (`checkInWater`) on the column's surface: block amounts / 8, up to 3 blocks up from the block its bottom is in, lowered by
  1/9 like the drawn surface. A submerged boat then rises with vanilla's own buoyancy (at most about 5 blocks/s) and rides
  rising water. Mirror (`HfBoatMirror`, vanilla's boat code on blocks written by `HfBlocks.amountFor`): floods rising 0.5 and
  2 b/s and 0.3-block waves sank the boat before (bottom 9.5-12 blocks under), now it floats (bottom at most 0.83 under the
  surface); the mixin compiles against the real 26.3 classes.
* **A small stream poured off one corner of a block.** Two thresholds hid most of the water: sheets were drawn only above
  0.005 blocks^3/s per half-block cell, and thin moving films only above 0.002, so a stream that physically ran over several
  blocks of an edge (CPU: 5.5 blocks for 0.1 b^3/s) was drawn pouring off 2.5 of them, and a trickle not at all. Both are now
  0.0005, and trickles stay clear instead of 35% white (sheet foam fades in with outflow per width up to 0.02). Water under 3 cm
  could not spread sideways at all (`hfMinFlowDepth`), so a stream landing on a block top drained straight off the nearest
  edge; at 1 cm it spreads over the top first (a bucket on flat ground now covers about 100 blocks^2 before it stops, near
  vanilla's 7-block reach; settled films stay hidden under hfRenderDepth). Stream of 0.05 b^3/s on a 2x2 block top: 8 of 16
  edge cells drawn pouring and 9 of 16 top cells wet (were 2 and 3). The tiled flood test now spreads into 16 tiles (12) and
  sleeps after 271 s (242).
* **Glass wedges over steps.** Two thin films a block apart that exchange water were drawn as one ramp, a slanted sheet
  standing in the air over the edge. A ramp now needs water at least 0.35 x the drop deep on one side; thinner water pours
  over the step as a sheet down its face. Mesh mirror: still no cracks (staircase stream 39 ramps instead of 63).
* **Small streams moved in jerks.** A tile sleeps when its surface stops changing (film tiles, deepest water under 0.1, already
  below 0.02 b/s). A steady stream has a still surface, so a tile carrying only a thin stream counted as asleep while the water
  ran through it. An asleep tile does not wake its neighbors, so the tile downstream stayed off and the stream was dammed at the
  border; when a neighbor slept too, its flux pages went and with them its moving films and sheets, which vanished and popped
  back whenever a surge woke it. Running water now keeps its tile awake like water in the air: an outflow above 0.0005
  blocks^3/s (the thinnest film or sheet drawn) at more than 0.15 blocks/s (so a flood edge creeping over flat ground at a few
  cm/s still lets its tiles sleep). Stream of 0.2 b^3/s down terraces over 4 tiles: 434 tile-frames a minute with running water
  left unsimulated before, 0 now. The open-ground flood test now sleeps after 313 s over 42 tiles (271 s, 32).
