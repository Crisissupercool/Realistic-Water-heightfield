# Heightfield CPU tests

Checks `HfTopology`, `HfTileGrid` and Java mirrors of `hf_flux.comp` / `hf_depth.comp` without Minecraft or a GPU:
`HfCpuTest.Sim` is the dense solver, `HfTiledSim` the tiled one including HfDomain's per-frame scheduling (growth, sleeping).
If you change the shader math or the scheduling, change the mirrors the same way.

Run from the repository root with any JDK 21+:

```
mkdir -p build/hf-cpu-test
javac -d build/hf-cpu-test src/client/java/me/skaffy/client/water/HfTopology.java src/client/java/me/skaffy/client/water/HfTileGrid.java src/client/java/me/skaffy/client/water/HfBlocks.java tools/hf-cpu-test/*.java
java -cp build/hf-cpu-test me.skaffy.client.water.HfCpuTest
```

It is deliberately not under `src/test`, because the Gradle test source set can't see the client classes.
