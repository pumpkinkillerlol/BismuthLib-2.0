package ru.paulevs.bismuthlib;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import ru.paulevs.bismuthlib.compat.VersionCompat;
import ru.paulevs.bismuthlib.data.BlockLights;
import ru.paulevs.bismuthlib.data.SimpleBlockStorage;
import ru.paulevs.bismuthlib.data.info.LightInfo;
import ru.paulevs.bismuthlib.data.transformer.LightTransformer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Computes colored light for one 16x16x16 section. One instance per worker thread.
 * <p>
 * Advanced light is a breadth-first flood fill inside the 48x48x48 window around the section.
 * Lights with the same color and radius have identical falloff, so they are grouped and flooded
 * together as one multi-source fill (a lava lake is one fill instead of thousands). This gives the
 * same result as flooding each light on its own and max-blending the colors.
 */
public class LightPropagator {
	private static final int SIZE = SimpleBlockStorage.SIZE;
	private static final int VOLUME = SIZE * SIZE * SIZE;
	private static final int STEP_X = SIZE * SIZE;
	private static final int STEP_Y = SIZE;
	private static final int MAX_RADIUS = 255;
	private static final int MAX_CACHED_GROUPS = 1024;

	private static final byte FLAG_SOURCE = 1;
	private static final byte FLAG_TRANSFORMER = 2;
	private static final byte FLAG_BLOCK_X = 4;
	private static final byte FLAG_BLOCK_Y = 8;
	private static final byte FLAG_BLOCK_Z = 16;

	// Distance along one axis from a window coordinate to the target section (window coordinates 16-31)
	private static final int[] BOX_DISTANCE = new int[SIZE];
	private static final int[] OFFSET_BORDER_DELTAS;

	private final SimpleBlockStorage storage = new SimpleBlockStorage();
	private final MutableBlockPos pos = new MutableBlockPos();
	private final boolean[] lightSections = new boolean[27];
	private final LightInfo[] border = new LightInfo[5832];

	private final byte[] flagCache = new byte[VOLUME];
	private final int[] flagStamps = new int[VOLUME];
	private int flagStamp;

	private final int[] visited = new int[VOLUME];
	private int visitStamp;
	private int[] frontier = new int[VOLUME];
	private int[] nextFrontier = new int[VOLUME];
	private int nextCount;

	private final Long2ObjectOpenHashMap<LightGroup> primaryCache = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<LightGroup> secondaryCache = new Long2ObjectOpenHashMap<>();
	private final List<LightGroup> primaryGroups = new ArrayList<>();
	private final List<LightGroup> secondaryGroups = new ArrayList<>();
	private final int[] values = new int[MAX_RADIUS];
	private int computeId;

	// State of the group that is currently flooded
	private boolean modify;
	private int radius;
	private int step;
	private int stepValue;

	/**
	 * Compute light of a section.
	 * @param data output, 4096 colors indexed as x << 8 | y << 4 | z
	 * @return true if any block in the section has light, data content is undefined otherwise
	 */
	public boolean compute(Level level, int secX, int secY, int secZ, int[] data, boolean fast, boolean modify) {
		if (!storage.load(level, secX, secY, secZ)) return false;

		// Palette check is very cheap and rejects most sections (no light sources nearby)
		boolean hasLights = false;
		for (int i = 0; i < 27; i++) {
			LevelChunkSection section = storage.getSection(i);
			boolean lit = section != null && section.maybeHas(BlockLights::hasLight);
			lightSections[i] = lit;
			hasLights |= lit;
		}
		if (!hasLights) return false;

		Arrays.fill(data, ColorMath.ALPHA);
		if (fast) fastLight(level, data);
		else advancedLight(level, data, modify);

		for (int value : data) {
			if ((value & 0x00FFFFFF) != 0) return true;
		}
		return false;
	}

	private void fastLight(Level level, int[] data) {
		boolean hasLights = false;
		int borderIndex = 0;
		for (int x = 15; x < 33; x++) {
			for (int y = 15; y < 33; y++) {
				for (int z = 15; z < 33; z++) {
					LightInfo light = BlockLights.getLight(storage.getState(x, y, z));
					border[borderIndex++] = light;
					hasLights |= light != null;
				}
			}
		}
		if (!hasLights) return;

		int originX = storage.getOriginX() + 16;
		int originY = storage.getOriginY() + 16;
		int originZ = storage.getOriginZ() + 16;

		for (int x = 0; x < 16; x++) {
			pos.setX(originX + x);
			int borderX = (x + 1) * 324;
			for (int y = 0; y < 16; y++) {
				pos.setY(originY + y);
				int borderXY = borderX + (y + 1) * 18;
				for (int z = 0; z < 16; z++) {
					pos.setZ(originZ + z);
					borderIndex = borderXY + z + 1;
					int index = x << 8 | y << 4 | z;

					LightInfo light = border[borderIndex];
					if (light != null) {
						data[index] = light.getSimple(level, pos, (byte) 0) | ColorMath.ALPHA;
						continue;
					}

					BlockState state = storage.getState(x + 16, y + 16, z + 16);
					if (state.useShapeForLightOcclusion() && state.isCollisionShapeFullBlock(storage, pos)) {
						continue;
					}

					int color = 0;
					for (byte i = 1; i < 27; i++) {
						light = border[borderIndex + OFFSET_BORDER_DELTAS[i]];
						if (light != null) {
							color = ColorMath.maxBlend(color, light.getSimple(level, pos, i));
						}
					}
					data[index] = color | ColorMath.ALPHA;
				}
			}
		}
	}

