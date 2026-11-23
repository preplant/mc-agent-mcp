package com.mcagent.mcp.client.tools;

import com.google.gson.JsonObject;
import com.mcagent.mcp.client.GameBridge;
import com.mcagent.mcp.client.ScreenNames;
import com.mcagent.mcp.client.capture.ScreenshotCapture;
import com.mcagent.mcp.client.capture.ScreenshotCapture.Frame;
import com.mcagent.mcp.tools.Args;
import com.mcagent.mcp.tools.McpTool.ToolException;
import com.mcagent.mcp.tools.MediaTool;
import com.mcagent.mcp.tools.Schema;
import com.mcagent.mcp.tools.ToolCategory;
import com.mcagent.mcp.tools.ToolResult;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * screenshot: the actual presented Minecraft frame, including open GUIs and HUD, as an MCP
 * image. Pixel readback happens on the render thread right before the buffer swap; JPEG/PNG
 * encoding happens on the calling MCP thread, so the game never waits for it.
 */
public final class ScreenshotTool implements MediaTool {
	/** A frame should arrive within a few render ticks; a frozen client never will. */
	private static final int TIMEOUT_MS = 10_000;
	private static final int MIN_WIDTH = 64;
	private static final int MAX_WIDTH = 4096;

	private final GameBridge bridge;
	private final ScreenshotCapture capture;

	public ScreenshotTool(GameBridge bridge, ScreenshotCapture capture) {
		this.bridge = bridge;
		this.capture = capture;
	}

	@Override
	public String name() {
		return "screenshot";
	}

	@Override
	public ToolCategory category() {
		return ToolCategory.PERCEPTION;
	}

	@Override
	public String description() {
		return "Capture the Minecraft window as an MCP image: the rendered world plus HUD, chat, titles, "
				+ "open inventory and server GUIs. 'format' is 'jpeg' (default) or 'png'; 'quality' is 1-100 "
				+ "for JPEG (default 85); 'maxWidth' (64-4096) downscales while keeping the aspect ratio. "
				+ "Returns the image plus a text block with width, height, format and screen metadata. "
				+ "Call it directly, not through 'batch'. It can fail when the window is minimized or the "
				+ "client is not rendering.";
	}

	@Override
	public JsonObject inputSchema() {
		return Schema.object()
				.enumeration("format", "Image format: 'jpeg' (default) or 'png'.", "jpeg", "png")
				.integer("quality", "JPEG quality 1-100 (default 85). Ignored for PNG.")
				.integer("maxWidth", "Optional downscale target width in pixels (64-4096), aspect ratio kept.")
				.build();
	}

