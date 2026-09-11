package ru.paulevs.bismuthlib.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.paulevs.bismuthlib.BismuthLibClient;
import ru.paulevs.bismuthlib.render.ColoredTerrainPipelines;

import java.util.Collection;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow private @Nullable ClientLevel level;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void cf_onRenderLevel(CallbackInfo info) {
		if (this.level != null) {
			Vec3 position = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
			BismuthLibClient.update(
				this.level,
				Mth.floor(position.x / 16.0),
				Mth.floor(position.y / 16.0),
				Mth.floor(position.z / 16.0)
			);
		}
	}

	@ModifyExpressionValue(method = "renderSectionLayer", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/RenderType;getRenderPipeline()Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
	))
	private RenderPipeline cf_useColoredPipeline(RenderPipeline pipeline) {
		return ColoredTerrainPipelines.replace(pipeline);
	}

	// Bound right before drawing so the vanilla Sampler0-11 loop cannot overwrite Sampler7.
	@WrapOperation(method = "renderSectionLayer", at = @At(
		value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;)V"
	))
	private void cf_bindColoredLight(
		RenderPass pass, Collection<RenderPass.Draw> draws, @Nullable GpuBuffer indexBuffer, @Nullable VertexFormat.IndexType indexType,
		Operation<Void> original, @Local RenderPipeline pipeline
	) {
		if (ColoredTerrainPipelines.isColored(pipeline)) {
			ColoredTerrainPipelines.bind(pass);
		}
		original.call(pass, draws, indexBuffer, indexType);
	}

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void cf_onBlockChanged(BlockGetter blockGetter, BlockPos blockPos, BlockState oldState, BlockState newState, int flags, CallbackInfo info) {
		if (oldState != newState) {
			BismuthLibClient.onBlockChanged(blockPos);
		}
	}
}
