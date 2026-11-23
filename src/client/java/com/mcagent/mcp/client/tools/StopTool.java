package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.ScreenNames;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/** stop: release all held keys, cancel mining, optionally close the open screen. */
public final class StopTool implements McpTool {
	private final GameBridge bridge;
	private final AgentControl control;

	public StopTool(GameBridge bridge, AgentControl control) {
		this.bridge = bridge;
		this.control = control;
	}

	@Override
	public String name() {
		return "stop";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.CONTROL;
	}

	@Override
	public String description() {
		return "Stop everything the agent is doing: release movement and use keys, cancel break_block. "
				+ "closeScreen=true also closes an open GUI (chest, crafting table, pause menu); after dying use respawn instead.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().bool("closeScreen", "Also close the open container/GUI (default false).").build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		boolean closeScreen = Args.getBool(args, "closeScreen", false);
		return bridge.callOnGameThread(() -> {
			Minecraft mc = Minecraft.getInstance();
			JsonObject o = control.stopAll(mc);
			if (closeScreen && mc.screen != null
					&& !(mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) {
				o.addProperty("closedScreen", ScreenNames.describe(mc.screen));
				if (mc.player != null) {
					mc.player.closeContainer();
				}
				// A screen can be open with no player (failed connection, title menu): go back
				// to the title instead of leaving the client on an error screen.
				mc.setScreen(mc.level == null ? new TitleScreen() : null);
			}
			return o;
		});
	}
}
