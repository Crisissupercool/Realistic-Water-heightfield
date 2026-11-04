package me.skaffy.client.water;

import me.skaffy.ModSettings;
import me.skaffy.RealisticWater;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

public final class WaterSettings {
	public static int resolution = 6;
	public static int boxMargin = 6;

	public static float cfl = 2.0F;
	public static float maxDt = 1.0F / 40.0F;
	public static int maxStepsPerFrame = 4;
	public static int renderMaxStepsPerFrame = 64;
	public static int cgIterations = 5;
	public static int mgCoarseSweeps = 12;
	public static int solverBottomCells = 8192;
	public static int solverSmallCells = 24000;
	public static int extrapolationLayers = 4;
	public static float flipRatio = 0.99F;
	public static float volumeCorrection = 2.0F;
	public static float maxSpeed = 40.0F;
	public static float gravity = 9.81F;
	public static float blockGravity = 9.81F;
	public static String blockGravityPerBlock = "";

	public static boolean whitewater = true;
	public static float wwTrappedAirRate = 30.0F;
	public static float wwWaveCrestRate = 45.0F;
	public static float wwEnergyMin = 1.0F;
	public static float wwEnergyMax = 6.0F;
	public static float wwTrappedAirMin = 2.0F;
	public static float wwTrappedAirMax = 14.0F;
	public static float wwWaveCrestMin = 0.4F;
	public static float wwWaveCrestMax = 2.5F;
	public static float wwFoamLifeMin = 1.5F;
	public static float wwFoamLifeMax = 4.0F;
	public static float wwBubbleBuoyancy = 2.5F;
	public static float wwBubbleDrag = 0.6F;
	public static float wwSprayDrag = 0.4F;
	public static float wwSprayThreshold = 3.0F;
	public static float wwBubbleThreshold = 6.0F;
	public static int wwMaxPerParticle = 4;
	public static int wwCellLimit = 12;

	public static float filmFriction = 40.0F;
	public static float filmDepth = 0.08F;
	public static boolean sheetFill = false;
	public static boolean wallFill = true;
	public static float wallFillReach = 1.0F;
	public static float sheetSmoothing = 2.0F;
	public static float strandedTime = 1.5F;
	public static int strandedNeighbors = 12;

	public static int surfaceSubdivision = 2;
	public static float surfaceKernel = 3.0F;
	public static float surfaceRadius = 0.9F;
	public static float surfaceIso = 0.5F;
	public static float dropletThreshold = 0.35F;
	public static float nearCull = 0.05F;
	public static boolean surfaceGather = true;
	public static boolean sortParticles = true;
	public static boolean surfaceInteriorSkip = true;
	public static int surfaceInteriorCount = 4;

	public static float fluidRestSpeed = 0.15F;
	public static float fluidRestTime = 1.5F;
	public static int fluidRestWhitewater = 32;

	public static float particleRadius = 0.4F;
	public static float thicknessScale = 1.0F;
	public static float refraction = 0.15F;
	public static float absorbR = 0.45F;
	public static float absorbG = 0.12F;
	public static float absorbB = 0.07F;
	public static float scatterR = 0.03F;
	public static float scatterG = 0.08F;
	public static float scatterB = 0.11F;
	public static float scatterDensity = 0.5F;
	public static float underwaterFog = 0.1F;
	public static boolean underwaterMirror = false;
	public static float surfaceSmoothing = 0.6F;
	public static float detailStrength = 0.4F;
	public static float detailScale = 6.0F;
	public static float detailSpeed = 1.4F;
	public static float roughness = 0.06F;
	public static float specular = 6.0F;
	public static float wetDarkening = 0.3F;
	public static float filmShading = 0.25F;
	public static float wwRadius = 0.012F;
	public static float wwMaxPixels = 2.5F;
	public static float foamCoverage = 1.2F;
	public static float foamSize = 3.0F;
	public static float foamBubbleSize = 0.05F;
	public static float bubbleAlpha = 0.25F;
	public static float sprayAlpha = 0.35F;

	public static float sourceFill = 0.9F;
	public static boolean bucketMakesSource = false;

	public static boolean flowSounds = true;
	public static float flowSoundVolume = 1.0F;

