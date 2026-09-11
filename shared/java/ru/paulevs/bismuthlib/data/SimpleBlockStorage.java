package ru.paulevs.bismuthlib.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the 48x48x48 block window (3x3x3 sections) around a section.
 * Blocks are read straight from chunk section palettes, nothing is copied.
 */
public class SimpleBlockStorage implements BlockGetter {
	public static final int SIZE = 48;
	private static final BlockState AIR = Blocks.AIR.defaultBlockState();

	private final LevelChunkSection[] sections = new LevelChunkSection[27];
	private int minBuildHeight;
	private int height;
	private int originX;
	private int originY;
	private int originZ;

	/**
	 * Loads sections around the section. Missing chunks and air-only sections are treated as air.
	 * @return false if the chunk of the center section is not loaded
	 */
	public boolean load(Level level, int secX, int secY, int secZ) {
		originX = (secX - 1) << 4;
		originY = (secY - 1) << 4;
		originZ = (secZ - 1) << 4;
		minBuildHeight = level.getMinBuildHeight();
		height = level.getHeight();

		int minSection = level.getMinSection();
		ChunkSource source = level.getChunkSource();
		boolean centerLoaded = false;

		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				LevelChunk chunk = source.getChunkNow(secX + dx, secZ + dz);
				LevelChunkSection[] chunkSections = chunk == null ? null : chunk.getSections();
				if (dx == 0 && dz == 0) centerLoaded = chunkSections != null;
				for (int dy = -1; dy <= 1; dy++) {
					LevelChunkSection section = null;
					int sectionIndex = secY + dy - minSection;
					if (chunkSections != null && sectionIndex >= 0 && sectionIndex < chunkSections.length) {
						section = chunkSections[sectionIndex];
						if (section != null && section.hasOnlyAir()) section = null;
					}
					sections[(dx + 1) * 9 + (dy + 1) * 3 + dz + 1] = section;
				}
			}
		}

		return centerLoaded;
	}

	/**
	 * @param index (dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)
	 */
	@Nullable
	public LevelChunkSection getSection(int index) {
		return sections[index];
	}

	/**
	 * Get block state using window coordinates (0-47).
	 */
	public BlockState getState(int x, int y, int z) {
		LevelChunkSection section = sections[(x >> 4) * 9 + (y >> 4) * 3 + (z >> 4)];
		return section == null ? AIR : section.getBlockState(x & 15, y & 15, z & 15);
	}

	public int getOriginX() {
		return originX;
	}

	public int getOriginY() {
		return originY;
	}

	public int getOriginZ() {
		return originZ;
	}

	@Nullable
	@Override
	public BlockEntity getBlockEntity(BlockPos blockPos) {
		return null;
	}

	@Override
	public BlockState getBlockState(BlockPos blockPos) {
		int x = blockPos.getX() - originX;
		int y = blockPos.getY() - originY;
		int z = blockPos.getZ() - originZ;
		if (x < 0 || x >= SIZE || y < 0 || y >= SIZE || z < 0 || z >= SIZE) return AIR;
		return getState(x, y, z);
	}

	@Override
	public FluidState getFluidState(BlockPos blockPos) {
		return getBlockState(blockPos).getFluidState();
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public int getMinBuildHeight() {
		return minBuildHeight;
	}
}