	private void advancedLight(Level level, int[] data, boolean modify) {
		this.modify = modify;
		if (++flagStamp == Integer.MAX_VALUE) {
			Arrays.fill(flagStamps, 0);
			flagStamp = 1;
		}
		if (++computeId == Integer.MAX_VALUE) {
			primaryCache.clear();
			secondaryCache.clear();
			computeId = 1;
		}
		primaryGroups.clear();
		secondaryGroups.clear();

		int originX = storage.getOriginX();
		int originY = storage.getOriginY();
		int originZ = storage.getOriginZ();

		for (int i = 0; i < 27; i++) {
			if (!lightSections[i]) continue;
			LevelChunkSection section = storage.getSection(i);
			int baseX = (i / 9) << 4;
			int baseY = ((i / 3) % 3) << 4;
			int baseZ = (i % 3) << 4;
			for (int lx = 0; lx < 16; lx++) {
				int wx = baseX + lx;
				for (int ly = 0; ly < 16; ly++) {
					int wy = baseY + ly;
					int distanceXY = BOX_DISTANCE[wx] + BOX_DISTANCE[wy];
					for (int lz = 0; lz < 16; lz++) {
						LightInfo light = BlockLights.getLight(section.getBlockState(lx, ly, lz));
						if (light == null) continue;
						int wz = baseZ + lz;
						int lightRadius = Math.min(light.getRadius(), MAX_RADIUS);
						// Light can't reach the section
						if (distanceXY + BOX_DISTANCE[wz] >= lightRadius) continue;
						pos.set(originX + wx, originY + wy, originZ + wz);
						int color = light.getAdvanced(level, pos, (byte) 0);
						addSeed(primaryCache, primaryGroups, lightRadius, color, (wx * SIZE + wy) * SIZE + wz);
					}
				}
			}
		}

		for (int i = 0; i < primaryGroups.size(); i++) {
			propagate(level, data, primaryGroups.get(i), modify);
		}

		// Lights re-emitted by transformers (colored glass, etc.)
		for (int i = 0; i < secondaryGroups.size(); i++) {
			propagate(level, data, secondaryGroups.get(i), false);
		}
	}

	private void addSeed(Long2ObjectOpenHashMap<LightGroup> cache, List<LightGroup> active, int radius, int color, int index) {
		long key = (long) radius << 32 | (color & 0xFFFFFFFFL);
		LightGroup group = cache.get(key);
		if (group == null) {
			if (cache.size() >= MAX_CACHED_GROUPS) cache.clear();
			group = new LightGroup(radius, color);
			cache.put(key, group);
		}
		if (group.computeId != computeId) {
			group.computeId = computeId;
			group.seeds.clear();
			active.add(group);
		}
		group.seeds.add(index);
	}

	private void propagate(Level level, int[] data, LightGroup group, boolean spawn) {
		radius = group.radius;
		values[0] = group.color;
		for (int i = 1; i < radius; i++) {
			values[i] = ColorMath.multiply(group.color, 1.0F - (float) i / radius);
		}

		if (++visitStamp == Integer.MAX_VALUE) {
			Arrays.fill(visited, 0);
			visitStamp = 1;
		}

		int count = 0;
		IntArrayList seeds = group.seeds;
		for (int i = 0; i < seeds.size(); i++) {
			int index = seeds.getInt(i);
			if (visited[index] == visitStamp) continue;
			visited[index] = visitStamp;
			write(data, index, values[0]);
			frontier[count++] = index;
		}

		for (step = 1; step < radius && count > 0; step++) {
			stepValue = values[step];
			nextCount = 0;
			for (int i = 0; i < count; i++) {
				int index = frontier[i];
				int x = index / STEP_X;
				int y = (index / STEP_Y) % SIZE;
				int z = index % SIZE;
				int dx = BOX_DISTANCE[x];
				int dy = BOX_DISTANCE[y];
				int dz = BOX_DISTANCE[z];
				if (x > 0) visit(level, data, index - STEP_X, BOX_DISTANCE[x - 1] + dy + dz, FLAG_BLOCK_X, spawn);
				if (x < SIZE - 1) visit(level, data, index + STEP_X, BOX_DISTANCE[x + 1] + dy + dz, FLAG_BLOCK_X, spawn);
				if (y > 0) visit(level, data, index - STEP_Y, dx + BOX_DISTANCE[y - 1] + dz, FLAG_BLOCK_Y, spawn);
				if (y < SIZE - 1) visit(level, data, index + STEP_Y, dx + BOX_DISTANCE[y + 1] + dz, FLAG_BLOCK_Y, spawn);
				if (z > 0) visit(level, data, index - 1, dx + dy + BOX_DISTANCE[z - 1], FLAG_BLOCK_Z, spawn);
				if (z < SIZE - 1) visit(level, data, index + 1, dx + dy + BOX_DISTANCE[z + 1], FLAG_BLOCK_Z, spawn);
			}
			int[] swap = frontier;
			frontier = nextFrontier;
			nextFrontier = swap;
			count = nextCount;
		}
	}