	public static int sandResolution = 12;
	public static float sandFrictionAngle = 15.0F;
	public static float sandCohesion = 0.015F;
	public static float sandStiffness = 62.5F;
	public static float sandWallFriction = 0.5F;
	public static float sandCfl = 0.35F;
	public static int sandMaxStepsPerFrame = 16;
	public static float sandImpactDamping = 0.5F;
	public static float sandImpactRate = 2.0F;
	public static float sandImpactCompression = 0.15F;
	public static float sandRestSpeed = 0.05F;
	public static float sandRestTime = 0.15F;
	public static float sandWakeAccel = 0.5F;
	public static float sandSurfaceIso = 0.5F;
	public static float sandSurfaceSmoothing = 0.6F;
	public static float sandGrainSize = 0.01F;
	public static int sandGrainsPerParticle = 32;
	public static float sandGrainSpread = 0.5F;
	public static float sandGrainMinPixels = 0.7F;
	public static float sandBrightness = 1.1F;
	public static boolean sandSounds = true;

	public static int leafResolution = 8;
	public static int leavesPerBlock = 200;
	public static int leafCellCapacity = 1;
	public static float leafSize = 0.3125F;
	public static float leafFallSpeed = 1.2F;
	public static float leafSinkSpeed = 0.25F;
	public static float leafFlutter = 0.8F;
	public static float leafAirDrag = 1.2F;
	public static float leafTumbleRate = 5.0F;
	public static float leafGroundFriction = 10.0F;
	public static int leafRepose = 2;
	public static float leafShuffleSpeed = 1.5F;
	public static float leafRestSpeed = 0.06F;
	public static float leafRestTime = 0.5F;
	public static float leafWakeSpeed = 0.3F;
	public static float leafKick = 1.4F;
	public static float leafBurnTime = 0.6F;
	public static float leafWindStrength = 2.5F;
	public static float leafWindLift = 0.5F;
	public static float leafMaxSpeed = 12.0F;
	public static int leafMaxStepsPerFrame = 8;
	public static float leafBrightness = 1.0F;
	public static boolean leafSounds = true;

	public static float waveMakerRate = 4.0F;
	public static float waveMakerSpeed = 1.0F;

	public static int lavaResolution = 6;
	public static float lavaFlipRatio = 0.9993F;
	public static float lavaMaxSpeed = 5.0F;
	public static float lavaFilmFriction = 140.0F;
	public static float lavaStrandedTime = 5.0F;
	public static int lavaStrandedNeighbors = 16;
	public static float lavaFilmDepth = 0.05F;
	public static float lavaRefraction = 0.04F;
	public static float lavaAbsorbR = 2.8F;
	public static float lavaAbsorbG = 5.5F;
	public static float lavaAbsorbB = 8.0F;
	public static float lavaScatterR = 1.2F;
	public static float lavaScatterG = 0.35F;
	public static float lavaScatterB = 0.05F;
	public static float lavaScatterDensity = 2.5F;
	public static float lavaUnderwaterFog = 0.35F;
	public static float lavaSurfaceSmoothing = 0.75F;
	public static float lavaDetailStrength = 0.25F;
	public static float lavaRoughness = 0.35F;
	public static float lavaSpecular = 2.5F;
	public static boolean lavaFlowSounds = true;
	public static boolean lavaIgniteFlammables = true;
	public static boolean lavaWaterMixing = true;
	public static boolean vanillaWaterExchange = true;
	public static boolean grabBodies = true;
	public static float grabStrength = 8000.0F;
	public static boolean opGrabbing = false;
	public static boolean grabCarrying = false;
	public static boolean realisticKelp = true;
	public static int kelpRadius = 48;
	public static float kelpSway = 0.6F;
	public static float kelpBuoyancy = 5.0F;
	public static float kelpWaterDrag = 3.0F;
	public static float kelpStiffness = 0.08F;
	public static boolean kelpSelfCollision = true;
	public static float lavaMixLavaMin = 0.06F;
	public static float lavaMixWaterMin = 0.03F;

