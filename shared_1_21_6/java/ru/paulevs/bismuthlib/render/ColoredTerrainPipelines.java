package ru.paulevs.bismuthlib.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryStack;
import ru.paulevs.bismuthlib.BismuthLibClient;
import ru.paulevs.bismuthlib.data.LevelShaderData;
import ru.paulevs.bismuthlib.gui.CFOptions;

import java.nio.ByteBuffer;

/**
 * Copies of the vanilla solid and cutout chunk pipelines that run the colored light terrain shader.
 * Only chunk section rendering switches to them, so other users of these pipelines stay vanilla.
 */
public final class ColoredTerrainPipelines {
	private static final String UNIFORM_BLOCK = "BismuthLight";
	// std140 layout of the BismuthLight block (see bismuthlib_light.glsl) is 40 bytes, padded to a multiple of 16.
	private static final int UNIFORM_BLOCK_SIZE = 48;

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
		.withVertexShader("core/bismuthlib_terrain")
		.withFragmentShader("core/bismuthlib_terrain")
		.withSampler("Sampler7")
		.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
		.buildSnippet();

	public static final RenderPipeline SOLID = RenderPipeline.builder(SNIPPET)
		.withLocation("pipeline/bismuthlib_solid")
		.build();
	public static final RenderPipeline CUTOUT_MIPPED = RenderPipeline.builder(SNIPPET)
		.withLocation("pipeline/bismuthlib_cutout_mipped")
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.build();
	public static final RenderPipeline CUTOUT = RenderPipeline.builder(SNIPPET)
		.withLocation("pipeline/bismuthlib_cutout")
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.build();

	private static GpuBuffer uniformBuffer;

	private ColoredTerrainPipelines() {}

	public static RenderPipeline replace(RenderPipeline pipeline) {
		if (pipeline == RenderPipelines.SOLID) return SOLID;
		if (pipeline == RenderPipelines.CUTOUT_MIPPED) return CUTOUT_MIPPED;
		if (pipeline == RenderPipelines.CUTOUT) return CUTOUT;
		return pipeline;
	}

	/**
	 * Writes the light uniforms. Must run outside of a render pass.
	 */
	public static void updateUniforms() {
		if (uniformBuffer == null) {
			uniformBuffer = RenderSystem.getDevice().createBuffer(
				() -> "BismuthLib light UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, UNIFORM_BLOCK_SIZE
			);
		}

		LevelShaderData data = BismuthLibClient.getData();
		BlockPos center = data.getCenter();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer buffer = Std140Builder.onStack(stack, UNIFORM_BLOCK_SIZE)
				.putIVec3(center.getX(), center.getY(), center.getZ())
				.putIVec2(data.getDataWidth(), data.getDataHeight())
				.putInt(data.getDataSide())
				.putInt(BismuthLibClient.isFastLightActive() ? 1 : 0)
				.putFloat(BismuthLibClient.getTimeOfDay())
				.putFloat(CFOptions.getBrightness())
				.get();
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniformBuffer.slice(), buffer);
		}
	}

	public static void bind(RenderPass pass) {
		if (uniformBuffer == null) return;
		pass.bindSampler("Sampler7", BismuthLibClient.getData().getTexture().getTextureView());
		pass.setUniform(UNIFORM_BLOCK, uniformBuffer);
	}
}
