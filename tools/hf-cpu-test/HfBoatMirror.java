package me.skaffy.client.water;

import java.util.function.DoubleUnaryOperator;

/**
 * Mirror of AbstractBoatMixin. Vanilla AbstractBoat vertical physics (26.3 bytecode: getStatus, isUnderwater, checkInWater, getWaterLevelAbove, floatBoat)
 * on a column of realistic water blocks written from a true surface the way HfBlocks.amountFor does (with hysteresis).
 */
final class HfBoatMirror {
	enum St { IN_WATER, UNDER_WATER, UNDER_FLOWING_WATER, ON_LAND, IN_AIR }

	// mode 0: vanilla heights (amount/9) + vanilla isSource (UNDER_FLOWING); 1: amount/9 + last batch's mixin (UNDER_WATER);
	// 2: fix (amount/8, realistic water never makes a boat "underwater")
	static int mode;
	static int[] amount = new int[200];
	static final double H = 0.5625, G = 0.04;

	static boolean water(int y) { return y >= 0 && y < 200 && amount[y] > 0; }
	static float height(int y) {
		if (!water(y)) return 0;
		if (water(y + 1)) return 1.0F;
		return amount[y] / (mode == 2 ? 8.0F : 9.0F);
	}

	/** Fix: height of the realistic water column from this block's bottom up to its surface, at most 3 blocks. */
	static float column(int by) {
		float h = 0;
		int k = by;
		while (water(k + 1) && k - by < 2) { h += 1; k++; }
		h += water(k + 1) ? 1.0F : amount[k] / 8.0F;
		// The drawn surface sits 1/9 lower once the water is a block deep (hfSurfaceDrop), less on thinner water.
		return h - (water(by - 1) ? 1.0F : Math.min(h, 1.0F)) / 9.0F;
	}

	static double y, vy, waterLevel, lastYd; static St status = St.IN_AIR, oldStatus;

	static St isUnderwater() {
		double d = y + H + 0.001;
		boolean flag = false;
		for (int by = (int) Math.floor(y + H); by < Math.ceil(d); by++) {
			if (water(by) && mode != 2 && d < by + height(by)) {
				if (mode == 0) return St.UNDER_FLOWING_WATER; // realistic water is never a source
				flag = true;
			}
		}
		return flag ? St.UNDER_WATER : null;
	}

	static boolean checkInWater() {
		waterLevel = -Double.MAX_VALUE;
		boolean flag = false;
		for (int by = (int) Math.floor(y); by < Math.ceil(y + 0.001); by++) {
			if (water(by)) {
				double f = by + (mode == 2 ? column(by) : height(by));
				waterLevel = Math.max(f, waterLevel);
				flag |= y < f;
			}
		}
		return flag;
	}

	static double waterLevelAbove() {
		int k = (int) Math.floor(y + H), l = (int) Math.ceil(y + H - lastYd);
		for (int o = k; o < l; o++) {
			float f = Math.max(0, water(o) ? height(o) : 0);
			if (f < 1.0F) return o + f;
		}
		return l + 1;
	}

	static St getStatus(double ground) {
		St s = isUnderwater();
		if (s != null) { waterLevel = y + H; return s; }
		if (checkInWater()) return St.IN_WATER;
		return y <= ground + 1e-6 ? St.ON_LAND : St.IN_AIR;
	}

	static void floatBoat() {
		double d = -G, e = 0;
		if (oldStatus == St.IN_AIR && status != St.IN_AIR && status != St.ON_LAND) {
			waterLevel = y;
			double f = waterLevelAbove() - H + 0.101;
			y = f; vy = 0; lastYd = 0;
			status = St.IN_WATER;
			return;
		}
		if (status == St.IN_WATER) e = (waterLevel - y) / H;
		else if (status == St.UNDER_FLOWING_WATER) d = -7.0E-4;
		else if (status == St.UNDER_WATER) e = 0.01;
		vy += d;
		if (e > 0) vy = (vy + e * (G / 0.65)) * 0.75;
	}

	/** Runs a boat on water whose true surface follows s(t); returns {sank (0/1), min freeboard, max depth of the boat bottom below the surface}. */
	static double[] run(DoubleUnaryOperator s, double seconds, double ground) {
		java.util.Arrays.fill(amount, 0);
		y = s.applyAsDouble(0) - 0.37; vy = 0; status = St.IN_WATER; lastYd = 0;
		double minFree = 9, worst = 0;
		int under = 0;
		for (int t = 0; t < seconds * 20; t++) {
			double surf = s.applyAsDouble(t / 20.0);
			for (int by = 0; by < 200; by++) {
				float fill = (float) Math.clamp(surf - (by + ground - ground), 0.0, 1.0);
				if (by < ground) fill = 0;
				amount[by] = HfBlocks.amountFor(fill, amount[by]);
			}
			oldStatus = status;
			status = getStatus(ground);
			floatBoat();
			y += vy; lastYd = vy;
			if (y < ground) { y = ground; vy = 0; }
			minFree = Math.min(minFree, y + H - surf);
			worst = Math.max(worst, surf - y);
			if (y + H < surf - 0.05) under++; else under = 0;
		}
		return new double[]{under > 40 ? 1 : 0, minFree, worst};
	}

	static void run() {
		Object[][] cases = {
			{"lake at a block boundary (captured vanilla water), 3 cm ripples", (DoubleUnaryOperator) t -> 64.0 + 0.03 * Math.sin(t * 3.1)},
			{"flood rising 0.5 b/s", (DoubleUnaryOperator) t -> Math.min(62.0 + 0.5 * t, 67.0)},
			{"flood rising 2 b/s", (DoubleUnaryOperator) t -> Math.min(62.0 + 2 * t, 67.0)},
			{"waves 0.3 high, 4 s period", (DoubleUnaryOperator) t -> 64.2 + 0.3 * Math.sin(t * 1.57)},
		};
		for (Object[] c : cases) {
			mode = 1;
			double[] old = run((DoubleUnaryOperator) c[1], 30, 55);
			mode = 2;
			double[] now = run((DoubleUnaryOperator) c[1], 30, 55);
			HfCpuTest.check(now[0] == 0 && now[2] < 1.0, String.format("%s: %s, bottom at most %.2f under the surface (before the fix: %s, %.2f)",
				c[0], now[0] > 0 ? "SANK" : "floats", now[2], old[0] > 0 ? "sank" : "floated", old[2]));
		}
	}
}
