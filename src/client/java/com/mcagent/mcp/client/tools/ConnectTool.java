package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.McpTool.ToolException;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;

/**
 * connect: start Minecraft's normal multiplayer connection lifecycle (DNS, authentication,
 * encryption, resource packs) without navigating the multiplayer menu.
 */
public final class ConnectTool implements McpTool {
	private static final int MAX_ADDRESS = 255;

	private final GameBridge bridge;

	public ConnectTool(GameBridge bridge) {
		this.bridge = bridge;
	}

	@Override
	public String name() {
		return "connect";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.SESSION;
	}

	@Override
	public String description() {
		return "Connect the client to a multiplayer server by address (host, host:port, or [ipv6]:port; "
				+ "port defaults to 25565). Uses Minecraft's normal connection and authentication flow. "
				+ "Returns immediately with connecting=true; poll 'observe' and read connection.state until "
				+ "it is 'connected', 'failed' (with a reason) or 'menu'. Refuses when already in a world or "
				+ "already connecting: call 'disconnect' first.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.string("address", "Server address, e.g. 'play.example.net:25565' or '[::1]:25565'.")
				.required("address")
				.build();
	}

	@Override
	public JsonObject call(JsonObject args) {
		final String address = Args.requireString(args, "address").trim();
		if (address.isEmpty() || address.length() > MAX_ADDRESS) {
			throw new ToolException("'address' must be 1-" + MAX_ADDRESS + " characters");
		}
		for (int i = 0; i < address.length(); i++) {
			final char c = address.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c)) {
				throw new ToolException("invalid server address '" + address
						+ "' (no spaces; use host or host:port, e.g. play.example.net:25565)");
			}
		}
		if (!ServerAddress.isValidAddress(address)) {
			throw new ToolException("invalid server address '" + address
					+ "' (use host or host:port, e.g. play.example.net:25565)");
		}
		final ServerAddress parsed = ServerAddress.parseString(address);

		return bridge.callOnGameThread(() -> {
			final Minecraft mc = Minecraft.getInstance();
			if (busy(mc)) {
				throw new ToolException("already in a world or connecting; call 'disconnect' before connecting");
			}

			final ServerData data = new ServerData(address, parsed.toString(), ServerData.Type.OTHER);
			ConnectScreen.startConnecting(new TitleScreen(), mc, parsed, data, false, null);

			final JsonObject o = new JsonObject();
			o.addProperty("connecting", true);
			o.addProperty("address", parsed.toString());
			o.addProperty("host", parsed.getHost());
			o.addProperty("port", parsed.getPort());
			o.addProperty("state", "connecting");
			o.addProperty("note", "connection is asynchronous; call observe and check connection.state");
			return o;
		});
	}

	private static boolean busy(Minecraft mc) {
		if (mc.level != null || mc.player != null || mc.screen instanceof ConnectScreen) {
			return true;
		}
		final ClientPacketListener listener = mc.getConnection();
		final Connection connection = listener == null ? null : listener.getConnection();
		return connection != null && (connection.isConnecting() || connection.isConnected());
	}
}
