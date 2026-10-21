# Heightfield CPU tests

Checks `HfTopology` and a Java mirror of `hf_flux.comp` / `hf_depth.comp` without Minecraft or a GPU.
If you change the shader math, change the mirror in `HfCpuTest.java` the same way.

Run from the repository root with any JDK 21+:

```
mkdir -p build/hf-cpu-test
javac -d build/hf-cpu-test src/client/java/me/skaffy/client/water/HfTopology.java tools/hf-cpu-test/HfCpuTest.java
java -cp build/hf-cpu-test me.skaffy.client.water.HfCpuTest
```

It is deliberately not under `src/test`, because the Gradle test source set can't see the client classes.
