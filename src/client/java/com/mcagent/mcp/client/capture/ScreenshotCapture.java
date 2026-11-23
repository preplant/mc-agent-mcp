package com.mcagent.mcp.client.capture;

import com.mcagent.mcp.tools.McpTool.ToolException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Captures the window's default framebuffer (FBO 0) immediately before {@code glfwSwapBuffers},
 * so the image is exactly the frame the player sees: world, HUD, chat, titles, open inventory
 * and server GUIs, and overlays other mods draw at present time.
 *
 * <p>Design adapted from MC-Inspector's pre-swap capture (MIT, Copyright (c) 2026 Harrison).
 * Requests arrive from MCP HTTP threads and are fulfilled on the render thread; pixel
 * conversion, downscaling and encoding all happen off the render thread.
 */
public final class ScreenshotCapture {
	/** Static handle for {@code PresentCaptureMixin}, like {@code AgentControl.instance}. */
	public static volatile ScreenshotCapture instance;

	/** Hard cap before a broken/huge framebuffer allocates gigabytes. */
	private static final long MAX_FRAME_BYTES = 256L * 1024 * 1024;

	private Request pending;

	/** Queue one capture for the next presented frame. */
	public CompletableFuture<Frame> newCapture() {
		synchronized (this) {
			if (pending != null && !pending.future.isDone()) {
				throw new ToolException("another screenshot is already in progress; wait for it to finish");
			}

			final CompletableFuture<Frame> future = new CompletableFuture<>();
			pending = new Request(future);
			future.whenComplete((frame, error) -> {
				synchronized (ScreenshotCapture.this) {
					if (pending != null && pending.future == future) {
						pending = null;
					}
				}
			});

			return future;
		}
	}

	/** Drop the queued request, e.g. after the caller timed out. Render thread safe. */
	public void cancelPending(String reason) {
		final Request request;
		synchronized (this) {
			request = pending;
			pending = null;
		}
		if (request != null) {
			request.future.completeExceptionally(new ToolException(reason));
		}
	}

	/** Called on the render thread right before {@code glfwSwapBuffers}. */
	public void beforeSwap() {
		final Request request;
		synchronized (this) {
			request = pending;
			pending = null;
		}
		if (request == null || request.future.isDone()) {
			return;
		}

		try {
			final Minecraft mc = Minecraft.getInstance();
			if (mc.getWindow() == null) {
				throw new ToolException("no game window");
			}

			final int[] width = new int[1];
			final int[] height = new int[1];
			GLFW.glfwGetFramebufferSize(mc.getWindow().handle(), width, height);
			final int w = width[0];
			final int h = height[0];
			if (w <= 0 || h <= 0) {
				throw new ToolException("the framebuffer is empty (" + w + "x" + h + ")");
			}
			final long bytes = (long) w * h * 4L;
			if (bytes > MAX_FRAME_BYTES) {
				throw new ToolException("framebuffer is too large to capture (" + w + "x" + h + ")");
			}

			final ByteBuffer buffer = MemoryUtil.memAlloc((int) bytes);
			try {
				final int prevFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
				final int prevPack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
				try {
					GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
					GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
					GL11.glReadBuffer(GL11.GL_BACK);
					GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
				} finally {
					GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPack);
					GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFbo);
				}

				final byte[] pixels = new byte[(int) bytes];
				buffer.get(pixels);
				final ClientLevel level = mc.level;
				request.future.complete(new Frame(w, h, pixels,
						level == null ? -1L : level.getGameTime(), System.currentTimeMillis()));
			} finally {
				MemoryUtil.memFree(buffer);
			}
		} catch (Throwable t) {
			request.future.completeExceptionally(t instanceof ToolException ? t
					: new ToolException("frame capture failed: " + t.getClass().getSimpleName()
					+ (t.getMessage() == null ? "" : ": " + t.getMessage())));
		}
	}

	/**
	 * Raw pixels from the presented frame. Rows are bottom-up OpenGL order; the tool flips
	 * and encodes them off the render thread.
	 */
	public record Frame(int width, int height, byte[] rgba, long gameTime, long capturedAt) {
	}

	private record Request(CompletableFuture<Frame> future) {
	}
}
