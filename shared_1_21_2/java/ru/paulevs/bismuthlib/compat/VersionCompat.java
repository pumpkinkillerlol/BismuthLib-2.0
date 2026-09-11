package ru.paulevs.bismuthlib.compat;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import ru.paulevs.bismuthlib.BismuthLibClient;
import ru.paulevs.bismuthlib.mixin.TextureAtlasSpriteAccessor;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Minecraft calls that differ between 1.21.x releases, so the shared logic can stay version independent.
 * Layers for releases with different APIs ship their own copy of this class.
 */
public final class VersionCompat {
	private VersionCompat() {}

	public static Optional<Block> getBlock(String id) {
		return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
	}

	public static void registerReloadListener(String name, Consumer<ResourceManager> listener) {
		final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(BismuthLibClient.MOD_ID, name);
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return id;
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				listener.accept(resourceManager);
			}
		});
	}

	public static int getMinSection(LevelHeightAccessor level) {
		return level.getMinSectionY();
	}

	/**
	 * Exclusive upper section bound (one above the top section), as returned by 1.20.x getMaxSection().
	 */
	public static int getMaxSection(LevelHeightAccessor level) {
		return level.getMaxSectionY() + 1;
	}

	public static boolean blocksLight(BlockState state, BlockGetter level, BlockPos pos) {
		return state.isSolidRender() || !state.propagatesSkylightDown();
	}

	/**
	 * First mip level of the block particle sprite, or null if there is none.
	 */
	public static NativeImage getParticleImage(BlockState state) {
		BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
		if (model == null) return null;
		SpriteContents contents = model.getParticleIcon().contents();
		if (contents == null) return null;
		NativeImage[] images = ((TextureAtlasSpriteAccessor) contents).cf_getImages();
		return images != null && images.length > 0 ? images[0] : null;
	}

	public static int getPixelABGR(NativeImage image, int x, int y) {
		return swapRedBlue(image.getPixel(x, y));
	}

	public static float getTimeOfDay(ClientLevel level) {
		return level.getTimeOfDay(0);
	}

	/**
	 * Converts between ARGB (NativeImage accessors since 1.21.2) and ABGR (light data) color order.
	 */
	public static int swapRedBlue(int color) {
		return (color & 0xFF00FF00) | ((color >> 16) & 0xFF) | ((color & 0xFF) << 16);
	}
}
