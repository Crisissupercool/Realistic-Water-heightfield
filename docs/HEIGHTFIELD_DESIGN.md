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
