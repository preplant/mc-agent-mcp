package com.mcagent.mcp.client;

import com.mcagent.mcp.client.inventory.MenuReader;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;

/**
 * Stable, agent-readable screen names. Reflection on screen classes returns intermediary
 * names in a production client (for example {@code class_476}), which are useless to a model.
 */
public final class ScreenNames {
	private ScreenNames() {
	}

	public static String describe(Screen screen) {
		if (screen == null) {
			return "none";
		}
		if (screen instanceof TitleScreen) {
			return "TitleScreen";
		}
		if (screen instanceof JoinMultiplayerScreen) {
			return "JoinMultiplayerScreen";
		}
		if (screen instanceof DirectJoinServerScreen) {
			return "DirectJoinServerScreen";
		}
		if (screen instanceof SelectWorldScreen) {
			return "SelectWorldScreen";
		}
		if (screen instanceof CreateWorldScreen) {
			return "CreateWorldScreen";
		}
		if (screen instanceof ConnectScreen) {
			return "ConnectScreen";
		}
		if (screen instanceof DisconnectedScreen) {
			return "DisconnectedScreen";
		}
		if (screen instanceof PauseScreen) {
			return "PauseScreen";
		}
		if (screen instanceof ChatScreen) {
			return "ChatScreen";
		}
		if (screen instanceof DeathScreen) {
			return "DeathScreen";
		}
		if (screen instanceof StatsScreen) {
			return "StatsScreen";
		}
		if (screen instanceof CreativeModeInventoryScreen) {
			return "CreativeInventoryScreen";
		}
		if (screen instanceof InventoryScreen) {
			return "InventoryScreen";
		}
		if (screen instanceof AbstractContainerScreen<?> container) {
			return "ContainerScreen[" + MenuReader.typeId(container.getMenu()) + "]";
		}

		return "Screen";
	}
}