	public static int slimeResolution = 4;
	public static float slimeFlipRatio = 0.9F;
	public static float slimeMaxSpeed = 3.0F;
	public static float slimeVolumeCorrection = 6.0F;
	public static float slimeViscosity = 1.5F;
	public static int slimeViscosityIterations = 4;
	public static float slimeAdhesion = 18.0F;
	public static float slimeEntityStick = 0.85F;
	public static float slimeRestSpeed = 0.6F;
	public static float slimeRestTime = 0.1F;
	public static float slimeWakeSpeed = 1.5F;
	public static float slimeFilmFriction = 220.0F;
	public static float slimeFilmDepth = 0.15F;
	public static float slimeStrandedTime = 8.0F;
	public static int slimeStrandedNeighbors = 4;
	public static float slimeOpacity = 0.85F;
	public static float slimeBrightness = 1.0F;
	public static float slimeSurfaceSmoothing = 0.85F;
	public static float slimeInsideOpacity = 0.92F;
	public static float slimeFogDistance = 0.5F;
	public static boolean slimeSounds = true;

	public static boolean tntReactions = true;
	public static float tntBlastSpeed = 14.0F;
	public static float tntBlastReach = 2.0F;
	public static float tntUpwardBias = 0.35F;
	public static float tntScatter = 0.25F;

	public static boolean vitrailInsidePack = true;
	public static float packWaterVisibility = 0.5F;
	public static float packWaterTint = 0.5F;
	public static boolean packShadows = true;
	public static float packShadowStrength = 0.55F;
	public static boolean packShadowPerLeaf = true;
	public static int packShadowDetail = 4;
	public static boolean packDrawMaterials = true;

	public static float packWaterOpacity = 0.25F;
	public static boolean waterSeaLook = false;
	public static boolean debugWaterLayers = false;

	public static boolean realisticRain = true;
	public static int rainRadius = 32;
	public static float rainDensity = 9.0F;
	public static float rainFallSpeed = 9.0F;
	public static float rainStreakTime = 0.03F;
	public static float rainOpacity = 0.3F;
	public static float rainWind = 1.5F;
	public static float rainSplashDroplets = 3.0F;
	public static float rainKick = 0.6F;
	public static float rainLavaSteam = 0.05F;
	public static float rainFeed = 0.02F;

	public static boolean realisticOcean = false;
	public static boolean oceanPackDrawsSea = true;
	public static int oceanResolution = 3;
	public static int oceanSimRadius = 3;
	public static int oceanSimDepth = 3;
	public static boolean oceanSimUnderwater = false;
	public static int oceanSimHeadroom = 2;
	public static float oceanSettleTime = 1.0F;
	public static float oceanSnapDeviation = 0.8F;
	public static float oceanWaveHeight = 1.0F;
	public static int oceanServerRadius = 32;
	public static float oceanEdgeBand = 1.5F;
	public static float oceanEdgePull = 6.0F;
	public static float oceanEdgeRefill = 2.0F;
	public static float oceanEdgeDrain = 6.0F;
	public static float oceanCalming = 0.4F;
	public static float oceanThicknessScale = 1.0F;
	public static float oceanSurfaceOpacity = 0.45F;
	public static float oceanRefraction = 0.15F;
	public static float oceanAbsorbR = 0.14F;
	public static float oceanAbsorbG = 0.05F;
	public static float oceanAbsorbB = 0.035F;
	public static float oceanScatterR = 0.16F;
	public static float oceanScatterG = 0.38F;
	public static float oceanScatterB = 0.75F;
	public static float oceanScatterDensity = 0.12F;
	public static float oceanUnderwaterFog = 0.15F;
	public static float oceanDetailStrength = 0.4F;
	public static float oceanRoughness = 0.06F;
	public static float oceanSpecular = 6.0F;
	public static float oceanFlipRatio = 0.99F;
	public static boolean oceanWhitewater = true;
	public static float oceanWhitecaps = 1.0F;
	public static float oceanWhitecapStart = 0.6F;
	public static int oceanWhitecapRadius = 64;
	public static float packFoamSize = 0.12F;

