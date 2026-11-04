package me.skaffy.physics;

public interface PhysicsView {
	int WATER_MASK = 0xF;
	int LAVA_SHIFT = 4;
	int FIRE = 0x100;

	double[][] boxes(int x, int y, int z);

	int medium(int x, int y, int z);

	default boolean flow(int x, int y, int z, double[] out) {
		return false;
	}

	default double friction(int x, int y, int z) {
		return 1.0;
	}
}
