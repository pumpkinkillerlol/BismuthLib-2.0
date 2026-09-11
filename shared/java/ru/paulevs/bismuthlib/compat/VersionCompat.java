package ru.paulevs.bismuthlib.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft calls that differ between versions, so the light engine can stay version independent.
 * 1.21.x source layers replace this class with their own copy.
 */
public final class VersionCompat {
	private VersionCompat() {}

	public static int getMinSection(LevelHeightAccessor level) {
		return level.getMinSection();
	}

	/**
	 * Exclusive upper section bound (one above the top section).
	 */
	public static int getMaxSection(LevelHeightAccessor level) {
		return level.getMaxSection();
	}

	public static boolean blocksLight(BlockState state, BlockGetter level, BlockPos pos) {
		return state.isSolidRender(level, pos) || !state.propagatesSkylightDown(level, pos);
	}
}