	public static boolean waterTemperature = true;
	public static float waterHeatTimeScale = 1.0F;
	public static float waterMixing = 5.0e-4F;
	public static float airHeatTransfer = 15.0F;
	public static float iceHeatTransfer = 300.0F;
	public static float magmaWaterTemperature = 90.0F;
	public static float magmaHeatReach = 3.0F;
	public static float magmaHeatTime = 2.0F;
	public static float lavaBoilTime = 0.15F;
	public static float fireWaterTemperature = 120.0F;
	public static float fireHeatTime = 12.0F;
	public static float furnaceWaterTemperature = 80.0F;
	public static float furnaceHeatTime = 120.0F;
	public static float boilChurn = 6.0F;
	public static float boilSteam = 1.0F;
	public static float steamWispTemperature = 60.0F;
	public static float boilingDamage = 1.0F;
	public static boolean boilingSounds = true;
	public static float boilingSoundVolume = 0.4F;
	public static float boilBubbleSize = 0.12F;
	public static float boilBubbleRise = 1.0F;
	public static float boilBubbleRate = 10.0F;
	public static float boilClingTemperature = 70.0F;

	public static int steamResolution = 4;
	public static float steamParticleSize = 0.08F;
	public static float steamExpansion = 6.0F;
	public static float steamLifetime = 8.0F;
	public static float steamBuoyancy = 1.2F;
	public static float steamDrag = 1.0F;
	public static float steamSwirl = 0.6F;
	public static float steamBurst = 1.2F;
	public static float steamEntityPush = 0.6F;
	public static float steamOpacity = 0.35F;
	public static float steamDarkShade = 0.45F;
	public static float steamLightShade = 0.95F;

