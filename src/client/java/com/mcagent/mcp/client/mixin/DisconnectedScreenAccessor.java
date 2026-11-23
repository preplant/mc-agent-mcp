package com.mcagent.mcp.client.mixin;

import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets {@code observe} report the exact reason a connection attempt or session failed. */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {

	@Accessor("details")
	DisconnectionDetails mcagent$details();
}
