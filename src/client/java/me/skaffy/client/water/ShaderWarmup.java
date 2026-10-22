package me.skaffy.client.water;

import java.util.Map;
import me.skaffy.client.vk.Shaders;

/**
 * Compiles the water shaders to SPIR-V on background threads from game start, so the first water in a world does not compile
 * about 30 shaders on the render thread (a 2-3 s freeze on the first bucket). The programs themselves are created at world join
 * ({@link WaterWorld#warm}). A shader or define set missing here still works: it is just compiled when first used.
 */
final class ShaderWarmup {
	private static final Map<String, String> NONE = Map.of();
	private static final String[] COMPUTE = {
		// Heightfield (HfDomain.programs) and the mesh draw arguments.
		"hf_flux.comp", "hf_depth.comp", "hf_mesh.comp", "hf_probe.comp", "mesh_args.comp", "hf_remap.comp", "hf_inject.comp",
		"hf_reservoir.comp", "hf_sample.comp", "hf_spray_update.comp", "hf_spray_args.comp", "hf_sample_many.comp",
		// Sand rendering.
		"grain_cull.comp",
		// Particle simulation (WaterRegion, SurfaceMesher, PressureSolver): splashes, /water ball, lava and sand.
		"args.comp", "emit.comp", "clear_box.comp", "p2g.comp", "classify.comp", "faces.comp", "divergence.comp", "project.comp",
		"extrapolate.comp", "potential.comp", "g2p.comp", "ww_update.comp", "inflow.comp", "probe.comp", "shift.comp", "remove.comp",
		"merge.comp", "blockfill.comp", "mpm_clear.comp", "mpm_p2g.comp", "mpm_grid.comp", "mpm_g2p.comp",
		"fine_clear.comp", "blur.comp", "nets_vertices.comp", "nets_quads.comp", "sheet_fill.comp", "zb_splat.comp", "zb_field.comp",
		"mg_flags0.comp", "mg_coarsen.comp", "mg_smooth.comp", "mg_restrict.comp", "mg_prolong.comp", "cg_init.comp", "cg_apply.comp",
		"cg_dot.comp", "cg_reduce.comp", "cg_update.comp", "cg_direction.comp", "mg_coarse.comp"
	};

	private ShaderWarmup() {
	}

	static void start() {
		// The largest first: they take longest and are needed for the first heightfield frame.
		graphics("fullscreen.vert", "composite.frag", NONE);
		Shaders.prewarm("hf_mesh.comp", Shaders.COMPUTE, NONE);
		for (String c : COMPUTE) {
			Shaders.prewarm(c, Shaders.COMPUTE, NONE);
		}
		Shaders.prewarm("blockfill.comp", Shaders.COMPUTE, Map.of("MPM", "1"));
		// WaterRenderer's programs, with the defines it builds them with.
		Map<String, String> droplets = Map.of("DROPLETS", "1", "VIEW_BINDING", "3");
		graphics("mesh.vert", "mesh_front.frag", Map.of("MESH_FLOW", "1"));
		graphics("particle.vert", "droplet_front.frag", droplets);
		graphics("mesh.vert", "mesh_thickness.frag", NONE);
		graphics("particle.vert", "droplet_thickness.frag", droplets);
		graphics("particle.vert", "whitewater.frag", Map.of("VIEW_BINDING", "4"));
		graphics("grain.vert", "grain.frag", NONE);
	}

	private static void graphics(String vert, String frag, Map<String, String> defines) {
		Shaders.prewarm(vert, Shaders.VERTEX, defines);
		Shaders.prewarm(frag, Shaders.FRAGMENT, defines);
	}
}
