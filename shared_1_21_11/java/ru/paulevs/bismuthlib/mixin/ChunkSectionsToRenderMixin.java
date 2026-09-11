package ru.paulevs.bismuthlib.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.paulevs.bismuthlib.render.ColoredTerrainPipelines;

@Mixin(ChunkSectionsToRender.class)
public class ChunkSectionsToRenderMixin {
	// Buffer writes are not allowed while a render pass is open, so upload before renderGroup creates one.
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void cf_updateLightUniforms(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo info) {
		if (group == ChunkSectionLayerGroup.OPAQUE) {
			ColoredTerrainPipelines.updateUniforms();
		}
	}

	@Inject(method = "renderGroup", at = @At(
		value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderSystem;bindDefaultUniforms(Lcom/mojang/blaze3d/systems/RenderPass;)V",
		shift = Shift.AFTER
	))
	private void cf_bindColoredLight(ChunkSectionLayerGroup group, GpuSampler sampler, CallbackInfo info, @Local RenderPass renderPass) {
		if (group == ChunkSectionLayerGroup.OPAQUE) {
			ColoredTerrainPipelines.bind(renderPass);
		}
	}

	@ModifyExpressionValue(method = "renderGroup", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;pipeline()Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
	))
	private RenderPipeline cf_useColoredPipeline(RenderPipeline pipeline) {
		return ColoredTerrainPipelines.replace(pipeline);
	}
}
