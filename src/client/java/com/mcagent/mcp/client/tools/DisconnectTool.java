package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.ConnectionState;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.mixin.ConnectScreenAccessor;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * disconnect: leave the current server or singleplayer world (or cancel an in-flight
 * connection) and return to the title screen, keeping the game and MCP server running.
 */
public final class DisconnectTool implements McpTool {
	private static final int CLEAR_TIMEOUT_MS = 3000;

	private final GameBridge bridge;

	public DisconnectTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "disconnect";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.SESSION;
	}

	@Override
	public String description() {
		return "Leave the current server or singleplayer world (or cancel a connection attempt) and return "
				+ "to the title screen. The Minecraft client and the MCP server keep running. Returns "
				+ "wasInWorld, wasMultiplayer and the resulting connection state. Safe to call at the menu.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		final JsonObject result = bridge.callOnGameThread(() -> {
			final Minecraft mc = Minecraft.getInstance();
			final boolean wasInWorld = mc.level != null;
			final boolean wasMultiplayer = mc.getCurrentServer() != null;
			final boolean wasConnecting = mc.screen instanceof ConnectScreen;
			final boolean wasFailed = mc.screen instanceof DisconnectedScreen;

			if (wasInWorld) {
				mc.disconnectFromWorld(Component.literal("mc-agent-mcp requested disconnect"));
			} else if (wasConnecting) {
				final Connection connection = ((ConnectScreenAccessor) mc.screen).mcagent$connection();
				if (connection != null) {
					connection.disconnect(Component.literal("mc-agent-mcp cancelled the connection"));
				}
				mc.setScreen(new TitleScreen());
			} else if (wasFailed) {
				mc.setScreen(new TitleScreen());
			} else if (mc.getConnection() != null) {
				mc.disconnect(new TitleScreen(), true, true);
				mc.setScreen(new TitleScreen());
			}

			final JsonObject o = new JsonObject();
			o.addProperty("disconnected", wasInWorld || wasConnecting || wasFailed);
			o.addProperty("wasInWorld", wasInWorld);
			o.addProperty("wasMultiplayer", wasMultiplayer);
			o.addProperty("wasConnecting", wasConnecting);
			o.addProperty("wasFailedScreen", wasFailed);
			return o;
		});

		awaitClear();
		result.add("connection", bridge.callOnGameThread(() -> ConnectionState.describeState(Minecraft.getInstance())));
		return result;
	}

	/** The client clears its level over the next ticks; report only once it is actually gone. */
	private void awaitClear() {
		for (int i = 0; i < CLEAR_TIMEOUT_MS / 50; i++) {
			final boolean clear = bridge.callOnGameThread(() -> {
				final Minecraft mc = Minecraft.getInstance();
				return mc.level == null && !(mc.screen instanceof ConnectScreen);
			});
			if (clear) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
