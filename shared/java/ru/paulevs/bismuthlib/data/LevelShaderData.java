package ru.paulevs.bismuthlib.data;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.paulevs.bismuthlib.LightPropagator;
import ru.paulevs.bismuthlib.gui.CFOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Colored light map around the player.
 * <p>
 * The map is a wrapping grid of section cells (dataWidth x dataHeight x dataWidth). Sections are computed by worker
 * threads, nearest to the player first, and uploaded into a sparse {@link LightAtlas}. Sections are recomputed only
 * when something can change their light: chunk load/unload, block change nearby or the player moving to new sections.
 * All public methods except the worker loop must be called on the render thread.
 */
@Environment(EnvType.CLIENT)
public class LevelShaderData {
	private static final Logger LOGGER = LoggerFactory.getLogger("bismuthlib");
	private static final long NO_POSITION = Long.MIN_VALUE;
	private static final long UPLOAD_BUDGET_NANOS = 6_000_000L;
	private static final int BUFFER_COUNT = 512;

	private final int dataWidth;
	private final int dataHeight;
	private final int halfWidth;
	private final int halfHeight;
	private final long[] cellPositions;
	private final int[] cellSlots;
	private final long[] cellSequences;
	private final int[] slotOwners;
	private final LightAtlas atlas;

	private final Set<Long> queued = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<SectionResult> results = new ConcurrentLinkedQueue<>();
	private final ArrayBlockingQueue<int[]> buffers = new ArrayBlockingQueue<>(BUFFER_COUNT);
	private final AtomicLong sequence = new AtomicLong();
	private final List<ChunkRefresh> deferredRefreshes = new ArrayList<>();
	private final Thread[] threads;
	private volatile PriorityBlockingQueue<SectionJob> jobs = new PriorityBlockingQueue<>();
	private volatile boolean run = true;

	private volatile Level level;
	private volatile boolean hasCenter;
	private volatile int centerX;
	private volatile int centerY;
	private volatile int centerZ;
	private int evictionFailDistance = Integer.MAX_VALUE;

	public LevelShaderData(int dataWidth, int dataHeight, int threadCount) {
		this.dataWidth = dataWidth;
		this.dataHeight = dataHeight;
		this.halfWidth = dataWidth >> 1;
		this.halfHeight = dataHeight >> 1;

		int cellCount = dataWidth * dataWidth * dataHeight;
		cellPositions = new long[cellCount];
		Arrays.fill(cellPositions, NO_POSITION);
		cellSlots = new int[cellCount];
		cellSequences = new long[cellCount];
		atlas = new LightAtlas(cellCount);
		slotOwners = new int[atlas.getMaxSlots()];

		for (int i = 0; i < BUFFER_COUNT; i++) {
			buffers.add(new int[4096]);
		}

		threads = new Thread[threadCount];
		for (int i = 0; i < threadCount; i++) {
			LightPropagator propagator = new LightPropagator();
			threads[i] = new Thread(() -> workerLoop(propagator), "colored_lights_" + i);
			threads[i].setDaemon(true);
			threads[i].start();
		}
	}

	public void dispose() {
		run = false;
		for (Thread thread : threads) {
			thread.interrupt();
		}
		for (Thread thread : threads) {
			try {
				thread.join(1000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		atlas.close();
	}

	public int getTextureId() {
		return atlas.getTextureId();
	}

	public int getSlotBase() {
		return atlas.getSlotBase();
	}

	public int getDataWidth() {
		return dataWidth;
	}

	public int getDataHeight() {
		return dataHeight;
	}

	public int getThreadCount() {
		return threads.length;
	}

	public int getCenterX() {
		return centerX;
	}

	public int getCenterY() {
		return centerY;
	}

	public int getCenterZ() {
		return centerZ;
	}

	public void update(Level level, int cx, int cy, int cz) {
		if (level != this.level) {
			clearLevel();
			this.level = level;
		}

		if (!hasCenter || cx != centerX || cy != centerY || cz != centerZ) {
			recenter(level, cx, cy, cz);
		}

		if (!deferredRefreshes.isEmpty()) {
			for (ChunkRefresh refresh : deferredRefreshes) {
				refreshChunk(refresh.x, refresh.z, refresh.litSections);
			}
			deferredRefreshes.clear();
		}

		applyResults(level);

		if (atlas.consumeContentLost()) {
			resetAll();
		}
		atlas.flushPageTable();
	}

	/**
	 * Forget the current level (world closed or dimension changed).
	 */
	public void clearLevel() {
		if (level == null) return;
		level = null;
		hasCenter = false;
		jobs.clear();
		queued.clear();
		deferredRefreshes.clear();

		SectionResult result;
		while ((result = results.poll()) != null) {
			if (result.data != null) buffers.offer(result.data);
		}

		for (int cell = 0; cell < cellPositions.length; cell++) {
			cellPositions[cell] = NO_POSITION;
			releaseCell(cell);
		}
		atlas.flushPageTable();
	}

	/**
	 * Recompute all sections, current light stays visible until new data is ready.
	 */
	public void resetAll() {
		Level level = this.level;
		if (level == null || !hasCenter) return;
		int minSection = level.getMinSection();
		int maxSection = level.getMaxSection();
		for (int i = 0; i < dataWidth; i++) {
			int px = centerX - halfWidth + i;
			for (int k = 0; k < dataHeight; k++) {
				int py = centerY - halfHeight + k;
				if (py < minSection || py >= maxSection) continue;
				for (int j = 0; j < dataWidth; j++) {
					enqueue(px, py, centerZ - halfWidth + j);
				}
			}
		}
	}

	public void markSection(int x, int y, int z) {
		Level level = this.level;
		if (level == null || !hasCenter || !isInRange(x, y, z)) return;
		if (y < level.getMinSection() || y >= level.getMaxSection()) return;
		enqueue(x, y, z);
	}

	/**
	 * Mark all sections that can be affected by a block change.
	 * @param reach max distance in blocks that light can travel from a block
	 */
	public void markBlock(BlockPos pos, int reach) {
		int x1 = (pos.getX() - reach) >> 4;
		int y1 = (pos.getY() - reach) >> 4;
		int z1 = (pos.getZ() - reach) >> 4;
		int x2 = (pos.getX() + reach) >> 4;
		int y2 = (pos.getY() + reach) >> 4;
		int z2 = (pos.getZ() + reach) >> 4;
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					markSection(x, y, z);
				}
			}
		}
	}

