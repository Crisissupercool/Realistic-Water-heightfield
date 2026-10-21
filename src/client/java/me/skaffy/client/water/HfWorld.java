package me.skaffy.client.water;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.net.HfBlocksPayload;
import me.skaffy.net.WaterSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * The heightfield as the owner of realistic water in the world (docs/HEIGHTFIELD_DESIGN.md section 14):
 * <ul>
 *   <li>realistic water blocks in loaded chunks are imported into heightfield tiles (instead of particles), springs become
 *       reservoirs;</li>
 *   <li>placed and picked-up realistic water is added to / removed from the heightfield;</li>
 *   <li>the heightfield writes its water levels back into realistic water blocks (swimming, saving), when a tile settles and about
 *       once a second while it moves;</li>
 *   <li>{@code /water hf capture} turns vanilla water into realistic water the heightfield takes over, {@code /water hf release}
 *       turns it back.</li>
 * </ul>
 * Block writes need the integrated server (singleplayer, or LAN host); other clients only show the heightfield.
 */
final class HfWorld {
	private final WaterWorld world;
	/** What waterAmount() reports for a realistic water block written with amount a (filled in lazily, needs the registries). */
	private int[] realisticAmount;
	/** Releasing: the solver is paused while every tile is written back as vanilla water, then the heightfield is closed. */
	boolean releasing;

	HfWorld(WaterWorld world) {
		this.world = world;
	}

	private int realisticAmount(int a) {
		if (this.realisticAmount == null) {
			this.realisticAmount = new int[9];
			for (int k = 1; k <= 8; k++) {
				this.realisticAmount[k] = WaterWorld.waterAmount(WaterSyncPayload.stateFor(k));
			}
		}
		return a <= 0 ? 0 : this.realisticAmount[Math.min(a, 8)];
	}

	private static boolean owns() {
		return WaterSettings.hfOwnsWater;
	}

	private static boolean canWrite() {
		return WaterSettings.hfSyncBlocks && WaterWorld.isHost() && ClientPlayNetworking.canSend(HfBlocksPayload.TYPE);
	}

	/** The heightfield, created around (x, y, z) if there is none yet. Null without Vulkan. */
	@Nullable HfDomain ensure(ClientLevel level, double x, double y, double z) {
		if (this.world.heightfield != null) {
			return this.world.heightfield;
		}
		if (!this.world.vulkanReady()) {
			return null;
		}
		int cpb = Math.clamp(WaterSettings.hfCellsPerBlock, 1, 4);
		int layers = Math.clamp(WaterSettings.hfLayers, 1, HfTileGrid.MAX_LAYERS);
		int maxTiles = Math.clamp(WaterSettings.hfMaxTiles, 16, 4096);
		int maxPages = Math.clamp(WaterSettings.hfMaxPages, 16, 16384);
		int maxFluxPages = Math.clamp(WaterSettings.hfMaxFluxPages, 16, maxPages);
		// Centre the height window on the player when there is one: on joining a world the first chunk scanned may hold some stray
		// water far above or below where the player (and usually the water they care about) is.
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		int refY = mc.player != null ? Mth.floor(mc.player.getY()) : Mth.floor(y);
		int yMin = Math.max(level.getMinY(), refY - Math.max(8, WaterSettings.hfWindowBelow));
		int yMax = Math.min(level.getMinY() + level.getHeight(), refY + Math.max(8, WaterSettings.hfWindowAbove));
		// Tiles line up with chunks: tile (0, 0) is the chunk containing (x, z).
		int originX = Math.floorDiv(Mth.floor(x), HfTileGrid.TILE_BLOCKS) * HfTileGrid.TILE_BLOCKS;
		int originZ = Math.floorDiv(Mth.floor(z), HfTileGrid.TILE_BLOCKS) * HfTileGrid.TILE_BLOCKS;
		HfTileGrid grid = new HfTileGrid(originX, originZ, yMin, yMax, cpb, layers, WaterSettings.hfMinOpening, maxTiles, maxPages, maxFluxPages);
		HfDomain hf = new HfDomain(grid, WaterSettings.hfMaxQuads);
		this.world.heightfield = hf;
		this.world.failed = false;
		this.releasing = false;
		return hf;
	}

