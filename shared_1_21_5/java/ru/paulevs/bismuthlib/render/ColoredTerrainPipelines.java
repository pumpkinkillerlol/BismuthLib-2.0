package ru.paulevs.bismuthlib.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import ru.paulevs.bismuthlib.BismuthLibClient;
import ru.paulevs.bismuthlib.data.LevelShaderData;
import ru.paulevs.bismuthlib.gui.CFOptions;

/**
 * Copies of the vanilla solid and cutout chunk pipelines that run the colored light terrain shader.
 * Only chunk section rendering switches to them, so other users of these render types stay vanilla.
 */
public final class ColoredTerrainPipelines {
	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
		.withVertexShader("core/bismuthlib_terrain")
		.withFragmentShader("core/bismuthlib_terrain")
		.withSampler("Sampler7")
		.withUniform("playerSectionPos", UniformType.IVEC3)
		.withUniform("dataScale", UniformType.IVEC3)
		.withUniform("dataSide", UniformType.INT)
		.withUniform("fastLight", UniformType.INT)
		.withUniform("timeOfDay", UniformType.FLOAT)
		.withUniform("lightsBrightness", UniformType.FLOAT)
		.buildSnippet();

	public static final RenderPipeline SOLID = RenderPipeline.builder(SNIPPET)
		.withLocation(id("pipeline/solid"))
		.build();
	public static final RenderPipeline CUTOUT_MIPPED = RenderPipeline.builder(SNIPPET)
		.withLocation(id("pipeline/cutout_mipped"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.build();
	public static final RenderPipeline CUTOUT = RenderPipeline.builder(SNIPPET)
		.withLocation(id("pipeline/cutout"))
		.withShaderDefine("ALPHA_CUTOUT", 0.1F)
		.build();

	private ColoredTerrainPipelines() {}

	public static RenderPipeline replace(RenderPipeline pipeline) {
		if (pipeline == RenderPipelines.SOLID) return SOLID;
		if (pipeline == RenderPipelines.CUTOUT_MIPPED) return CUTOUT_MIPPED;
		if (pipeline == RenderPipelines.CUTOUT) return CUTOUT;
		return pipeline;
	}

	public static boolean isColored(RenderPipeline pipeline) {
		return pipeline == SOLID || pipeline == CUTOUT_MIPPED || pipeline == CUTOUT;
	}

	public static void bind(RenderPass pass) {
		LevelShaderData data = BismuthLibClient.getData();
		BlockPos center = data.getCenter();
		pass.bindSampler("Sampler7", data.getTexture().getTexture());
		pass.setUniform("playerSectionPos", center.getX(), center.getY(), center.getZ());
		pass.setUniform("dataScale", data.getDataWidth(), data.getDataHeight(), 0);
		pass.setUniform("dataSide", data.getDataSide());
		pass.setUniform("fastLight", BismuthLibClient.isFastLightActive() ? 1 : 0);
		pass.setUniform("timeOfDay", BismuthLibClient.getTimeOfDay());
		pass.setUniform("lightsBrightness", CFOptions.getBrightness());
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(BismuthLibClient.MOD_ID, path);
	}
}