	public void onChunkLoad(Level level, LevelChunk chunk) {
		if (level != this.level) return;
		refreshChunk(chunk.getPos().x, chunk.getPos().z, getLitSections(chunk));
	}

	public void onChunkUnload(Level level, LevelChunk chunk) {
		if (level != this.level) return;
		// Chunk is removed after this event, refresh on the next frame so workers don't see it
		deferredRefreshes.add(new ChunkRefresh(chunk.getPos().x, chunk.getPos().z, getLitSections(chunk)));
	}

	private void refreshChunk(int chunkX, int chunkZ, boolean[] litSections) {
		Level level = this.level;
		if (level == null || !hasCenter) return;

		int minSection = level.getMinSection();
		int y1 = Math.max(centerY - halfHeight, minSection);
		int y2 = Math.min(centerY + halfHeight, level.getMaxSection() - 1);

		for (int y = y1; y <= y2; y++) {
			markSection(chunkX, y, chunkZ);

			// Neighbour chunks change only if this chunk has lights near them
			int index = y - minSection;
			if (!isLit(litSections, index - 1) && !isLit(litSections, index) && !isLit(litSections, index + 1)) continue;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (dx != 0 || dz != 0) markSection(chunkX + dx, y, chunkZ + dz);
				}
			}
		}
	}

	private static boolean[] getLitSections(LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();
		boolean[] lit = new boolean[sections.length];
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			lit[i] = section != null && !section.hasOnlyAir() && section.maybeHas(BlockLights::hasLight);
		}
		return lit;
	}

	private static boolean isLit(boolean[] litSections, int index) {
		return index >= 0 && index < litSections.length && litSections[index];
	}

	private void recenter(Level level, int cx, int cy, int cz) {
		boolean moved = hasCenter;
		centerX = cx;
		centerY = cy;
		centerZ = cz;
		hasCenter = true;
		evictionFailDistance = Integer.MAX_VALUE;

		int minSection = level.getMinSection();
		int maxSection = level.getMaxSection();

		for (int i = 0; i < dataWidth; i++) {
			int px = cx - halfWidth + i;
			int indexX = Math.floorMod(px, dataWidth) * dataHeight;
			for (int k = 0; k < dataHeight; k++) {
				int py = cy - halfHeight + k;
				int indexXY = (indexX + Math.floorMod(py, dataHeight)) * dataWidth;
				for (int j = 0; j < dataWidth; j++) {
					int pz = cz - halfWidth + j;
					int cell = indexXY + Math.floorMod(pz, dataWidth);
					long key = BlockPos.asLong(px, py, pz);
					if (cellPositions[cell] == key) continue;

					// Cell now belongs to another section, hide old light until the new one is ready
					cellPositions[cell] = key;
					releaseCell(cell);
					if (py >= minSection && py < maxSection) {
						enqueue(px, py, pz);
					}
				}
			}
		}

		if (moved) reprioritize();
	}

	private void reprioritize() {
		PriorityBlockingQueue<SectionJob> oldJobs = jobs;
		if (oldJobs.isEmpty()) return;

		List<SectionJob> list = new ArrayList<>(oldJobs.size());
		oldJobs.drainTo(list);

		List<SectionJob> kept = new ArrayList<>(list.size());
		for (SectionJob job : list) {
			if (isInRange(job.x, job.y, job.z)) {
				job.distance = distance(job.x, job.y, job.z);
				kept.add(job);
			}
			else {
				queued.remove(job.key);
			}
		}

		// Heapify is O(n), workers pick up the new queue on their next poll
		jobs = new PriorityBlockingQueue<>(kept);
	}

	private void enqueue(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		if (queued.add(key)) {
			jobs.add(new SectionJob(key, x, y, z, distance(x, y, z)));
		}
	}

	private void workerLoop(LightPropagator propagator) {
		while (run) {
			try {
				SectionJob job = jobs.poll(10, TimeUnit.MILLISECONDS);
				if (job == null) continue;

				queued.remove(job.key);
				long jobSequence = sequence.incrementAndGet();

				Level level = this.level;
				if (level == null || !hasCenter || !isInRange(job.x, job.y, job.z)) continue;

				int[] buffer = buffers.take();
				boolean lit;
				try {
					lit = propagator.compute(level, job.x, job.y, job.z, buffer, CFOptions.isFastLight(), CFOptions.modifyColor());
				}
				catch (Exception e) {
					LOGGER.error("Failed to compute colored light for section " + job.x + " " + job.y + " " + job.z, e);
					buffers.offer(buffer);
					continue;
				}

				if (!lit) buffers.offer(buffer);
				results.add(new SectionResult(level, job.key, jobSequence, lit ? buffer : null));
			}
			catch (InterruptedException e) {
				if (!run) return;
			}
		}
	}

	private void applyResults(Level level) {
		long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
		int uploads = 0;
		SectionResult result;
		while ((result = results.poll()) != null) {
			if (applyResult(level, result)) uploads++;
			if (result.data != null) buffers.offer(result.data);
			if (uploads > 0 && (uploads & 7) == 0 && System.nanoTime() > deadline) break;
		}
	}

	private boolean applyResult(Level level, SectionResult result) {
		if (result.level != level) return false;

		int x = BlockPos.getX(result.key);
		int y = BlockPos.getY(result.key);
		int z = BlockPos.getZ(result.key);
		int cell = getCellIndex(x, y, z);

		// Section left the map or newer data was already applied
		if (cellPositions[cell] != result.key || result.sequence < cellSequences[cell]) return false;
		cellSequences[cell] = result.sequence;

		if (result.data == null) {
			releaseCell(cell);
			return false;
		}

		int slot = cellSlots[cell];
		if (slot == 0) {
			slot = atlas.allocateSlot();
			if (slot == 0) slot = takeFartherSlot(distance(x, y, z));
			if (slot == 0) return false;
			cellSlots[cell] = slot;
			slotOwners[slot] = cell;
		}

		atlas.uploadSlot(slot, result.data);
		atlas.setPage(cell, slot);
		return true;
	}

	/**
	 * When the atlas is full take a slot from the section farthest from the player (if it is farther than distance).
	 */
	private int takeFartherSlot(int distance) {
		if (distance >= evictionFailDistance) return 0;

		int bestCell = -1;
		int bestDistance = distance;
		for (int slot = 1; slot < slotOwners.length; slot++) {
			int cell = slotOwners[slot];
			if (cellSlots[cell] != slot) continue;
			long key = cellPositions[cell];
			int d = distance(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
			if (d > bestDistance) {
				bestDistance = d;
				bestCell = cell;
			}
		}

		if (bestCell < 0) {
			evictionFailDistance = distance;
			return 0;
		}

		int slot = cellSlots[bestCell];
		cellSlots[bestCell] = 0;
		atlas.setPage(bestCell, 0);
		return slot;
	}

	private void releaseCell(int cell) {
		int slot = cellSlots[cell];
		if (slot == 0) return;
		atlas.freeSlot(slot);
		cellSlots[cell] = 0;
		atlas.setPage(cell, 0);
		evictionFailDistance = Integer.MAX_VALUE;
	}

	/**
	 * Must match cell index calculation in colorful_fabric.glsl
	 */
	private int getCellIndex(int x, int y, int z) {
		int indexX = Math.floorMod(x, dataWidth);
		int indexY = Math.floorMod(y, dataHeight);
		int indexZ = Math.floorMod(z, dataWidth);
		return ((indexX * dataHeight) + indexY) * dataWidth + indexZ;
	}

	private boolean isInRange(int x, int y, int z) {
		return Math.abs(x - centerX) <= halfWidth && Math.abs(y - centerY) <= halfHeight && Math.abs(z - centerZ) <= halfWidth;
	}

	private int distance(int x, int y, int z) {
		int dx = x - centerX;
		int dy = y - centerY;
		int dz = z - centerZ;
		return dx * dx + dy * dy + dz * dz;
	}

	private static final class SectionJob implements Comparable<SectionJob> {
		final long key;
		final int x;
		final int y;
		final int z;
		int distance;

		SectionJob(long key, int x, int y, int z, int distance) {
			this.key = key;
			this.x = x;
			this.y = y;
			this.z = z;
			this.distance = distance;
		}

		@Override
		public int compareTo(SectionJob job) {
			return Integer.compare(distance, job.distance);
		}
	}

	private record SectionResult(Level level, long key, long sequence, int[] data) {}

	private record ChunkRefresh(int x, int z, boolean[] litSections) {}
}
