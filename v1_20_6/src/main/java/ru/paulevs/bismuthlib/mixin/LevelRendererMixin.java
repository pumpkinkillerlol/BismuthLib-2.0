package ru.paulevs.bismuthlib.mixin;

import org.joml.Matrix4f;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.paulevs.bismuthlib.BismuthLibClient;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
	@Shadow private @Nullable ClientLevel level;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void cf_onRenderLevel(float f, long l, boolean bl, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo info) {
		if (this.level != null) {
			BismuthLibClient.update(
				this.level,
				Mth.floor(camera.getPosition().x / 16.0),
				Mth.floor(camera.getPosition().y / 16.0),
				Mth.floor(camera.getPosition().z / 16.0)
			);
		}
	}

	@Inject(method = "renderSectionLayer", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V",
		shift = Shift.BEFORE
	))
	private void cf_onRenderChunkLayer(RenderType renderType, double d, double e, double f, Matrix4f matrix4f, Matrix4f matrix4f2, CallbackInfo info) {
		BismuthLibClient.bindWithUniforms();
	}

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void cf_onBlockChanged(BlockGetter blockGetter, BlockPos blockPos, BlockState oldState, BlockState newState, int flags, CallbackInfo info) {
		if (oldState != newState) {
			BismuthLibClient.onBlockChanged(blockPos);
		}
	}
}