	private static boolean inWindow(HfTileGrid g, int y) {
		return y >= g.yMin && y < g.yMax;
	}

	static HfTopology.Voxels voxels(ClientLevel level, HfTileGrid g) {
		return voxels(level, g, null);
	}

	/**
	 * Terrain for the heightfield. Any fluid that is not realistic water (vanilla water, waterlogged blocks, kelp, lava) counts as
	 * solid: it is not the heightfield's water, so the heightfield must neither flow into it nor lose its own water there. Positions
	 * in {@code open} (vanilla water being captured right now) count as open.
	 */
	static HfTopology.Voxels voxels(ClientLevel level, HfTileGrid g, @Nullable Long2IntOpenHashMap open) {
		SolidCache solids = new SolidCache(g.cpb, false);
		long full = g.cpb * g.cpb * g.cpb >= 64 ? -1L : (1L << (g.cpb * g.cpb * g.cpb)) - 1L;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		return (bx, by, bz) -> {
			BlockState state = level.getBlockState(p.set(bx, by, bz));
			if (!state.getFluidState().isEmpty() && state.getBlock() != ModContent.REALISTIC_WATER_BLOCK
				&& (open == null || !open.containsKey(BlockPos.asLong(bx, by, bz)))) {
				return full;
			}
			return solids.blockVoxels(level, bx, by, bz);
		};
	}

	/** Realistic water amount at a block (springs count as full), 0 for anything else. */
	private static int realistic(ClientLevel level, int x, int y, int z) {
		BlockState state = level.getBlockState(new BlockPos(x, y, z));
		return state.getBlock() == ModContent.REALISTIC_WATER_BLOCK ? Math.min(8, Math.max(WaterWorld.waterAmount(state), RealisticWaterBlock.isInfinite(state) ? 8 : 0)) : 0;
	}

	/** Adds a tile built from the current terrain, filled from the realistic water blocks in it plus {@code extra}. */
	int addTileFromBlocks(ClientLevel level, HfDomain hf, int tx, int tz, @Nullable Long2IntOpenHashMap extra) {
		HfTileGrid g = hf.grid;
		int cx = Math.floorDiv(g.originX, 16) + tx;
		int cz = Math.floorDiv(g.originZ, 16) + tz;
		if (!level.hasChunk(cx, cz)) {
			// An unloaded chunk would read as solid and dry; its water would then be overwritten by the block sync.
			return HfTileGrid.NONE;
		}
		HfTileGrid.Tile t = g.build(voxels(level, g, extra), tx, tz);
		float[] depth = HfBlocks.depthFromBlocks(g, tx, tz, t.geo, t.layersUsed, (x, y, z) -> {
			if (extra != null) {
				int e = extra.getOrDefault(BlockPos.asLong(x, y, z), 0);
				if (e > 0) {
					return e;
				}
			}
			// Blocks a particle simulation currently owns stay with it.
			return this.world.ownedByParticles(BlockPos.asLong(x, y, z)) ? 0 : realistic(level, x, y, z);
		});
		int tile = hf.addTile(tx, tz, t, depth);
		if (tile != HfTileGrid.NONE) {
			this.addSpringsIn(level, hf, tile);
		}
		return tile;
	}

	/** Springs (infinite realistic water) in a tile hold their column at the top of the block, like a vanilla source: they refill it and take back any overshoot. */
	private void addSpringsIn(ClientLevel level, HfDomain hf, int tile) {
		HfTileGrid g = hf.grid;
		int cx = Math.floorDiv(g.originX, 16) + g.tileX[tile];
		int cz = Math.floorDiv(g.originZ, 16) + g.tileZ[tile];
		if (!level.hasChunk(cx, cz)) {
			return;
		}
		LevelChunk chunk = level.getChunk(cx, cz);
		for (int y = g.yMin; y < g.yMax; y++) {
			LevelChunkSection section = chunk.getSection(level.getSectionIndex(y));
			if (section.hasOnlyAir() || !section.maybeHas(RealisticWaterBlock::isInfinite)) {
				y = (y | 15);
				continue;
			}
			for (int bz = 0; bz < 16; bz++) {
				for (int bx = 0; bx < 16; bx++) {
					BlockState state = section.getBlockState(bx, y & 15, bz);
					if (RealisticWaterBlock.isInfinite(state) && state.getBlock() == ModContent.REALISTIC_WATER_BLOCK) {
						this.setBlockReservoir(g, tile, bx, bz, y + 1.0F, HfTileGrid.RESERVOIR_FIXED, true);
					}
				}
			}
		}
	}

