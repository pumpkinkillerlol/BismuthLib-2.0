package ru.paulevs.bismuthlib;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import ru.paulevs.bismuthlib.commands.PrintCommand;
import ru.paulevs.bismuthlib.data.BlockLights;
import ru.paulevs.bismuthlib.data.LevelShaderData;
import ru.paulevs.bismuthlib.data.info.ProviderLight;
import ru.paulevs.bismuthlib.data.info.SimpleLight;
import ru.paulevs.bismuthlib.data.transformer.ProviderLightTransformer;
import ru.paulevs.bismuthlib.data.transformer.SimpleLightTransformer;
import ru.paulevs.bismuthlib.gui.CFOptions;
import ru.paulevs.bismuthlib.mixin.TextureAtlasSpriteAccessor;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class BismuthLibClient implements ClientModInitializer {
	public static final String MOD_ID = "bismuthlib";

	private static LevelShaderData data;

	//private static GPULightPropagator gpuLight;
	private static final Gson GSON = new GsonBuilder().create();
	private static ResourceManager managerCache;
	private static boolean fastLight = false;
	private static boolean modifyLight = false;
	private static boolean brightSources = false;

	@Override
	public void onInitializeClient() {
		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			HudRenderCallback.EVENT.register((drawContext, delta) -> {
				drawContext.fill(1, 1, 100, 100, 0xFFFFFFFF);
			});
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			PrintCommand.register(dispatcher);
		});

		// Light is recomputed only when it can change: chunks appear/disappear or blocks change (LevelRendererMixin)
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
			if (data != null) data.onChunkLoad(level, chunk);
		});
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			if (data != null) data.onChunkUnload(level, chunk);
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null && data != null) data.clearLevel();
		});

		final ResourceLocation location = new ResourceLocation(MOD_ID, "resource_reloader");
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
			@Override
			public ResourceLocation getFabricId() {
				return location;
			}

			@Override
			public void onResourceManagerReload(ResourceManager resourceManager) {
				managerCache = resourceManager;
				loadBlocks(resourceManager);
			}
		});
	}

	private static void loadBlocks(ResourceManager resourceManager) {
		Set<Block> exclude = new HashSet<>();

		Map<ResourceLocation, Resource> list = resourceManager.listResources("lights", resourceLocation ->
			resourceLocation.getPath().endsWith(".json") && resourceLocation.getNamespace().equals(MOD_ID)
		);

		BlockLights.clear();
		Map<BlockState, Integer> colorMap = new HashMap<>();
		Map<BlockState, Integer> radiusMap = new HashMap<>();
		list.forEach((id, resource) -> {
			JsonObject obj = new JsonObject();

			try {
				BufferedReader reader = resource.openAsReader();
				obj = GSON.fromJson(reader, JsonObject.class);
				reader.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}

			final JsonObject storage = obj;
			storage.keySet().forEach(key -> {
				ResourceLocation blockID = new ResourceLocation(key);
				Optional<Block> optional = BuiltInRegistries.BLOCK.getOptional(blockID);
				if (optional.isPresent()) {
					Block block = optional.get();
					ImmutableList<BlockState> blockStates = block.getStateDefinition().getPossibleStates();
					exclude.add(block);

					JsonObject data = storage.getAsJsonObject(key);
					if (data.keySet().isEmpty()) {
						BlockLights.addLight(block, null);
					}
					else {
						radiusMap.clear();
						colorMap.clear();
						BlockColor provider = null;
						int providerIndex = 0;

						JsonElement element = data.get("color");
						if (element == null) throw new RuntimeException("Block " + blockID + " in " + id + " missing color element!");
						if (element.isJsonPrimitive()) {
							String preValue = element.getAsString();
							if (preValue.startsWith("provider")) {
								provider = ColorProviderRegistry.BLOCK.get(block);
								providerIndex = Integer.parseInt(preValue.substring(preValue.indexOf('=') + 1));
							}
							else {
								int value = Integer.parseInt(preValue, 16);
								blockStates.forEach(state -> colorMap.put(state, value));
							}
						}
						else {
							getValues(block, element.getAsJsonObject()).forEach((state, primitive) -> {
								int value = Integer.parseInt(primitive.getAsString(), 16);
								colorMap.put(state, value);
							});
						}

						element = data.get("radius");
						if (element == null) throw new RuntimeException("Block " + blockID + " in " + id + " missing radius element!");
						if (element.isJsonPrimitive()) {
							int value = element.getAsInt();
							blockStates.forEach(state -> radiusMap.put(state, value));
						}
						else {
							Map<BlockState, JsonPrimitive> values = getValues(block, element.getAsJsonObject());
							values.forEach((state, primitive) -> radiusMap.put(state, primitive.getAsInt()));
						}

						final int indexCopy = providerIndex;
						final BlockColor colorCopy = provider;
						blockStates.forEach(state -> {
							if (radiusMap.containsKey(state)) {
								int radius = radiusMap.get(state);
								if (colorCopy != null) {
									BlockLights.addLight(state, new ProviderLight(state, colorCopy, indexCopy, radius));
								}
								else if (colorMap.containsKey(state)) {
									int color = colorMap.get(state);
									BlockLights.addLight(state, new SimpleLight(color, radius));
								}
							}
						});
					}
				}
			});
		});

		BuiltInRegistries.BLOCK.stream().filter(block -> !exclude.contains(block)).forEach(block -> {
			block.getStateDefinition().getPossibleStates().stream().filter(state -> state.getLightEmission() > 0).forEach(state -> {
				int color = getBlockColor(state);
				int radius = state.getLightEmission();
				BlockLights.addLight(state, new SimpleLight(color, radius));
			});
		});

		list = resourceManager.listResources("transformers", resourceLocation ->
			resourceLocation.getPath().endsWith(".json") && resourceLocation.getNamespace().equals(MOD_ID)
		);

		float[] hsv = new float[3];
		list.forEach((id, resource) -> {
			JsonObject obj = new JsonObject();

			try {
				BufferedReader reader = resource.openAsReader();
				obj = GSON.fromJson(reader, JsonObject.class);
				reader.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}

			final JsonObject storage = obj;
			storage.keySet().forEach(key -> {
				ResourceLocation blockID = new ResourceLocation(key);
				Optional<Block> optional = BuiltInRegistries.BLOCK.getOptional(blockID);
				if (optional.isPresent()) {
					Block block = optional.get();
					ImmutableList<BlockState> blockStates = block.getStateDefinition().getPossibleStates();
					exclude.add(block);

					JsonObject data = storage.getAsJsonObject(key);
					if (data.keySet().isEmpty()) {
						BlockLights.addLight(block, null);
					}
					else {
						colorMap.clear();
						BlockColor provider = null;
						int providerIndex = 0;

						JsonElement element = data.get("color");
						if (element == null) throw new RuntimeException("Block " + blockID + " in " + id + " missing color element!");
						if (element.isJsonPrimitive()) {
							String preValue = element.getAsString();
							if (preValue.startsWith("provider")) {
								provider = ColorProviderRegistry.BLOCK.get(block);
								providerIndex = Integer.parseInt(preValue.substring(preValue.indexOf('=') + 1));
							}
							else {
								int value = Integer.parseInt(preValue, 16);
								if (CFOptions.isBrightSources()) {
									int r = (value >> 16) & 255;
									int g = (value >> 8) & 255;
									int b = value & 255;
									Color.RGBtoHSB(r, g, b, hsv);
									hsv[2] = 1.0F;
									value = Color.HSBtoRGB(hsv[0], hsv[1], hsv[2]);
								}
								final int valueCopy = value;
								blockStates.forEach(state -> colorMap.put(state, valueCopy));
							}
						}
						else {
							getValues(block, element.getAsJsonObject()).forEach((state, primitive) -> {
								int value = Integer.parseInt(primitive.getAsString(), 16);
								colorMap.put(state, value);
							});
						}

						final int indexCopy = providerIndex;
						final BlockColor colorCopy = provider;
						blockStates.forEach(state -> {
							if (colorCopy != null) {
								BlockLights.addTransformer(state, new ProviderLightTransformer(state, colorCopy, indexCopy));
							}
							else if (colorMap.containsKey(state)) {
								BlockLights.addTransformer(state, new SimpleLightTransformer(colorMap.get(state)));
							}
						});
					}
				}
			});
		});

		// Light sources changed, recompute visible light
		if (data != null) data.resetAll();
	}

	private static Map<BlockState, JsonPrimitive> getValues(Block block, final JsonObject values) {
		ImmutableList<BlockState> states = block.getStateDefinition().getPossibleStates();
		Map<BlockState, JsonPrimitive> result = new HashMap<>();
		values.keySet().forEach(stateString -> {
			JsonPrimitive value = values.get(stateString).getAsJsonPrimitive();
			Map<String, String> propValues = new HashMap<>();
			Arrays.stream(stateString.split(",")).forEach(entry -> {
				String[] pair = entry.split("=");
				propValues.put(pair[0], pair[1]);
			});
			states.forEach(state -> {
				AtomicBoolean add = new AtomicBoolean(true);
				propValues.forEach((name, val) -> {
					if (!hasPropertyWithValue(state, name, val)) {
						add.set(false);
					}
				});
				if (add.get()) {
					result.put(state, value);
				}
			});
		});
		return result;
	}

	private static boolean hasPropertyWithValue(BlockState state, String propertyName, String propertyValue) {
		Collection<Property<?>> properties = state.getProperties();
		Iterator<Property<?>> iterator = properties.iterator();
		boolean result = false;
		while (iterator.hasNext()) {
			Property<?> property = iterator.next();
			if (property.getName().equals(propertyName) && state.getValue(property).toString().equals(propertyValue)) {
				result = true;
				break;
			}
		}
		return result;
	}

	public static void initData() {
		int w = CFOptions.getMapRadiusXZ();
		int h = CFOptions.getMapRadiusY();
		int count = CFOptions.getThreadCount();

		if (data == null) {
			data = new LevelShaderData(w, h, count);
		}
		else if (data.getDataWidth() != w || data.getDataHeight() != h || count != data.getThreadCount()) {
			data.dispose();
			data = new LevelShaderData(w, h, count);
		}

		boolean fast = CFOptions.isFastLight();
		boolean modify = CFOptions.modifyColor();
		boolean bright = CFOptions.isBrightSources();
		if (fast != fastLight || modify != modifyLight || bright != brightSources) {
			if (managerCache != null && bright != brightSources) {
				loadBlocks(managerCache);
			}
			fastLight = fast;
			modifyLight = modify;
			brightSources = bright;
			data.resetAll();
		}
	}

	public static void update(Level level, int cx, int cy, int cz) {
		data.update(level, cx, cy, cz);
	}

	public static void updateSection(int cx, int cy, int cz) {
		data.markSection(cx, cy, cz);
	}

	public static void onBlockChanged(BlockPos pos) {
		if (data == null) return;
		data.markBlock(pos, CFOptions.isFastLight() ? 1 : BlockLights.getMaxReach());
	}

	public static void bindWithUniforms() {
		int textureId = data.getTextureId();
		RenderSystem.setShaderTexture(7, textureId);
		ShaderInstance shader = RenderSystem.getShader();
		// Samplers are copied from RenderSystem before this point, set it directly so a regrown light texture is used immediately
		shader.setSampler("Sampler7", textureId);

		Uniform uniform = shader.getUniform("playerSectionPos");
		if (uniform != null) {
			uniform.set(data.getCenterX(), data.getCenterY(), data.getCenterZ());
		}

		uniform = shader.getUniform("dataScale");
		if (uniform != null) {
			uniform.set(data.getDataWidth(), data.getDataHeight());
		}

		// Row where light atlas slots start (see LightAtlas)
		uniform = shader.getUniform("dataSide");
		if (uniform != null) {
			uniform.set(data.getSlotBase());
		}

		uniform = shader.getUniform("fastLight");
		if (uniform != null) {
			uniform.set(fastLight ? 1 : 0);
		}

		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			uniform = shader.getUniform("timeOfDay");
			if (uniform != null) {
				uniform.set(level.getTimeOfDay(0));
			}
		}

		uniform = shader.getUniform("lightsBrightness");
		if (uniform != null) {
			uniform.set(CFOptions.getBrightness());
		}
	}

	private static int getBlockColor(BlockState state) {
		BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
		if (model != null) {
			SpriteContents contents = model.getParticleIcon().contents();
			if (contents != null) {
				TextureAtlasSpriteAccessor accessor = (TextureAtlasSpriteAccessor) contents;
				NativeImage[] images = accessor.cf_getImages();
				if (images != null && images.length > 0) {
					return getAverageBright(images[0]);
				}
			}
		}
		return 0xFFFFFF;
	}

	private static int getAverageBright(NativeImage img) {
		long cr = 0;
		long cg = 0;
		long cb = 0;
		long count = 0;
		for (int x = 0; x < img.getWidth(); x++) {
			for (int y = 0; y < img.getHeight(); y++) {
				int abgr = img.getPixelRGBA(x, y);
				int r = abgr & 255;
				int g = (abgr >> 8) & 255;
				int b = (abgr >> 16) & 255;
				if (r > 200 || g > 200 || b > 200) {
					cr += r;
					cg += g;
					cb += b;
					count ++;
				}
			}
		}
		if (count == 0) return 0xffffff;
		int r = (int) (cr / count);
		int g = (int) (cg / count);
		int b = (int) (cb / count);
		return r << 16 | g << 8 | b;
	}
}
