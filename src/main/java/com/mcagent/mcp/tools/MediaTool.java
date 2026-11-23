package com.mcagent.mcp.tools;

import com.google.gson.JsonObject;

/**
 * A tool whose result may contain MCP image content. Media tools cannot be carried by
 * {@code batch} (the batch payload is JSON-only), so they are never batchable.
 */
public interface MediaTool extends McpTool {

	/** Build the full result, including image blocks. Runs on the calling MCP thread. */
	ToolResult mediaResult(JsonObject args);

	@Override
	default JsonObject call(JsonObject args) {
		throw new ToolException("tool '" + name() + "' returns media; call it directly through tools/call");
	}

	@Override
	default boolean batchable() {
		return false;
	}
}