	private void setBlockReservoir(HfTileGrid g, int tile, int bx, int bz, float level, int mode, boolean add) {
		for (int k = 0; k < g.cpb; k++) {
			for (int c = 0; c < g.cpb; c++) {
				int col = (bz * g.cpb + k) * g.tn + bx * g.cpb + c;
				if (add) {
					g.setReservoir(tile, col, level, mode);
				} else {
					g.removeReservoir(tile, col);
				}
			}
		}
	}

	private boolean warnedWindow;

	private void warnWindow(int y, HfTileGrid g) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (!this.warnedWindow && mc.player != null) {
			this.warnedWindow = true;
			mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(
				"Realistic water at Y=%d is outside the heightfield's height window (Y %d to %d) and stays frozen (and invisible). "
					+ "Stand near it and run /water reload to rebuild the heightfield around you.", y, g.yMin, g.yMax)));
		}
	}

	// ---- chunk loads and block changes ----

	/**
	 * A chunk was loaded: if it holds realistic water and no heightfield tile covers it yet, build one from its blocks. Returns true
	 * when the heightfield took the chunk's water (the particle simulation must then leave it alone).
	 */
	boolean importChunk(ClientLevel level, ChunkPos pos) {
		if (!owns() || !level.hasChunk(pos.x(), pos.z())) {
			return false;
		}
		LevelChunk chunk = level.getChunk(pos.x(), pos.z());
		LevelChunkSection[] sections = chunk.getSections();
		int firstY = Integer.MIN_VALUE;
		for (int s = 0; s < sections.length && firstY == Integer.MIN_VALUE; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() == ModContent.REALISTIC_WATER_BLOCK)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096 && firstY == Integer.MIN_VALUE; i++) {
				if (section.getBlockState(i & 15, (i >> 4) & 15, i >> 8).getBlock() == ModContent.REALISTIC_WATER_BLOCK) {
					firstY = baseY + ((i >> 4) & 15);
				}
			}
		}
		if (firstY == Integer.MIN_VALUE) {
			return false;
		}
		HfDomain hf = this.ensure(level, pos.x() * 16 + 8, firstY, pos.z() * 16 + 8);
		if (hf == null) {
			return false;
		}
		HfTileGrid g = hf.grid;
		if (!inWindow(g, firstY)) {
			// Outside the heightfield's height window: leave the blocks as they are rather than handing a lake to the particles.
			this.warnWindow(firstY, g);
			return true;
		}
		int tx = g.tileOfBlockX(pos.x() * 16);
		int tz = g.tileOfBlockZ(pos.z() * 16);
		if (g.slotAt(tx, tz) == HfTileGrid.NONE) {
			this.addTileFromBlocks(level, hf, tx, tz, null);
		}
		return true;
	}

	/**
	 * Realistic water at a block changed by something other than our own writes (a bucket, another mod). Returns true when the
	 * heightfield took the change.
	 */
	boolean onWaterChanged(ClientLevel level, BlockPos pos, int before, int after) {
		if (!owns() || !WaterWorld.isHost()) {
			return false;
		}
		HfDomain hf = this.ensure(level, pos.getX(), pos.getY(), pos.getZ());
		if (hf == null || !inWindow(hf.grid, pos.getY())) {
			return false;
		}
		HfTileGrid g = hf.grid;
		int tx = g.tileOfBlockX(pos.getX());
		int tz = g.tileOfBlockZ(pos.getZ());
		int tile = g.slotAt(tx, tz);
		if (tile == HfTileGrid.NONE) {
			// The new block is already in the world, so building the tile from the blocks includes it.
			return this.addTileFromBlocks(level, hf, tx, tz, null) != HfTileGrid.NONE;
		}
		this.injectBlock(hf, tile, pos.getX(), pos.getY(), pos.getZ(), (after - before) / 8.0F);
		return true;
	}

	private void injectBlock(HfDomain hf, int tile, int x, int y, int z, float depth) {
		HfTileGrid g = hf.grid;
		int bx = x - g.originX - g.tileX[tile] * HfTileGrid.TILE_BLOCKS;
		int bz = z - g.originZ - g.tileZ[tile] * HfTileGrid.TILE_BLOCKS;
		for (int k = 0; k < g.cpb; k++) {
			for (int c = 0; c < g.cpb; c++) {
				hf.inject(tile, (bz * g.cpb + k) * g.tn + bx * g.cpb + c, y + 0.5F, depth);
			}
		}
	}

	/** A spring was placed or removed. Returns true when the heightfield handles it. */
	boolean onSpring(ClientLevel level, BlockPos pos, boolean added) {
		if (!owns()) {
			return false;
		}
		HfDomain hf = added ? this.ensure(level, pos.getX(), pos.getY(), pos.getZ()) : this.world.heightfield;
		if (hf == null || !inWindow(hf.grid, pos.getY())) {
			return false;
		}
		HfTileGrid g = hf.grid;
		int tx = g.tileOfBlockX(pos.getX());
		int tz = g.tileOfBlockZ(pos.getZ());
		int tile = g.slotAt(tx, tz);
		if (tile == HfTileGrid.NONE) {
			// Building the tile also picks up the spring.
			return added && this.addTileFromBlocks(level, hf, tx, tz, null) != HfTileGrid.NONE;
		}
		int bx = pos.getX() - g.originX - tx * HfTileGrid.TILE_BLOCKS;
		int bz = pos.getZ() - g.originZ - tz * HfTileGrid.TILE_BLOCKS;
		this.setBlockReservoir(g, tile, bx, bz, pos.getY() + 1.0F, HfTileGrid.RESERVOIR_FIXED, added);
		g.quiet[tile] = 0.0F;
		return true;
	}

	// ---- commands ----

	/**
	 * Test command: raises the water to {@code center.y + height} inside {@code radius}. New tiles start with that water; tiles that
	 * already exist get it added on top of what they hold.
	 */
	String flood(ClientLevel level, Vec3 center, int radius, double height) {
		long start = System.nanoTime();
		HfDomain hf = this.ensure(level, center.x, center.y, center.z);
		if (hf == null) {
			return "Realistic Water needs the Vulkan graphics API";
		}
		HfTileGrid g = hf.grid;
		int refY = Mth.floor(center.y);
		float waterLevel = (float) (center.y + height);
		if (!inWindow(g, refY)) {
			return String.format("Y=%d is outside the heightfield's height window (%d to %d); clear it first", refY, g.yMin, g.yMax);
		}
		waterLevel = Math.min(waterLevel, g.yMax - 1.0F);
		HfTopology.Voxels voxels = voxels(level, g);
		int tb = HfTileGrid.TILE_BLOCKS;
		int tx0 = g.tileOfBlockX(Mth.floor(center.x - radius));
		int tx1 = g.tileOfBlockX(Mth.floor(center.x + radius));
		int tz0 = g.tileOfBlockZ(Mth.floor(center.z - radius));
		int tz1 = g.tileOfBlockZ(Mth.floor(center.z + radius));
		double r2 = (double) radius * radius;
		double before = hf.initialVolume;
		int added = 0;
		boolean full = false;
		for (int tz = tz0; tz <= tz1 && !full; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				double minX = g.originX + tx * (double) tb;
				double minZ = g.originZ + tz * (double) tb;
				double nearX = Mth.clamp(center.x, minX, minX + tb) - center.x;
				double nearZ = Mth.clamp(center.z, minZ, minZ + tb) - center.z;
				if (nearX * nearX + nearZ * nearZ > r2) {
					continue;
				}
				int tile = g.slotAt(tx, tz);
				HfTileGrid.Tile built = tile == HfTileGrid.NONE ? g.build(voxels, tx, tz) : null;
				int[] geo = built != null ? built.geo : g.tileGeo[tile];
				int layers = built != null ? built.layersUsed : g.layersUsed[tile];
				float[] depth = new float[layers * g.pageSpans];
				for (int lz = 0; lz < g.tn; lz++) {
					for (int lx = 0; lx < g.tn; lx++) {
						double dx = minX + (lx + 0.5) / g.cpb - center.x;
						double dz = minZ + (lz + 0.5) / g.cpb - center.z;
						if (dx * dx + dz * dz > r2) {
							continue;
						}
						for (int l = 0; l < layers; l++) {
							int i = l * g.pageSpans + lz * g.tn + lx;
							if (!HfTileGrid.validGeo(geo[i])) {
								continue;
							}
							float floor = g.floorOf(geo[i]);
							float ceil = g.ceilOf(geo[i]);
							// Spans open around the player's height or above: not deep caves, but rooms and tree-covered ground.
							if (floor < waterLevel && ceil > refY - 2) {
								depth[i] = Math.min(waterLevel, ceil) - floor;
								if (built == null) {
									hf.inject(tile, lz * g.tn + lx, floor + 0.01F, depth[i]);
								}
							}
						}
					}
				}
				if (built != null) {
					if (hf.addTile(tx, tz, built, depth) == HfTileGrid.NONE) {
						full = true;
						break;
					}
					added++;
				}
			}
		}
		long ms = (System.nanoTime() - start) / 1_000_000L;
		return String.format("Flooding radius %d to Y=%.1f: %,.0f blocks of water, %d new tiles (%d in use, pool %d), built in %d ms%s",
			radius, waterLevel, hf.initialVolume - before, added, g.count, g.maxTiles, ms,
			full ? ". The tile pool is full; raise hfMaxTiles / hfMaxPages for bigger floods" : "");
	}

	/**
	 * Takes over the vanilla water within {@code radius} of {@code center}: the blocks become realistic water (invisible to vanilla
	 * rendering, still swimmable) and the heightfield simulates them. Where the water continues past the edge (an ocean, a river),
	 * the edge is held at its level.
	 */
	String capture(ClientLevel level, Vec3 center, int radius) {
		if (!canWrite()) {
			return "Capturing changes blocks, which needs singleplayer (or hosting the world)";
		}
		HfDomain hf = this.ensure(level, center.x, center.y, center.z);
		if (hf == null) {
			return "Realistic Water needs the Vulkan graphics API";
		}
		HfTileGrid g = hf.grid;
		int cx = Mth.floor(center.x);
		int cz = Mth.floor(center.z);
		double r2 = (radius + 0.5) * (radius + 0.5);
		Long2IntOpenHashMap water = new Long2IntOpenHashMap();
		Map<Long, int[]> tiles = new LinkedHashMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int z = cz - radius; z <= cz + radius; z++) {
			for (int x = cx - radius; x <= cx + radius; x++) {
				if ((double) (x - cx) * (x - cx) + (double) (z - cz) * (z - cz) > r2 || !level.hasChunk(x >> 4, z >> 4)) {
					continue;
				}
				for (int y = g.yMin; y < g.yMax; y++) {
					BlockState state = level.getBlockState(p.set(x, y, z));
					if (state.getBlock() == Blocks.WATER) {
						int amount = Math.clamp(state.getFluidState().getAmount(), 1, 8);
						water.put(BlockPos.asLong(x, y, z), amount);
						int tx = g.tileOfBlockX(x);
						int tz = g.tileOfBlockZ(z);
						tiles.putIfAbsent(((long) tx << 32) ^ (tz & 0xFFFFFFFFL), new int[]{tx, tz});
					}
				}
			}
		}
		if (water.isEmpty()) {
			return "No vanilla water within " + radius + " blocks (between Y " + g.yMin + " and " + g.yMax + ")";
		}
		LongArrayList positions = new LongArrayList();
		ByteArrayList amounts = new ByteArrayList();
		int newTiles = 0;
		int skipped = 0;
		for (int[] t : tiles.values()) {
			int tile = g.slotAt(t[0], t[1]);
			boolean fresh = tile == HfTileGrid.NONE;
			if (!fresh) {
				// The tile treated this vanilla water as solid; rebuild it with the captured cells open so the water has room.
				hf.rebuildTile(tile, g.build(voxels(level, g, water), t[0], t[1]));
			}
			if (fresh) {
				tile = this.addTileFromBlocks(level, hf, t[0], t[1], water);
				if (tile == HfTileGrid.NONE) {
					continue;
				}
				newTiles++;
			}
			int x0 = g.originX + t[0] * HfTileGrid.TILE_BLOCKS;
			int z0 = g.originZ + t[1] * HfTileGrid.TILE_BLOCKS;
			for (var e : water.long2IntEntrySet()) {
				long key = e.getLongKey();
				int x = BlockPos.getX(key);
				int z = BlockPos.getZ(key);
				if (x < x0 || x >= x0 + 16 || z < z0 || z >= z0 + 16) {
					continue;
				}
				if (!fresh) {
					this.injectBlock(hf, tile, x, BlockPos.getY(key), z, e.getIntValue() / 8.0F);
				}
				positions.add(key);
				amounts.add((byte) e.getIntValue());
				this.world.expectWater(key, this.realisticAmount(e.getIntValue()));
			}
		}
		skipped = water.size() - positions.size();
		int reservoirs = this.captureEdges(level, hf, water, cx, cz, radius);
		send(HfBlocksPayload.CAPTURE, positions, amounts);
		return String.format("Captured %,d water blocks into %d new tiles (%d in use), %d edge cells held at their level%s",
			positions.size(), newTiles, g.count, reservoirs, skipped > 0 ? String.format("; %,d blocks left as vanilla (tile pool full)", skipped) : "");
	}

	/** Columns at the edge of the capture whose water continues outside become fixed-level reservoirs. Returns the cell count. */
	private int captureEdges(ClientLevel level, HfDomain hf, Long2IntOpenHashMap water, int cx, int cz, int radius) {
		HfTileGrid g = hf.grid;
		Long2IntOpenHashMap top = new Long2IntOpenHashMap();
		top.defaultReturnValue(Integer.MIN_VALUE);
		for (long key : water.keySet()) {
			long column = BlockPos.asLong(BlockPos.getX(key), 0, BlockPos.getZ(key));
			top.put(column, Math.max(top.get(column), BlockPos.getY(key)));
		}
		double r2 = (radius + 0.5) * (radius + 0.5);
		int cells = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (var e : top.long2IntEntrySet()) {
			int x = BlockPos.getX(e.getLongKey());
			int z = BlockPos.getZ(e.getLongKey());
			int y = e.getIntValue();
			boolean open = false;
			for (int d = 0; d < 4 && !open; d++) {
				int nx = x + HfTileGrid.DX[d];
				int nz = z + HfTileGrid.DZ[d];
				boolean outside = (double) (nx - cx) * (nx - cx) + (double) (nz - cz) * (nz - cz) > r2;
				open = outside && level.getBlockState(p.set(nx, y, nz)).getBlock() == Blocks.WATER;
			}
			int[] at = g.locate(x + 0.5, z + 0.5);
			if (!open || at == null) {
				continue;
			}
			int tile = at[0];
			float surface = y + water.get(BlockPos.asLong(x, y, z)) / 8.0F;
			this.setBlockReservoir(g, tile, x - g.originX - g.tileX[tile] * HfTileGrid.TILE_BLOCKS, z - g.originZ - g.tileZ[tile] * HfTileGrid.TILE_BLOCKS,
				surface, HfTileGrid.RESERVOIR_FIXED, true);
			cells += g.cpb * g.cpb;
		}
		return cells;
	}

	/** Writes every tile back as vanilla water, then closes the heightfield. */
	String release() {
		HfDomain hf = this.world.heightfield;
		if (hf == null) {
			return "No heightfield water";
		}
		if (!canWrite()) {
			return "Releasing changes blocks, which needs singleplayer (or hosting the world)";
		}
		for (int s = 0; s < hf.grid.count; s++) {
			hf.grid.syncForced[s] = true;
		}
		this.releasing = true;
		return "Turning " + hf.grid.count + " tiles of heightfield water back into vanilla water...";
	}

	/** Removes the heightfield and the realistic water blocks it wrote (springs stay). */
	String clear(ClientLevel level) {
		HfDomain hf = this.world.heightfield;
		if (hf == null) {
			return "No heightfield water";
		}
		int removed = 0;
		if (canWrite()) {
			HfTileGrid g = hf.grid;
			LongArrayList positions = new LongArrayList();
			ByteArrayList amounts = new ByteArrayList();
			for (int s = 0; s < g.count; s++) {
				int cx = Math.floorDiv(g.originX, 16) + g.tileX[s];
				int cz = Math.floorDiv(g.originZ, 16) + g.tileZ[s];
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				LevelChunk chunk = level.getChunk(cx, cz);
				for (int y = g.yMin; y < g.yMax; y++) {
					LevelChunkSection section = chunk.getSection(level.getSectionIndex(y));
					if (section.hasOnlyAir() || !section.maybeHas(st -> st.getBlock() == ModContent.REALISTIC_WATER_BLOCK)) {
						y = (y | 15);
						continue;
					}
					for (int bz = 0; bz < 16; bz++) {
						for (int bx = 0; bx < 16; bx++) {
							BlockState state = section.getBlockState(bx, y & 15, bz);
							if (state.getBlock() == ModContent.REALISTIC_WATER_BLOCK && !RealisticWaterBlock.isInfinite(state)) {
								long key = BlockPos.asLong(cx * 16 + bx, y, cz * 16 + bz);
								positions.add(key);
								amounts.add((byte) 0);
								this.world.expectWater(key, 0);
							}
						}
					}
				}
			}
			send(HfBlocksPayload.WRITE, positions, amounts);
			removed = positions.size();
		}
		this.close();
		return removed > 0 ? "Removed the heightfield water and " + removed + " water blocks" : "Removed the heightfield water";
	}

	void close() {
		this.warnedWindow = false;
		if (this.world.heightfield != null) {
			this.world.heightfield.close();
			this.world.heightfield = null;
		}
		this.releasing = false;
	}

	// ---- block sync ----

	// ---- currents ----

	/** Latest water sample at the player: {u, w, surface, depth, found, floor}, and when it arrived. */
	private float[] playerSample;
	private long playerSampleNanos;

	/** Call each client tick: water moving around the player pulls it along (horizontal drag toward the water velocity). */
	void pushPlayer(net.minecraft.client.Minecraft mc) {
		var player = mc.player;
		float[] sm = this.playerSample;
		if (player == null || sm == null || this.world.heightfield == null || WaterSettings.hfCurrentDrag <= 0.0F
			|| System.nanoTime() - this.playerSampleNanos > 250_000_000L || sm[4] < 0.5F || sm[3] < 0.1F
			|| player.isSpectator() || player.getAbilities().flying) {
			return;
		}
		double feet = player.getY();
		double submerged = Math.clamp((sm[2] - feet) / Math.max(0.5, player.getBbHeight()), 0.0, 1.0);
		double speed = Math.hypot(sm[0], sm[1]);
		if (submerged <= 0.0 || speed < 0.05) {
			return;
		}
		// Blocks per second -> blocks per tick; the pull fades in with the current so still water does not add drag of its own.
		double k = Math.min(1.0, WaterSettings.hfCurrentDrag * submerged * Math.min(1.0, speed / 0.5));
		Vec3 v = player.getDeltaMovement();
		double tx = sm[0] / 20.0;
		double tz = sm[1] / 20.0;
		player.setDeltaMovement(v.x + (tx - v.x) * k, v.y, v.z + (tz - v.z) * k);
	}

	/** Call each frame before planning: writes arrived depth readbacks into blocks. */
	void receive(ClientLevel level, HfDomain hf) {
		float[] sm = hf.takeSample();
		if (sm != null) {
			this.playerSample = sm;
			this.playerSampleNanos = System.nanoTime();
		}
		List<HfDomain.TileDepth> arrived = hf.takeDepths();
		for (HfDomain.TileDepth td : arrived) {
			this.writeTile(level, hf, td, this.releasing ? HfBlocksPayload.RELEASE : HfBlocksPayload.WRITE);
		}
		if (this.releasing) {
			HfTileGrid g = hf.grid;
			boolean done = true;
			for (int s = 0; s < g.count && done; s++) {
				done = !g.syncForced[s] && !g.syncPending[s];
			}
			if (done) {
				this.close();
			}
		}
	}

	/** Call each frame after the solver steps: samples the water at the player and asks for the depth of tiles due for a block sync. */
	void request(VkCommandBuffer cb, me.skaffy.client.vk.Desc ubo, HfDomain hf, float frameDt) {
		var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			hf.sample(cb, ubo, player.getX(), player.getY() + 0.1, player.getZ());
		}
		if (!canWrite()) {
			return;
		}
		int[] tiles = hf.grid.pickSync(frameDt, Math.max(0.1F, WaterSettings.hfSyncInterval), WaterSettings.hfSleepSeconds,
			Math.clamp(WaterSettings.hfSyncTilesPerFrame, 1, HfDomain.MAX_SYNC_TILES));
		if (tiles.length > 0) {
			hf.requestDepths(cb, tiles);
		}
	}

	private void writeTile(ClientLevel level, HfDomain hf, HfDomain.TileDepth td, byte mode) {
		HfTileGrid g = hf.grid;
		int tile = td.tile();
		boolean asleep = g.quiet[tile] >= WaterSettings.hfSleepSeconds;
		if (td.version() != g.version[tile]) {
			// Rebuilt since the copy: sync again from the new topology.
			g.syncPending[tile] = false;
			return;
		}
		int cx = Math.floorDiv(g.originX, 16) + g.tileX[tile];
		int cz = Math.floorDiv(g.originZ, 16) + g.tileZ[tile];
		if (!level.hasChunk(cx, cz)) {
			g.synced(tile, asleep);
			return;
		}
		LevelChunk chunk = level.getChunk(cx, cz);
		int[] geo = g.tileGeo[tile];
		int layers = g.layersUsed[tile];
		int rows = g.yMax - g.yMin;
		boolean[] sectionHasWater = new boolean[level.getSectionsCount()];
		for (int i = 0; i < sectionHasWater.length; i++) {
			LevelChunkSection section = chunk.getSection(i);
			sectionHasWater[i] = !section.hasOnlyAir() && section.maybeHas(st -> st.getBlock() == ModContent.REALISTIC_WATER_BLOCK);
		}
		LongArrayList positions = new LongArrayList();
		ByteArrayList amounts = new ByteArrayList();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int bz = 0; bz < 16; bz++) {
			for (int bx = 0; bx < 16; bx++) {
				float[] fill = HfBlocks.blockFills(g, geo, layers, td.depth(), bx, bz);
				for (int r = 0; r < rows; r++) {
					int y = g.yMin + r;
					if (fill[r] <= 0.0F && !sectionHasWater[level.getSectionIndex(y)]) {
						continue;
					}
					p.set(cx * 16 + bx, y, cz * 16 + bz);
					BlockState state = chunk.getBlockState(p);
					boolean ours = state.getBlock() == ModContent.REALISTIC_WATER_BLOCK;
					if (ours && RealisticWaterBlock.isInfinite(state) || !ours && !state.isAir()) {
						continue;
					}
					long key = p.asLong();
					int current = ours ? WaterWorld.waterAmount(state) : 0;
					int pending = this.world.expectedWater(key);
					if (pending >= 0) {
						current = pending;
					}
					int target = HfBlocks.amountFor(fill[r], current, !asleep);
					if (mode == HfBlocksPayload.RELEASE) {
						// Vanilla flowing water without a source dries up, so released water is all sources: a block at least half full
						// becomes a source, the rest air.
						int vanilla = fill[r] >= 0.5F ? 8 : 0;
						if (vanilla > 0 || ours) {
							positions.add(key);
							amounts.add((byte) vanilla);
							this.world.expectWater(key, 0);
						}
					} else if (target != current) {
						positions.add(key);
						amounts.add((byte) target);
						this.world.expectWater(key, this.realisticAmount(target));
					}
				}
			}
		}
		send(mode, positions, amounts);
		g.synced(tile, asleep);
	}

	private static void send(byte mode, LongArrayList positions, ByteArrayList amounts) {
		if (positions.isEmpty() || !ClientPlayNetworking.canSend(HfBlocksPayload.TYPE)) {
			return;
		}
		int max = HfBlocksPayload.MAX_ENTRIES;
		for (int start = 0; start < positions.size(); start += max) {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] a = new byte[n];
			for (int k = 0; k < n; k++) {
				p[k] = positions.getLong(start + k);
				a[k] = amounts.getByte(start + k);
			}
			ClientPlayNetworking.send(new HfBlocksPayload(mode, p, a));
		}
	}
}
