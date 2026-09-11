package ru.paulevs.bismuthlib.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.paulevs.bismuthlib.gui.CFSettingsScreen;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
	private OptionsScreenMixin() {
		super(Component.empty());
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void cf_addButton(CallbackInfo info) {
		OptionsScreen self = (OptionsScreen) (Object) this;
		int x = this.width / 2 - 180;
		int y = this.height / 6 + 72 - 8;
		this.addRenderableWidget(
			Button.builder(Component.translatable("bismuthlib.options.button"), button -> {
				Minecraft.getInstance().setScreen(new CFSettingsScreen(self, Minecraft.getInstance().options));
			}).bounds(x, y, 20, 20).build()
		);
	}
}
