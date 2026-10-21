package me.skaffy.client.water;

import me.skaffy.ModSettings;
import java.lang.reflect.Field;
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
	public static int extrapolationLayers = 4;
	public static float flipRatio = 0.99F;
	public static float volumeCorrection = 2.0F;
	public static float maxSpeed = 40.0F;
	public static float gravity = 9.81F;

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
	public static float sheetSmoothing = 2.0F;
	public static float strandedTime = 1.5F;
	public static int strandedNeighbors = 12;

	public static int surfaceSubdivision = 2;
	public static float surfaceKernel = 3.0F;
	public static float surfaceRadius = 0.9F;
	public static float surfaceIso = 0.5F;
	public static float dropletThreshold = 0.35F;
	public static float nearCull = 0.05F;

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

	// Heightfield solver (docs/HEIGHTFIELD_DESIGN.md). Topology settings apply to the next /water flood.
	public static int hfCellsPerBlock = 2;
	public static int hfLayers = 4;
	public static float hfMinOpening = 0.25F;
	/** Tiles of 16x16 blocks; with hfMaxPages and hfMaxFluxPages read when a flood starts. */
	public static int hfMaxTiles = 256;
	/** Span pages: one per used layer of a tile, 12 B per span (12 KB per page at 2 cells per block). */
	public static int hfMaxPages = 512;
	/** Flux pages, only held by tiles being simulated: 32 B per span (32 KB per page). */
	public static int hfMaxFluxPages = 384;
	/** Quads in the shared heightfield surface mesh (80 B each). */
	public static int hfMaxQuads = 400_000;
	/** The heightfield owns realistic water: chunk loads, placed water and springs go to it instead of the particle simulation. */
	public static boolean hfOwnsWater = true;
	/** Write the heightfield's water back into realistic water blocks (swimming, saving). Needs singleplayer or hosting. */
	public static boolean hfSyncBlocks = true;
	/** Seconds between block syncs of a tile while its water moves; settled tiles sync once when they fall asleep. */
	public static float hfSyncInterval = 1.0F;
	public static int hfSyncTilesPerFrame = 2;
	/** Height window of a new heightfield, below and above where it starts (water outside it is left alone). */
	public static int hfWindowBelow = 64;
	public static int hfWindowAbove = 96;
	/** Debug: 0 normal, 1 draw only water tops (no side walls), 2 draw only side walls. */
	public static int hfDebugMesh = 0;
	public static int hfMaxNewTilesPerFrame = 2;
	public static float hfCfl = 0.3F;
	public static float hfDamping = 0.995F;
	public static float hfFriction = 0.2F;
	public static float hfMaxSpeed = 30.0F;
	/** Water thinner than this is not drawn: still films (thinner than hfMinFlowDepth) stay invisible instead of shimmering squares. */
	public static float hfRenderDepth = 0.035F;
	/** Lowers the drawn surface like vanilla's 8/9-high water (blocks, for water at least a block deep). */
	public static float hfSurfaceDrop = 0.111F;
	/** How strongly moving heightfield water pulls the player along (fraction of the velocity difference per tick, when fully submerged). */
	public static float hfCurrentDrag = 0.15F;
	/** Outflow over an edge (blocks^3/s) above which a falling sheet is drawn. */
	public static float hfCurtainMinFlow = 0.005F;
	/** How hard the player pushes heightfield water when moving through it (bow wave, wake, rings when jumping in); 0 turns it off. */
	public static float hfStirStrength = 0.5F;
	/** How strongly the player makes fine ripples on heightfield water (wading, swimming, bobbing, jumping in); 0 turns them off. */
	public static float hfRipples = 1.0F;
	/** How much the ripples bend the surface when shading (multiplies their slope). */
	public static float hfRippleNormal = 1.0F;
	/** How white foam and falling water show on heightfield water (0 off, 1 normal). */
	public static float hfFoam = 1.0F;
	/** Seconds foam takes to fade to about a third once nothing churns the water any more. */
	public static float hfFoamLife = 1.5F;
	public static int hfMaxStepsPerFrame = 8;
	/** When the surface everywhere rises or falls slower than this (blocks/s) for {@link #hfSleepSeconds}, the heightfield stops simulating until woken. */
	public static float hfSleepRate = 0.002F;
	public static float hfSleepSeconds = 1.0F;
	/** Tiles whose deepest water is below hfFilmDepth only hold a thin film and may sleep while still creeping slower than this. */
	public static float hfFilmDepth = 0.1F;
	public static float hfFilmSleepRate = 0.02F;
	/** Water thinner than this does not flow out of a cell, so a flood stops spreading once its edge is this thin. */
	public static float hfMinFlowDepth = 0.03F;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;
	public static boolean renderSteadyPreview = true;
	public static float renderCameraSmoothing = 0.12F;

	private WaterSettings() {
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
		return String.valueOf(f.get(null));
	}
}
