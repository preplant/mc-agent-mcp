package com.mcagent.mcp.client;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.mixin.DisconnectedScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Multiplayer/singleplayer session state for {@code observe} and the connection tools.
 * The client owns connection state; this class only describes it and remembers the last
 * reason a world was left, which vanilla otherwise drops from view.
 */
public final class ConnectionState {
	/** Text of the last {@code Minecraft.disconnectFromWorld} reason ("" when null). */
	private static volatile String lastDisconnect;
	private static volatile long lastDisconnectAt;

	private ConnectionState() {
	}

	/** Called from {@code MinecraftMixin} before a world is torn down. */
	public static void recordDisconnect(Component reason) {
		lastDisconnect = reason == null ? "" : reason.getString();
		lastDisconnectAt = System.currentTimeMillis();
	}

	/** Game thread only: connection state, current server and any disconnect reason. */
	public static JsonObject describeState(Minecraft mc) {
		final JsonObject o = new JsonObject();
		final ClientPacketListener listener = mc.getConnection();
		final Connection connection = listener == null ? null : listener.getConnection();

		final String state;
		if (mc.level != null && mc.player != null) {
			state = "connected";
		} else if (connection != null && (connection.isConnecting() || connection.isConnected())) {
			state = "connecting";
		} else if (mc.screen instanceof ConnectScreen) {
			state = "connecting";
		} else if (mc.screen instanceof DisconnectedScreen) {
			state = "failed";
		} else {
			state = "menu";
		}
		o.addProperty("state", state);
		o.addProperty("singleplayer", mc.hasSingleplayerServer());

		final ServerData server = mc.getCurrentServer();
		if (server != null) {
			o.addProperty("serverName", Objects.toString(server.name, ""));
			o.addProperty("address", Objects.toString(server.ip, ""));
		}

		final DisconnectionDetails details = details(mc, connection);
		if (details != null && details.reason() != null) {
			o.addProperty("reason", details.reason().getString());
		}

		if (lastDisconnect != null) {
			final JsonObject last = new JsonObject();
			last.addProperty("reason", lastDisconnect.isEmpty() ? "disconnected" : lastDisconnect);
			last.addProperty("at", lastDisconnectAt);
			o.add("lastDisconnect", last);
		}

		return o;
	}

	private static DisconnectionDetails details(Minecraft mc, Connection connection) {
		if (mc.screen instanceof DisconnectedScreen screen) {
			return ((DisconnectedScreenAccessor) screen).mcagent$details();
		}

		return connection == null ? null : connection.getDisconnectionDetails();
	}
}
