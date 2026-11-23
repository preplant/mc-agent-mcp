package com.mcagent.mcp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mcagent.mcp.tools.McpTool;
import com.mcagent.mcp.tools.MediaTool;
import com.mcagent.mcp.tools.ToolPolicy;
import com.mcagent.mcp.tools.ToolRegistry;
import com.mcagent.mcp.tools.ToolResult;

/**
 * Minimal MCP protocol handler (spec revision 2025-06-18), covering:
 * initialize, ping, notifications/initialized, tools/list, tools/call.
 * Transport concerns (HTTP, sessions) live in {@link McpHttpServer}.
 */
public final class McpProtocol {
	public static final String PROTOCOL_VERSION = "2025-06-18";

	private final ToolRegistry registry;
	private final String serverName;
	private final String serverVersion;
	private final String instructions;
	private final ToolPolicy policy;

	public McpProtocol(ToolRegistry registry, String serverName, String serverVersion) {
		this(registry, serverName, serverVersion, null);
	}

	public McpProtocol(ToolRegistry registry, String serverName, String serverVersion, String instructions) {
		this(registry, serverName, serverVersion, instructions, ToolPolicy.allowAll());
	}

	/**
	 * @param instructions optional usage guide returned by initialize; MCP clients typically add
	 *                     it to the model's system prompt
	 * @param policy       permissions and rate limits applied to tools/list and tools/call
	 */
	public McpProtocol(ToolRegistry registry, String serverName, String serverVersion, String instructions,
					   ToolPolicy policy) {
		this.registry = registry;
		this.serverName = serverName;
		this.serverVersion = serverVersion;
		this.instructions = instructions;
		this.policy = policy;
	}

	/**
	 * Handle one JSON-RPC message.
	 *
	 * @return the response object, or {@code null} for notifications (which must not be answered)
	 */
	public JsonObject handle(JsonObject msg) {
		JsonElement id = msg.get("id"); // null or JsonNull -> notification
		boolean isNotification = id == null || id instanceof JsonNull;
		String method = msg.has("method") && msg.get("method").isJsonPrimitive()
				? msg.get("method").getAsString() : null;

		if (method == null) {
			return isNotification ? null
					: JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "missing or invalid 'method'");
		}

		// Notifications (no id): never answered.
		if (isNotification) {
			// notifications/initialized, notifications/cancelled, ... — nothing to do yet.
			return null;
		}

		JsonObject params = msg.has("params") && msg.get("params").isJsonObject()
				? msg.getAsJsonObject("params") : new JsonObject();

		return switch (method) {
			case "initialize" -> JsonRpc.result(id, initializeResult());
			case "ping" -> JsonRpc.result(id, new JsonObject());
			case "tools/list" -> JsonRpc.result(id, registry.listPayload(policy::isExposed));
			case "tools/call" -> JsonRpc.result(id, callTool(params));
			default -> JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "unknown method: " + method);
		};
	}

	private JsonObject initializeResult() {
		JsonObject caps = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		caps.add("tools", tools);

		JsonObject info = new JsonObject();
		info.addProperty("name", serverName);
		info.addProperty("version", serverVersion);

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", PROTOCOL_VERSION);
		result.add("capabilities", caps);
		result.add("serverInfo", info);
		if (instructions != null) {
			result.addProperty("instructions", instructions);
		}
		return result;
	}

	/** Run one tool through the same checks as tools/call (used by the batch tool). */
	public JsonObject invokeTool(String name, JsonObject args) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", args);
		return callTool(params);
	}

	private JsonObject callTool(JsonObject params) {
		String name = params.has("name") && params.get("name").isJsonPrimitive()
				? params.get("name").getAsString() : null;
		if (name == null) {
			return ToolResult.error("missing tool name").toJson();
		}
		McpTool tool = registry.get(name);
		if (tool == null) {
			return ToolResult.error("unknown tool: " + name).toJson();
		}
		ToolPolicy.Decision decision = policy.check(tool);
		if (!decision.allowed()) {
			JsonObject payload = new JsonObject();
			payload.addProperty("error", decision.reason());
			if (decision.retryAfterMs() > 0) payload.addProperty("retryAfterMs", decision.retryAfterMs());
			return ToolResult.json(payload, true).toJson();
		}
		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();
		try {
			ToolResult result = tool instanceof MediaTool media
					? media.mediaResult(args)
					: ToolResult.json(tool.call(args));
			return result.toJson();
		} catch (McpTool.ToolException e) {
			return ToolResult.error(e.getMessage()).toJson();
		} catch (Exception e) {
			return ToolResult.error("internal error: " + e.getClass().getSimpleName() + ": " + e.getMessage())
					.toJson();
		}
	}

	/** Whether a named tool is exposed and can run inside {@code batch}. */
	public boolean isBatchable(String name) {
		McpTool tool = registry.get(name);
		return tool == null || tool.batchable();
	}
}
