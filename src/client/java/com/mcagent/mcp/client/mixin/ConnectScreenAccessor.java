package com.mcagent.mcp.client.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets disconnect cancel an in-flight connection attempt instead of waiting for it to time out. */
@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {

	@Accessor("connection")
	Connection mcagent$connection();
}
