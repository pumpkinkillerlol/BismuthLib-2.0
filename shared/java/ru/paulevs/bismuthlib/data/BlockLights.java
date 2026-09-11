package ru.paulevs.bismuthlib.data;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ru.paulevs.bismuthlib.data.info.LightInfo;
import ru.paulevs.bismuthlib.data.transformer.LightTransformer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Concurrent maps: light worker threads read these while resources can be reloaded
public class BlockLights {
	private static final Map<BlockState, LightTransformer> TRANSFORMERS = new ConcurrentHashMap<>();
	private static final Map<BlockState, LightInfo> LIGHTS = new ConcurrentHashMap<>();
	private static volatile int maxRadius = 0;

	public static void addLight(BlockState state, LightInfo light) {
		if (light == null) {
			LIGHTS.remove(state);
		}
		else {
			LIGHTS.put(state, light);
			maxRadius = Math.max(maxRadius, light.getRadius());
		}
	}

	public static void addLight(Block block, LightInfo light) {
		block.getStateDefinition().getPossibleStates().forEach(state -> addLight(state, light));
	}

	public static LightInfo getLight(BlockState state) {
		return LIGHTS.get(state);
	}

	public static boolean hasLight(BlockState state) {
		return LIGHTS.containsKey(state);
	}

	/**
	 * Max distance in blocks that advanced light can travel from a block (limited by the 16 blocks propagation window).
	 */
	public static int getMaxReach() {
		return Math.max(1, Math.min(maxRadius - 1, 16));
	}

	public static void addTransformer(BlockState state, LightTransformer transformer) {
		TRANSFORMERS.put(state, transformer);
	}

	public static void addTransformer(Block block, LightTransformer transformer) {
		block.getStateDefinition().getPossibleStates().forEach(state -> addTransformer(state, transformer));
	}

	public static LightTransformer getTransformer(BlockState state) {
		return TRANSFORMERS.get(state);
	}

	public static void clear() {
		LIGHTS.clear();
		TRANSFORMERS.clear();
		maxRadius = 0;
	}
}
