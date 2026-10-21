# Multi-layer heightfield water: design

Status: draft v1 (2026-10-21). Nothing here is implemented yet.

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

**Refactor needed:** `WaterRenderer.Body` currently holds a `WaterRegion` and calls `r.mesher.build(...)`. It becomes an interface
(`FluidBody`: `buildMesh(cb)`, `verts()`, `quads()`, `meshCounters()`, `hasDroplets()`). `WaterRegion` and the new heightfield tiles
both implement it. This is the only change to existing shared code. Everything else is in new files.

## 6. Gameplay sync

* **Blocks:** the existing `Fill` record goes from GPU to CPU to `WaterSyncPayload` and on to the server. `hf_fill.comp` writes the same
  per-block "fullness + speed" bytes from `h`, so vanilla swimming, drowning and boats work through the existing block sync.
* **Entities (later):** drag from the flux velocity at the entity's position, plus displacement (entities push water out of the cells
  they occupy). Needs a small readback around tracked entities.
* **Sources:** springs add inflow into their span. Buckets add a volume. New command `/water flood <radius> <height>` raises the water
  level over an area, for testing.

## 7. Code layout

All new code goes in a new package, so merging upstream updates stays easy.

```
src/client/java/me/skaffy/client/water/hf/
    HfWorld.java       tiles, scheduling, sleeping, sources, commands glue
    HfTile.java        one 16x16-block tile, pool slot, dirty flags
    HfTopology.java    CPU span and link builder, water remap on block change
    HfSolver.java      records flux, depth and mesh dispatches
    HfMesher.java      buffers in the WaterRenderer mesh format
src/client/resources/assets/realistic-water/shaders/
    hf_common.glsl  hf_flux.comp  hf_depth.comp  hf_mesh.comp  hf_fill.comp  hf_probe.comp
```

New settings in `WaterSettings`: `hfCellsPerBlock`, `hfLayers`, `hfCfl`, `hfDamping`, `hfFriction`, `hfMinOpening`, `hfDryDepth`,
`hfSleepEps`, `hfMaxStepsPerFrame`, `waterMode` (`heightfield` | `flip`).

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
