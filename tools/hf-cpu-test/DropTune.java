package me.skaffy.client.water;

/** Tuning aid: flood front motion for several HfMeshMirror.DROP_DEPTH values. */
final class DropTune {
	public static void main(String[] args) {
		for (String a : args) {
			HfMeshMirror.DROP_DEPTH = Float.parseFloat(a);
			for (boolean bank : new boolean[]{false, true}) {
				double[] jumpy = HfMeshMirror.frontMotion(false, bank);
				double[] fluid = HfMeshMirror.frontMotion(true, bank);
				System.out.printf("depth %.3f bank %b: max %.2f frames %d front %.1f | jumpy %.2f %d -> %s%n", HfMeshMirror.DROP_DEPTH, bank,
					fluid[0], (int) fluid[1], fluid[2], jumpy[0], (int) jumpy[1],
					fluid[0] < 0.5 * jumpy[0] && fluid[1] < 0.25 * Math.max(jumpy[1], 1.0) && fluid[2] > 2.0 ? "ok" : "FAIL");
			}
		}
	}
}