	// Heightfield solver (docs/HEIGHTFIELD_DESIGN.md). Topology settings apply to the next /water flood.
	public static int hfCellsPerBlock = 2;
	public static int hfLayers = 4;
	public static float hfMinOpening = 0.25F;
	/** Tiles of 16x16 blocks; with hfMaxPages and hfMaxFluxPages read when a flood starts. */
	public static int hfMaxTiles = 2048;
	/** Span pages: one per used layer of a tile, about 36 B per span with air, foliage and deposits (36 KB per page at 2 cells per block). */
	public static int hfMaxPages = 4096;
	/**
	 * Flux pages, only held by tiles being simulated (one per layer): 48 B per span with foam and momentum (48 KB per page). A flood
	 * cascading down a mountain kept about 450 tiles awake through caves and overhangs and held all 1536: water poured elsewhere
	 * then could not move and stood at its tile borders as walls (and was never written back, so not swimmable). With 4096 the
	 * pools take about 490 MB.
	 */
	public static int hfMaxFluxPages = 4096;
	/** Quads in the shared heightfield surface mesh (80 B each). */
	public static int hfMaxQuads = 400_000;
	/** The heightfield owns realistic water: chunk loads, placed water and springs go to it instead of the particle simulation. */
	public static boolean hfOwnsWater = true;
	/** Write the heightfield's water back into realistic water blocks (swimming, saving). Needs singleplayer or hosting. */
	public static boolean hfSyncBlocks = true;
	/** Seconds between block syncs of a tile while its water moves; settled tiles sync once when they fall asleep. */
	public static float hfSyncInterval = 1.0F;
	public static int hfSyncTilesPerFrame = 2;
	/**
	 * How far a new heightfield reaches below the player or sea level, whichever is lower (below it everything is solid).
	 * Default: down to the world's floor. It was 64 (Y -1 with sea level 63), so a flood running from a mountain into caves froze,
	 * invisible and not swimmable, once it went below that ("outside the heightfield's height window").
	 */
	public static int hfWindowBelow = 1024;
	/** Blocks above the player the heightfield reaches; -1: up to the build limit (the sky over the terrain is skipped cheaply). */
	public static int hfWindowAbove = -1;
	/** Debug: 0 normal, 1 draw only water tops (no side walls), 2 draw only side walls. */
	public static int hfDebugMesh = 0;
	/** Turns parts of the water shading off to measure their cost (/water hf stats, composite): 1 reflection trace, 2 ripples, 4 foam, 8 wake. Add to combine.
	 * Diagnostics: 32 shows only the light coming through the water, 64 only the reflection. */
	public static int hfCompositeOff = 0;
	public static int hfMaxNewTilesPerFrame = 2;
	public static float hfCfl = 0.3F;
	/** Linear drag on the flow (per 1/60 s and per second). Low now: bed friction (hfManning) holds back thin, fast water instead. */
	public static float hfDamping = 0.998F;
	public static float hfFriction = 0.05F;
	public static float hfMaxSpeed = 30.0F;
	/**
	 * Manning bed roughness (s/m^(1/3), 1 block = 1 m): thin fast water is held back by the ground, deep water barely. Real ground
	 * is about 0.025-0.035; 0 turns it off.
	 */
	public static float hfManning = 0.02F;
	/** Share of the vertical momentum of water landing from a fall or a step that turns into flow spreading out from where it lands. */
	public static float hfImpactSpread = 0.5F;
	/** Water thinner than this is not drawn: still films (thinner than hfMinFlowDepth) stay invisible instead of shimmering squares. */
	public static float hfRenderDepth = 0.035F;
	/** Lowers the drawn surface like vanilla's 8/9-high water (blocks, for water at least a block deep). */
	public static float hfSurfaceDrop = 0.111F;
	/** How strongly moving heightfield water pulls the player along (fraction of the velocity difference per tick, when fully submerged). */
	public static float hfCurrentDrag = 0.15F;
	/** Moving heightfield water carries mobs, items and boats (applied on the integrated server; your own boat on this client). */
	public static boolean hfEntityCurrents = true;
	/** Per-tick pull of a mob toward the water's velocity, times how much of it is under water. */
	public static float hfEntityDrag = 0.15F;
	/** Extra pull on mobs growing with depth x speed^2 (blocks^3/s^2 / 8, capped at 1): fast floods knock mobs off their feet. */
	public static float hfFloodPush = 0.35F;
	/** Per-tick pull of floating things (items, boats, anything not alive) toward the surface current. */
	public static float hfFloatDrag = 0.25F;
	/** Entities sampled per frame (nearest the player first, at most 128) and how far from the player, in blocks. */
	public static int hfEntitySamples = 128;
	public static float hfEntityRange = 48.0F;
	/** Entities in water, moving against it or crossing the surface, that stir it like the player (waves, wakes), nearest first. */
	public static int hfEntityStir = 16;
	/**
	 * Outflow over an edge (blocks^3/s) above which a falling sheet is drawn. Low enough for a trickle (0.0005 is half a litre a
	 * second at 1 block = 1 m): at 0.005 a small stream was drawn pouring off one corner of a block while the water also running
	 * over the rest of the edge was not drawn at all.
	 */
	public static float hfCurtainMinFlow = 0.0005F;
	/** How hard the player pushes heightfield water when moving through it (bow wave, wake, rings when jumping in); 0 turns it off. */
	public static float hfStirStrength = 1.0F;
	/** How strongly the player makes its wake and splash rings on heightfield water (swimming, wading, bobbing, jumping in); 0 off. */
	public static float hfRipples = 1.0F;
	/** How much the wake bends the surface when shading (multiplies its slope). */
	public static float hfRippleNormal = 2.5F;
	/** Seconds the wake's waves take to fade to about a third. */
	public static float hfWakeLife = 4.0F;
	/** How white the churned water right behind a swimmer shows (0 off). */
	public static float hfWakeFoam = 1.0F;
	/** How big a crater and ring wave a body hitting heightfield water makes (scales the displaced volume); 0 turns splashes off. */
	public static float hfSplash = 1.0F;
	/** How white foam and falling water show on heightfield water (0 off, 1 normal). */
	public static float hfFoam = 1.0F;
	/** Seconds foam takes to fade to about a third once nothing churns the water any more. */
	public static float hfFoamLife = 1.5F;
	/** How much spray falling sheets throw (droplets where they land and peeling off them, mist at their foot); 0 turns it off. */
	public static float hfSpray = 1.0F;
	/** Radius of a heightfield spray droplet (blocks). */
	public static float hfSprayRadius = 0.02F;
	/** Radius of a mist puff (blocks) and how opaque one is. */
	public static float hfMistSize = 0.5F;
	public static float hfMistAlpha = 0.02F;
	/** Radius of a splash's water drops (blocks; each drop 0.5..1.4 times this), drawn as motion-blurred bright streaks. */
	public static float hfDropRadius = 0.03F;
	/** Diagnostic: report in chat when a block edit makes heightfield water jump (where, at which stage, how the column changed). */
	public static boolean hfEditProbe = false;
	public static int hfMaxStepsPerFrame = 8;
	/** When the surface everywhere rises or falls slower than this (blocks/s) for {@link #hfSleepSeconds}, the heightfield stops simulating until woken. */
	public static float hfSleepRate = 0.002F;
	public static float hfSleepSeconds = 1.0F;
	/** Tiles whose deepest water is below hfFilmDepth only hold a thin film and may sleep while still creeping slower than this. */
	public static float hfFilmDepth = 0.1F;
	public static float hfFilmSleepRate = 0.02F;
	/**
	 * Water thinner than this does not flow sideways out of a cell, so a flood stops spreading once its edge is this thin (it still
	 * drains over edges). 1 cm: at 3 cm a stream landing on a block top could not spread over it and poured off one corner; a
	 * bucket now covers about 100 blocks^2 before it stops, close to vanilla water's 7-block reach.
	 */
	public static float hfMinFlowDepth = 0.01F;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;
	public static boolean renderSteadyPreview = true;
	public static float renderCameraSmoothing = 0.12F;