	private void visit(Level level, int[] data, int index, int boxDistance, byte axisFlag, boolean spawn) {
		if (visited[index] == visitStamp) return;

		// Any path through this block is too long to reach the section
		if (step + boxDistance >= radius) {
			visited[index] = visitStamp;
			return;
		}

		byte flags = getFlags(index);
		if (spawn && (flags & FLAG_TRANSFORMER) != 0) {
			visited[index] = visitStamp;
			spawnTransformedLight(level, index);
			return;
		}
		if ((flags & FLAG_SOURCE) != 0) {
			visited[index] = visitStamp;
			return;
		}
		// Blocked only along this axis, can still be entered from other sides
		if ((flags & axisFlag) != 0) return;

		visited[index] = visitStamp;
		if (boxDistance == 0) write(data, index, stepValue);
		nextFrontier[nextCount++] = index;
	}

	private void spawnTransformedLight(Level level, int index) {
		int x = index / STEP_X;
		int y = (index / STEP_Y) % SIZE;
		int z = index % SIZE;
		LightTransformer transformer = BlockLights.getTransformer(storage.getState(x, y, z));
		if (transformer == null) return;
		pos.set(storage.getOriginX() + x, storage.getOriginY() + y, storage.getOriginZ() + z);
		int color = ColorMath.mulBlend(stepValue, transformer.getColor(level, pos));
		addSeed(secondaryCache, secondaryGroups, radius - step, color, index);
	}

	private byte getFlags(int index) {
		if (flagStamps[index] == flagStamp) return flagCache[index];

		int x = index / STEP_X;
		int y = (index / STEP_Y) % SIZE;
		int z = index % SIZE;
		BlockState state = storage.getState(x, y, z);
		byte flags = 0;

		if (!state.isAir()) {
			if (BlockLights.getLight(state) != null) flags |= FLAG_SOURCE;
			if (modify && BlockLights.getTransformer(state) != null) flags |= FLAG_TRANSFORMER;
			pos.set(storage.getOriginX() + x, storage.getOriginY() + y, storage.getOriginZ() + z);
			if (VersionCompat.blocksLight(state, storage, pos)) {
				if (isAxisSturdy(state, Direction.EAST)) flags |= FLAG_BLOCK_X;
				if (isAxisSturdy(state, Direction.UP)) flags |= FLAG_BLOCK_Y;
				if (isAxisSturdy(state, Direction.SOUTH)) flags |= FLAG_BLOCK_Z;
			}
		}

		flagCache[index] = flags;
		flagStamps[index] = flagStamp;
		return flags;
	}

	private boolean isAxisSturdy(BlockState state, Direction dir) {
		return state.isFaceSturdy(storage, pos, dir) || state.isFaceSturdy(storage, pos, dir.getOpposite());
	}

	private static void write(int[] data, int index, int color) {
		int x = index / STEP_X - 16;
		int y = (index / STEP_Y) % SIZE - 16;
		int z = index % SIZE - 16;
		if (((x | y | z) & ~15) != 0) return;
		int i = x << 8 | y << 4 | z;
		data[i] = ColorMath.maxBlend(data[i], color) | ColorMath.ALPHA;
	}

	static {
		for (int i = 0; i < SIZE; i++) {
			BOX_DISTANCE[i] = i < 16 ? 16 - i : i > 31 ? i - 31 : 0;
		}

		List<BlockPos> positions = new ArrayList<>(27);

		for (byte i = -1; i < 2; i++) {
			for (byte j = -1; j < 2; j++) {
				for (byte k = -1; k < 2; k++) {
					positions.add(new BlockPos(i, j, k));
				}
			}
		}

		BlockPos[] offsets = positions
			.stream()
			.sorted(Comparator.comparingDouble(b -> b.distSqr(Vec3i.ZERO)))
			.toList()
			.toArray(new BlockPos[27]);

		OFFSET_BORDER_DELTAS = new int[27];
		for (int i = 0; i < 27; i++) {
			BlockPos offset = offsets[i];
			OFFSET_BORDER_DELTAS[i] = offset.getX() * 324 + offset.getY() * 18 + offset.getZ();
		}
	}

	private static final class LightGroup {
		final IntArrayList seeds = new IntArrayList();
		final int radius;
		final int color;
		int computeId;

		LightGroup(int radius, int color) {
			this.radius = radius;
			this.color = color;
		}
	}
}
