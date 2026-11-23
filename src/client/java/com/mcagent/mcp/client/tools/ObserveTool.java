package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mcagent.mcp.client.ChatLog;
import com.mcagent.mcp.client.ConnectionState;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.ScreenNames;
import com.mcagent.mcp.client.blocks.BlockScanner;
import com.mcagent.mcp.client.control.AgentControl;
import com.mcagent.mcp.client.inventory.MenuReader;
import com.mcagent.mcp.client.reflex.Reflexes;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.ToolCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Snapshot of the local player: position, rotation, health, food, dimension, world time.
 * Read-only; runs on the game thread via {@link GameBridge}.
 */
public final class ObserveTool implements McpTool {
	private static final int MAX_EVENTS = 12;

	private final GameBridge bridge;
	private final ChatLog events;
	/** Events are reported once: each observe returns what happened since the previous one. */
	private final AtomicLong lastEventSeq = new AtomicLong();

	public ObserveTool(GameBridge bridge, ChatLog events) {
		this.bridge = bridge;
		this.events = events;
	}

	@Override
	public String name() {
		return "observe";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Observe the local player and world: position, rotation, health, hunger, armor, air, held item, "
				+ "attack cooldown, open screen, crosshair target, dead flag, dimension, time of day, weather. "
				+ "Also 'threats' (hostile mobs within 16 blocks, nearest first) and 'events' (what happened since "
				+ "the last observe: damage taken, reflex actions, kills). 'gui' tells whether a screen is open and "
				+ "intercepting input (open=true means normal gameplay actions and chat commands may not reach the "
				+ "world; read containers with get_container, or use screenshot for the visual layout). 'connection' "
				+ "reports menu/connecting/connected/failed, the server address and any disconnect reason. "
				+ "Call this first each turn. Returns inWorld=false when no world is loaded.";
	}

	@Override
	public JsonObject inputSchema() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", new JsonObject());
		return schema;
	}

	@Override
	public JsonObject call(JsonObject args) {
		return bridge.callOnGameThread(() -> {
			JsonObject o = new JsonObject();
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer p = mc.player;
			if (mc.level == null || p == null) {
				o.addProperty("inWorld", false);
				o.add("gui", gui(mc));
				o.add("connection", ConnectionState.describeState(mc));
				return o;
			}
			o.addProperty("inWorld", true);
			o.addProperty("name", p.getName().getString());
			JsonObject pos = new JsonObject();
			pos.addProperty("x", p.getX());
			pos.addProperty("y", p.getY());
			pos.addProperty("z", p.getZ());
			o.add("position", pos);
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			o.addProperty("onGround", p.onGround());
			o.addProperty("health", p.getHealth());
			o.addProperty("maxHealth", p.getMaxHealth());
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("saturation", p.getFoodData().getSaturationLevel());
			o.addProperty("dimension", mc.level.dimension().identifier().toString());
			o.addProperty("gameTime", mc.level.getGameTime());
			// Everything below is what an agent needs to decide its next action in one call.
			o.addProperty("dead", p.isDeadOrDying());
			o.addProperty("armor", p.getArmorValue());
			o.addProperty("air", p.getAirSupply());
			o.addProperty("inWater", p.isInWater());
			o.addProperty("onFire", p.isOnFire());
			o.addProperty("xpLevel", p.experienceLevel);
			o.addProperty("timeOfDay", mc.level.getDayTime() % 24000L);
			o.addProperty("raining", mc.level.isRaining());
			o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
			JsonObject held = Json.item(p.getMainHandItem());
			if (held != null) o.add("mainHand", held);
			o.addProperty("attackStrength", Json.round2(p.getAttackStrengthScale(0.5F)));
			if (mc.screen != null) o.addProperty("screen", ScreenNames.describe(mc.screen));
			o.add("gui", gui(mc));
			o.add("connection", ConnectionState.describeState(mc));
			o.add("crosshair", crosshair(mc));
			if (AgentControl.instance != null) o.add("agent", AgentControl.instance.status());
			o.add("threats", Reflexes.describeThreats(mc, p, 6));
			if (events != null) {
				JsonObject ev = events.since(lastEventSeq.get(), MAX_EVENTS);
				lastEventSeq.set(ev.get("lastSeq").getAsLong());
				JsonArray lines = new JsonArray();
				for (var e : ev.getAsJsonArray("messages")) lines.add(e.getAsJsonObject().get("text"));
				if (!lines.isEmpty()) o.add("events", lines);
			}
			return o;
		});
	}

	/**
	 * Whether a screen is intercepting input and, for container screens, a cheap summary.
	 * Full slot contents stay behind get_container so observe remains the cheap default.
	 */
	private static JsonObject gui(Minecraft mc) {
		final JsonObject o = new JsonObject();
		final boolean open = mc.screen != null;
		o.addProperty("open", open);
		o.addProperty("inputCaptured", open);
		if (!open) {
			return o;
		}

		o.addProperty("screen", ScreenNames.describe(mc.screen));
		final Component title = mc.screen.getTitle();
		if (title != null) {
			o.addProperty("title", title.getString());
		}
		o.addProperty("pauseScreen", mc.screen.isPauseScreen());
		final boolean inventoryBased = mc.screen instanceof AbstractContainerScreen<?>;
		o.addProperty("inventoryBased", inventoryBased);

		if (!inventoryBased || mc.player == null) {
			return o;
		}

		final AbstractContainerMenu menu = mc.player.containerMenu;
		o.addProperty("containerType", MenuReader.typeId(menu));
		o.addProperty("containerId", menu.containerId);
		int filled = 0;
		int empty = 0;
		for (final Slot slot : menu.slots) {
			if (MenuReader.isPlayerSlot(slot)) {
				continue;
			}
			if (slot.hasItem()) {
				filled++;
			} else {
				empty++;
			}
		}
		o.addProperty("containerSlotsFilled", filled);
		o.addProperty("containerSlotsEmpty", empty);

		return o;
	}

	/** What the crosshair points at: {type:"block",pos,block,face} | {type:"entity",id,entity} | {type:"miss"}. */
	private static JsonObject crosshair(Minecraft mc) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
			o.addProperty("type", "block");
			o.add("pos", Json.pos(bh.getBlockPos()));
			o.addProperty("block", BlockScanner.idOf(mc.level.getBlockState(bh.getBlockPos())));
			o.addProperty("face", bh.getDirection().getName());
		} else if (hit instanceof EntityHitResult eh) {
			o.addProperty("type", "entity");
			o.addProperty("id", eh.getEntity().getId());
			o.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(eh.getEntity().getType()).toString());
		} else {
			o.addProperty("type", "miss");
		}
		return o;
	}
}
