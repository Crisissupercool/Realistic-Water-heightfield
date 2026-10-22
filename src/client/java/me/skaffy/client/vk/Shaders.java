package me.skaffy.client.vk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public final class Shaders {
	private static final String ROOT = "/assets/realistic-water/shaders/";
	public static final int VERTEX = Shaderc.shaderc_vertex_shader;
	public static final int FRAGMENT = Shaderc.shaderc_fragment_shader;
	public static final int COMPUTE = Shaderc.shaderc_compute_shader;

	/**
	 * SPIR-V compiled ahead of use on background threads ({@link #prewarm}). Compiling every water shader with shaderc on the render
	 * thread the first time water appeared froze the game for 2-3 s (about 30 shaders, composite and hf_mesh the largest).
	 */
	private static final Map<String, Future<byte[]>> SPIRV = new ConcurrentHashMap<>();
	private static final ExecutorService WARMUP = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "realistic-water shader warmup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private Shaders() {
	}

	private static String key(String name, int kind, Map<String, String> defines) {
		return name + '|' + kind + '|' + new TreeMap<>(defines);
	}

	/** Starts compiling a shader to SPIR-V in the background, so {@link #module} later only has to create the module. */
	public static void prewarm(String name, int kind, Map<String, String> defines) {
		SPIRV.computeIfAbsent(key(name, kind, defines), k -> WARMUP.submit(() -> compile(name, kind, defines)));
	}

	public static String load(String name) {
		try (InputStream in = Shaders.class.getResourceAsStream(ROOT + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing shader " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read shader " + name, e);
		}
	}

	private static String preprocess(String source, Set<String> seen) {
		StringBuilder out = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#include")) {
				int a = trimmed.indexOf('"');
				int b = trimmed.lastIndexOf('"');
				String file = trimmed.substring(a + 1, b);
				if (seen.add(file)) {
					out.append(preprocess(load(file), seen)).append('\n');
				}
			} else {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	public static long module(String name, int kind, Map<String, String> defines) {
		byte[] spv = null;
		Future<byte[]> warmed = SPIRV.get(key(name, kind, defines));
		if (warmed != null) {
			try {
				spv = warmed.get();
			} catch (Exception e) {
				// Compile again here, so a real compile error is reported with its message.
				spv = null;
			}
		}
		if (spv == null) {
			spv = compile(name, kind, defines);
		}
		ByteBuffer code = MemoryUtil.memAlloc(spv.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			code.put(spv).flip();
			VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
			LongBuffer pModule = stack.mallocLong(1);
			Vk.check(VK10.vkCreateShaderModule(Vk.vk(), info, null, pModule), "vkCreateShaderModule " + name);
			return pModule.get(0);
		} finally {
			MemoryUtil.memFree(code);
		}
	}

	private static byte[] compile(String name, int kind, Map<String, String> defines) {
		String source = load(name);
		int nl = source.indexOf('\n');
		StringBuilder head = new StringBuilder(source.substring(0, nl + 1));
		defines.forEach((k, v) -> head.append("#define ").append(k).append(' ').append(v).append('\n'));
		String full = preprocess(head + source.substring(nl + 1), new HashSet<>());

		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		ByteBuffer src = MemoryUtil.memUTF8(full, false);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			long result = Shaderc.shaderc_compile_into_spv(compiler, src, kind, stack.UTF8(name), stack.UTF8("main"), options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("Shader " + name + " failed to compile:\n" + Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
				byte[] out = new byte[spv.remaining()];
				spv.get(out);
				return out;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			MemoryUtil.memFree(src);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
