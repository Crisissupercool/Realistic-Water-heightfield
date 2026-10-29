**This is a modified version, not the original mod.** It is a fork of [Realistic Water](https://github.com/sskaffy/Realistic-Water) by **Skaffy**, the author of the original mod, distributed under the original license (kept in `LICENSE`).

First prototype of the heightfield water: a multi-layer shallow-water simulation that runs on the GPU and covers large areas, for floods, rivers, waterfalls and lakes. It is an early test build. Expect rough edges, and keep backups of worlds you try it in.

## Requirements
- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API 0.161.0+26.3, Java 25
- Graphics API set to **Vulkan** in Video Settings (restart after switching)
- Singleplayer (or the LAN host). Other players see the water but don't sync it.

## Trying it
- `/water flood [radius] [height]`: floods the area around you (default radius 12, 3 blocks high)
- Realistic Water Bucket (Creative inventory): pours heightfield water
- `/water hf capture [radius]`: converts the vanilla water around you (default 24) into heightfield water
- `/water hf release`: turns it back into vanilla water; `/water hf clear` removes it
- `/water hf stats`: volume, tiles, solver load
- `/water settings` and `/water set <name> <value>`: tuning

## What's in it
- Water spreads, pools and fills rooms in layers (caves, overhangs, basements), with volume conserved through block edits
- Thin flows run smoothly over blocks and down steps. Shorelines are smooth (marching squares) instead of block steps
- Waterfalls fall as sheets with a real fall time, splash where they land and throw drops that carry water
- Water falling into trees rains on through the leaves; wake and foam behind obstacles, flow-mapped shading, an underwater view of the surface
- Boats float on the water column and ride rising water; entities are pushed by currents
- Water is saved with the world as Skaffy's realistic water blocks

## Known issues
- A strongly fed waterfall pool can look restless
- Fast water hitting an obstacle doesn't splash in 3D yet; sheets don't join at convex corners (dark slot at pillar corners)
- No ocean swell, GUI toggles or automatic conversion of vanilla water yet (planned for the next builds)
- Not based on Skaffy's 1.2.4 yet (no Sodium support)
- Compatibility with other mods hasn't been tested
