package me.skaffy.client.water;

/**
 * Conversion between heightfield spans and block water amounts (0..8 per block, like realistic water blocks). Free of Minecraft
 * types so tools/hf-cpu-test can check the round trip. See docs/HEIGHTFIELD_DESIGN.md section 14.
 */
final class HfBlocks {
	private HfBlocks() {
	}

	/** Water amount 0..8 of the block at (bx, by, bz). */
	interface Amounts {
		int at(int bx, int by, int bz);
	}

	/**
	 * Depth per {@code layer * pageSpans + col} for a tile from block amounts: each span collects amount / 8 of every block whose
	 * center lies inside its open space.
	 */
	static float[] depthFromBlocks(HfTileGrid g, int tx, int tz, int[] geo, int layersUsed, Amounts a) {
		float[] depth = new float[layersUsed * g.pageSpans];
		int x0 = g.originX + tx * HfTileGrid.TILE_BLOCKS;
		int z0 = g.originZ + tz * HfTileGrid.TILE_BLOCKS;
		for (int bz = 0; bz < HfTileGrid.TILE_BLOCKS; bz++) {
			for (int bx = 0; bx < HfTileGrid.TILE_BLOCKS; bx++) {
				for (int by = g.yMin; by < g.yMax; by++) {
					int amount = a.at(x0 + bx, by, z0 + bz);
					if (amount <= 0) {
						continue;
					}
					float center = by + 0.5F;
					for (int k = 0; k < g.cpb; k++) {
						for (int c = 0; c < g.cpb; c++) {
							int col = (bz * g.cpb + k) * g.tn + bx * g.cpb + c;
							for (int l = 0; l < layersUsed; l++) {
								int gg = geo[l * g.pageSpans + col];
								if (HfTileGrid.validGeo(gg) && g.floorOf(gg) <= center && center < g.ceilOf(gg)) {
									depth[l * g.pageSpans + col] += Math.min(amount, 8) / 8.0F;
									break;
								}
							}
						}
					}
				}
			}
		}
		return depth;
	}

	/**
	 * How full each block of one block column is (0..1, index {@code by - yMin}), averaged over the column's cpb x cpb cells. Water
	 * in a span fills it from its floor up to {@code min(floor + depth, ceil)}.
	 */
	static float[] blockFills(HfTileGrid g, int[] geo, int layersUsed, float[] depth, int bx, int bz) {
		float[] fill = new float[g.yMax - g.yMin];
		float share = 1.0F / (g.cpb * g.cpb);
		for (int k = 0; k < g.cpb; k++) {
			for (int c = 0; c < g.cpb; c++) {
				int col = (bz * g.cpb + k) * g.tn + bx * g.cpb + c;
				for (int l = 0; l < layersUsed; l++) {
					int i = l * g.pageSpans + col;
					int gg = geo[i];
					float d = depth[i];
					if (!HfTileGrid.validGeo(gg) || d <= 0.0F) {
						continue;
					}
					float lo = g.floorOf(gg);
					float hi = Math.min(lo + d, g.ceilOf(gg));
					for (int y = Math.max(g.yMin, (int) Math.floor(lo)); y < Math.min(g.yMax, (int) Math.ceil(hi)); y++) {
						float overlap = Math.min(hi, y + 1.0F) - Math.max(lo, y);
						if (overlap > 0.0F) {
							fill[y - g.yMin] += overlap * share;
						}
					}
				}
			}
		}
		return fill;
	}

	/** Block amount for a fill fraction, keeping the current amount while the water is within 3/4 of a step of it (no flicker). */
	static int amountFor(float fill, int current) {
		return amountFor(fill, current, true);
	}

	/**
	 * @param hysteresis false for the final write of settled water: rounding to the nearest step then, so the saved amounts do not keep a
	 *                   level the water only had while it was sloshing (that made water come back slightly higher after a reload)
	 */
	static int amountFor(float fill, int current, boolean hysteresis) {
		float target = Math.clamp(fill, 0.0F, 1.0F) * 8.0F;
		if (hysteresis && current > 0 && Math.abs(target - current) < 0.75F) {
			return current;
		}
		int a = Math.round(target);
		return a < 1 && target < 0.5F ? 0 : Math.clamp(a, 1, 8);
	}
}