	@Override
	public ToolResult mediaResult(JsonObject args) {
		final String format = Args.getEnum(args, "format", "jpeg", "jpeg", "png");
		final int quality = Args.getInt(args, "quality", 85, 1, 100);
		final int maxWidth = Args.has(args, "maxWidth")
				? Args.getInt(args, "maxWidth", 0, MIN_WIDTH, MAX_WIDTH) : 0;

		if (capture == null) {
			throw new ToolException("screenshot capture is not initialized");
		}
		if (Minecraft.getInstance().isSameThread()) {
			throw new ToolException("screenshot must be called from the MCP thread, not the game thread");
		}

		// Window/screen state must be read on the game thread. This also refuses minimized
		// windows before a capture is queued (they stop presenting frames).
		final JsonObject metadata = bridge.callOnGameThread(this::metadata);

		final CompletableFuture<Frame> future = capture.newCapture();
		final Frame frame;
		try {
			frame = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			capture.cancelPending("screenshot timed out");
			throw new ToolException("no frame was presented within " + (TIMEOUT_MS / 1000)
					+ " s (window minimized/occluded, or Minecraft is not rendering)");
		} catch (ExecutionException e) {
			final Throwable cause = e.getCause();
			if (cause instanceof ToolException toolError) throw toolError;
			throw new ToolException("screenshot failed: " + (cause == null ? e.getMessage() : cause.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ToolException("interrupted while waiting for the frame");
		}

		BufferedImage image = image(frame, maxWidth);
		final byte[] bytes;
		final String mime;
		try {
			if ("png".equals(format)) {
				bytes = png(image);
				mime = "image/png";
			} else {
				bytes = jpeg(rgb(image), quality);
				mime = "image/jpeg";
			}
		} catch (IOException e) {
			throw new ToolException("failed to encode the screenshot: " + e.getMessage());
		}

		metadata.addProperty("format", format);
		metadata.addProperty("mimeType", mime);
		metadata.addProperty("framebufferWidth", frame.width());
		metadata.addProperty("framebufferHeight", frame.height());
		metadata.addProperty("width", image.getWidth());
		metadata.addProperty("height", image.getHeight());
		metadata.addProperty("downscaled", image.getWidth() != frame.width());
		metadata.addProperty("bytes", bytes.length);
		metadata.addProperty("gameTime", frame.gameTime());
		metadata.addProperty("capturedAt", frame.capturedAt());
		if ("jpeg".equals(format)) {
			metadata.addProperty("quality", quality);
		}
		if (maxWidth > 0) {
			metadata.addProperty("maxWidth", maxWidth);
		}

		return ToolResult.image(Base64.getEncoder().encodeToString(bytes), mime, metadata);
	}

	/** Window, GUI and focus state; game thread only. */
	private JsonObject metadata() {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.getWindow() == null) {
			throw new ToolException("the Minecraft window is not ready yet");
		}
		if (mc.getWindow().isIconified() || mc.getWindow().isMinimized()) {
			throw new ToolException("the Minecraft window is minimized; it does not present frames, "
					+ "so no screenshot can be taken until it is restored");
		}

		final JsonObject o = new JsonObject();
		o.addProperty("windowWidth", mc.getWindow().getWidth());
		o.addProperty("windowHeight", mc.getWindow().getHeight());
		o.addProperty("guiScale", mc.getWindow().getGuiScale());
		o.addProperty("focused", GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), GLFW.GLFW_FOCUSED)
				== GLFW.GLFW_TRUE);
		if (mc.screen != null) {
			o.addProperty("screen", ScreenNames.describe(mc.screen));
			if (mc.screen.getTitle() != null) {
				o.addProperty("screenTitle", mc.screen.getTitle().getString());
			}
		}

		return o;
	}

	/** Convert bottom-up RGBA bytes into an upright ARGB image, optionally downscaled. */
	private static BufferedImage image(Frame frame, int maxWidth) {
		final int w = frame.width();
		final int h = frame.height();
		final byte[] rgba = frame.rgba();
		final BufferedImage source = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final int[] pixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
		for (int y = 0; y < h; y++) {
			final int src = (h - 1 - y) * w * 4; // GL rows are bottom-up
			final int dst = y * w;
			for (int x = 0; x < w; x++) {
				final int i = src + x * 4;
				pixels[dst + x] = 0xFF000000
						| ((rgba[i] & 0xFF) << 16)
						| ((rgba[i + 1] & 0xFF) << 8)
						| (rgba[i + 2] & 0xFF);
			}
		}

		return maxWidth > 0 && w > maxWidth ? scale(source, maxWidth) : source;
	}

	private static BufferedImage scale(BufferedImage source, int maxWidth) {
		final int w = maxWidth;
		final int h = Math.max(1, (int) Math.round(source.getHeight() * (w / (double) source.getWidth())));
		final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}

		return out;
	}

	/** JPEG has no alpha channel; flatten onto black. */
	private static BufferedImage rgb(BufferedImage source) {
		final BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
				BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = out.createGraphics();
		try {
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, out.getWidth(), out.getHeight());
			g.drawImage(source, 0, 0, null);
		} finally {
			g.dispose();
		}

		return out;
	}

	private static byte[] png(BufferedImage image) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);

		return out.toByteArray();
	}

	private static byte[] jpeg(BufferedImage image, int quality) throws IOException {
		final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		final ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(quality / 100.0f);

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		} finally {
			writer.dispose();
		}

		return bytes.toByteArray();
	}
}
