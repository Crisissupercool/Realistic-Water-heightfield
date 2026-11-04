**This is a modified version, not the original mod.** It is a fork of [Realistic Water](https://github.com/sskaffy/Realistic-Water) by **Skaffy**, the author of the original mod, distributed under the original license (kept in `LICENSE`).

Second prototype of the heightfield water: a multi-layer shallow-water simulation that runs on the GPU and covers large areas, for floods, rivers, waterfalls and lakes. Still an early test build: keep backups of worlds you try it in. This is the last build on Skaffy's 1.2.1 before merging his newer versions (Sodium and shader-pack support).

## Requirements
- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API 0.161.0+26.3, Java 25
- Graphics API set to **Vulkan** in Video Settings (restart after switching)
- Singleplayer (or the LAN host). Other players see the water but don't sync it.

## Trying it
- `/water flood [radius] [height]`: floods the area around you (default radius 12, 3 blocks high)
- Realistic Water Bucket (Creative inventory): pours heightfield water; the infinite bucket places a spring
- `/water hf capture [radius]`: converts the vanilla water around you (default 24) into heightfield water
- `/water hf release`: turns it back into vanilla water; `/water hf clear` removes it
- `/water hf stats`: volume, tiles, solver load
- `/water settings` and `/water set <name> <value>`: tuning

## New since prototype 1
- **Water runs instead of looking like gel:** momentum is carried with the flow, so floods race down slopes and spread from a waterfall's foot instead of piling up
- **Pressure:** water filled to a ceiling carries the weight of the water above it. A flooded tunnel or a sealed tank pushes hard out of any hole and rises up shafts to the same level
- **No more limits:** floods grow past chunk borders and run down into caves to the world's floor
- **Vanilla water is left alone:** a flood reaching a pond, river or the sea drains into it as a plume instead of standing on it or converting it
- **Waterfalls:** a pour that stops ends within one fall time, and a new pour falls from the top again; spray on cascades and steep slopes, and only near the camera
- **Underwater:** the current shows from below, spray above the surface is hidden, flooded caves look filled
- **Reflections:** no more line across the water at eye height; trees and banks reflect across the whole water; reflections no longer slide when you move or jump; no grey patches in waves; softer wake rings seen from above
- **Tanks:** water filled to the top of a box of glass or barriers is visible from outside
- **Streams:** steps in a stream foam, and the foam and ripples no longer seem to reset
- **Springs** fill to a vanilla source's height, so a box filled from its top row doesn't spill
- **Faster world join:** the water shaders are compiled once and kept on disk (game folder, realistic-water/spirv); the first start after an update still compiles them

## Known issues
- Waterfalls don't show through the water surface from below
- A column with more than 4 open spaces stacked in it treats the lowest ones as solid
- Fast water hitting an obstacle doesn't splash in 3D yet; sheets don't join at convex corners
- No ocean swell or GUI toggles yet
- Not based on Skaffy's 1.2.4+ yet (no Sodium or shader-pack support); that merge is next
- Compatibility with other mods hasn't been tested
