package com.mcagent.mcp.client.mixin;

import com.mcagent.mcp.client.capture.ScreenshotCapture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the last moment before {@code glfwSwapBuffers}, where FBO 0 holds the presented frame. */
@Mixin(RenderSystem.class)
public abstract class PresentCaptureMixin {

	@Inject(method = "flipFrame", require = 1,
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
	private static void mcagent$beforeSwap(CallbackInfo ci) {
		final ScreenshotCapture capture = ScreenshotCapture.instance;
		if (capture != null) {
			capture.beforeSwap();
		}
	}
}
