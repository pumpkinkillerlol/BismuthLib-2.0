package ru.paulevs.bismuthlib.mixin;

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

// Chunk layers are drawn by ChunkSectionsToRender since 1.21.6, see ChunkSectionsToRenderMixin.
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

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void cf_onBlockChanged(BlockGetter blockGetter, BlockPos blockPos, BlockState oldState, BlockState newState, int flags, CallbackInfo info) {
		if (oldState != newState) {
			BismuthLibClient.onBlockChanged(blockPos);
		}
	}
}
