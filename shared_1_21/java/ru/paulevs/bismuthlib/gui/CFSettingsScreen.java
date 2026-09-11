package ru.paulevs.bismuthlib.gui;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import ru.paulevs.bismuthlib.BismuthLibClient;

public class CFSettingsScreen extends OptionsSubScreen {
	public CFSettingsScreen(Screen screen, Options options) {
		super(screen, options, Component.translatable("bismuthlib.options.settings.title"));
	}

	@Override
	protected void addOptions() {
		this.list.addBig(CFOptions.MAP_RADIUS_XZ);
		this.list.addBig(CFOptions.MAP_RADIUS_Y);
		this.list.addBig(CFOptions.BRIGHTNESS);
		this.list.addSmall(CFOptions.OPTIONS);
	}

	@Override
	public void removed() {
		super.removed();
		CFOptions.save();
		BismuthLibClient.initData();
	}
}
