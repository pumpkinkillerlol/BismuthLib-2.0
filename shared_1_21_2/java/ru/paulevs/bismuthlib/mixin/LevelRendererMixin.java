package ru.paulevs.bismuthlib.mixin;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.paulevs.bismuthlib.BismuthLibClient;
import ru.paulevs.bismuthlib.data.LevelShaderData;
import ru.paulevs.bismuthlib.gui.CFOptions;

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

	// CompiledShaderProgram.apply() binds samplers and uploads uniforms, so values set here are used by this layer.
	@Inject(method = "renderSectionLayer", at = @At(
		value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/CompiledShaderProgram;apply()V",
		shift = Shift.BEFORE
	))
	private void cf_onRenderChunkLayer(CallbackInfo info) {
		LevelShaderData data = BismuthLibClient.getData();
		CompiledShaderProgram shader = RenderSystem.getShader();
		if (shader == null) return;

		shader.bindSampler("Sampler7", data.getTexture().getId());

		Uniform uniform = shader.getUniform("playerSectionPos");
		if (uniform != null) {
			BlockPos center = data.getCenter();
			uniform.set(center.getX(), center.getY(), center.getZ());
		}

		uniform = shader.getUniform("dataScale");
		if (uniform != null) {
			uniform.set(data.getDataWidth(), data.getDataHeight());
		}

		uniform = shader.getUniform("dataSide");
		if (uniform != null) {
			uniform.set(data.getDataSide());
		}

		uniform = shader.getUniform("fastLight");
		if (uniform != null) {
			uniform.set(BismuthLibClient.isFastLightActive() ? 1 : 0);
		}

		uniform = shader.getUniform("timeOfDay");
		if (uniform != null && this.level != null) {
			uniform.set(BismuthLibClient.getTimeOfDay());
		}

		uniform = shader.getUniform("lightsBrightness");
		if (uniform != null) {
			uniform.set(CFOptions.getBrightness());
		}
	}

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void cf_onBlockChanged(BlockGetter blockGetter, BlockPos blockPos, BlockState oldState, BlockState newState, int flags, CallbackInfo info) {
		if (oldState != newState) {
			BismuthLibClient.onBlockChanged(blockPos);
		}
	}
}