	private static final Map<String, String> DEFAULTS = snapshot();

	private WaterSettings() {
	}

	private static Map<String, String> snapshot() {
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<String, Field> e : fields().entrySet()) {
			try {
				out.put(e.getKey(), String.valueOf(e.getValue().get(null)));
			} catch (IllegalAccessException ignored) {
			}
		}
		return out;
	}

	public static String defaultValue(String name) {
		return DEFAULTS.getOrDefault(name, "");
	}

	public static String get(String name) throws ReflectiveOperationException {
		Field f = fields().get(name);
		if (f == null) {
			throw new NoSuchFieldException(name);
		}
		return String.valueOf(f.get(null));
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("realistic-water.properties");
	}

	public static void load() {
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(file)) {
			props.load(in);
		} catch (IOException e) {
			RealisticWater.LOGGER.warn("Couldn't read {}", file, e);
			return;
		}
		for (String name : props.stringPropertyNames()) {
			try {
				String value = props.getProperty(name);
				set(name, value.isEmpty() ? "-" : value);
			} catch (ReflectiveOperationException | NumberFormatException e) {
				RealisticWater.LOGGER.warn("Skipping setting {} from {}", name, file);
			}
		}
	}

	public static void save() {
		Properties props = new Properties();
		for (String name : fields().keySet()) {
			try {
				String value = get(name);
				if (!value.equals(defaultValue(name))) {
					props.setProperty(name, value);
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer out = Files.newBufferedWriter(file)) {
				props.store(out, "Realistic Water settings that differ from the defaults (edit in game with /water)");
			}
		} catch (IOException e) {
			RealisticWater.LOGGER.warn("Couldn't save {}", file, e);
		}
	}

	public static void reset(String name) throws ReflectiveOperationException {
		if (DEFAULTS.containsKey(name)) {
			set(name, DEFAULTS.get(name).isEmpty() ? "-" : DEFAULTS.get(name));
		}
	}

	public static Map<String, Field> fields() {
		Map<String, Field> out = new TreeMap<>();
		for (Field f : WaterSettings.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				out.put(f.getName(), f);
			}
		}
		return out;
	}

	public static String set(String name, String value) throws ReflectiveOperationException {
		Field f = fields().get(name);
		if (f == null) {
			throw new NoSuchFieldException(name);
		}
		Class<?> t = f.getType();
		if (t == int.class) {
			f.setInt(null, Integer.parseInt(value));
		} else if (t == float.class) {
			f.setFloat(null, Float.parseFloat(value));
		} else if (t == boolean.class) {
			f.setBoolean(null, Boolean.parseBoolean(value));
		} else if (t == String.class) {
			f.set(null, value.equals("-") ? "" : value);
		}
		if ("lavaIgniteFlammables".equals(name) && t == boolean.class) {
			ModSettings.lavaIgniteFlammables = (boolean) f.get(null);
		}
		if ("lavaWaterMixing".equals(name) && t == boolean.class) {
			ModSettings.lavaWaterMixing = (boolean) f.get(null);
		}
		return String.valueOf(f.get(null));
	}
}
