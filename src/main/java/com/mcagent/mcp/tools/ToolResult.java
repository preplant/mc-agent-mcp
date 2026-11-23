package com.mcagent.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * One MCP {@code tools/call} result: content blocks plus an optional structured payload.
 *
 * Text tools expose their JSON payload as a single text block (and mirror it into
 * {@code structuredContent}). Media tools return a real MCP image block; the metadata is
 * added as a second text block so an agent can reason about resolution without base64.
 */
public record ToolResult(JsonArray content, JsonObject structured, boolean isError) {

	/** A plain JSON tool result: one text block plus structuredContent. */
	public static ToolResult json(JsonObject payload) {
		return json(payload, false);
	}

	/** A plain JSON result that is also flagged as a user-facing failure. */
	public static ToolResult json(JsonObject payload, boolean isError) {
		final JsonArray blocks = new JsonArray();
		blocks.add(textBlock(payload.toString()));

		return new ToolResult(blocks, payload, isError);
	}

	/**
	 * An image result: {@code {type:"image",data,mimeType}} followed by a text block carrying
	 * {@code metadata}. {@code data} is standard base64 without a data-URL prefix.
	 */
	public static ToolResult image(String data, String mimeType, JsonObject metadata) {
		final JsonObject image = new JsonObject();
		image.addProperty("type", "image");
		image.addProperty("data", data);
		image.addProperty("mimeType", mimeType);

		final JsonArray blocks = new JsonArray();
		blocks.add(image);
		if (metadata != null) {
			blocks.add(textBlock(metadata.toString()));
		}

		return new ToolResult(blocks, metadata, false);
	}

	/** A user-facing failure: isError=true, with the message both as text and as structuredContent. */
	public static ToolResult error(String message) {
		final JsonObject payload = new JsonObject();
		payload.addProperty("error", message);

		final JsonArray blocks = new JsonArray();
		blocks.add(textBlock(payload.toString()));

		return new ToolResult(blocks, payload, true);
	}

	/** The MCP result object as sent on the wire. */
	public JsonObject toJson() {
		final JsonObject result = new JsonObject();
		result.add("content", content);
		if (structured != null) {
			result.add("structuredContent", structured);
		}
		if (isError) {
			result.addProperty("isError", true);
		}

		return result;
	}

	private static JsonObject textBlock(String text) {
		final JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);

		return block;
	}
}
