package com.mcagent.mcp.client.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import java.util.Locale;
import java.util.Objects;

/** list_servers: the saved multiplayer entries in this client profile, read-only. */
public final class ListServersTool implements McpTool {
	private final GameBridge bridge;

	public ListServersTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "list_servers";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "List the saved multiplayer servers in the running client's profile: display name, address "
				+ "and type (other/lan/realm). Read-only; use 'connect' with an address to join one.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object().build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() -> {
			final ServerList list = new ServerList(Minecraft.getInstance());
			list.load();

			final JsonArray servers = new JsonArray();
			for (int i = 0; i < list.size(); i++) {
				final ServerData server = list.get(i);
				final JsonObject entry = new JsonObject();
				entry.addProperty("name", Objects.toString(server.name, ""));
				entry.addProperty("address", Objects.toString(server.ip, ""));
				entry.addProperty("type", server.type().name().toLowerCase(Locale.ROOT));
				servers.add(entry);
			}

			final JsonObject o = new JsonObject();
			o.add("servers", servers);
			o.addProperty("count", servers.size());
			return o;
		});
	}
}
