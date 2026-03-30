package com.agenticrag.app.tool.impl;

import com.agenticrag.app.memory.MemoryReadResult;
import com.agenticrag.app.memory.MemoryRecallService;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class MemoryGetTool implements Tool {
	private final ObjectMapper objectMapper;
	private final MemoryRecallService memoryRecallService;

	public MemoryGetTool(ObjectMapper objectMapper, MemoryRecallService memoryRecallService) {
		this.objectMapper = objectMapper;
		this.memoryRecallService = memoryRecallService;
	}

	@Override
	public String name() {
		return "memory_get";
	}

	@Override
	public String description() {
		return "Read exact lines from a memory markdown file returned by memory_search. Use this when you need precise original wording instead of candidate snippets.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode props = root.putObject("properties");
		props.putObject("path").put("type", "string");
		props.putObject("lineStart").put("type", "integer").put("minimum", 1);
		props.putObject("lineEnd").put("type", "integer").put("minimum", 1);
		root.putArray("required").add("path").add("lineStart").add("lineEnd");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		return Mono.fromCallable(() -> {
			if (arguments == null || !arguments.hasNonNull("path") || !arguments.hasNonNull("lineStart") || !arguments.hasNonNull("lineEnd")) {
				return ToolResult.error("Missing path or line range");
			}
			String path = arguments.get("path").asText("");
			int lineStart = arguments.get("lineStart").asInt(0);
			int lineEnd = arguments.get("lineEnd").asInt(0);
			if (path.trim().isEmpty() || lineStart < 1 || lineEnd < lineStart) {
				return ToolResult.error("Invalid path or line range");
			}
			MemoryReadResult result = memoryRecallService.get(context.getUserId(), path, lineStart, lineEnd);
			if (result == null) {
				return ToolResult.error("Memory file or line range not found");
			}
			return ToolResult.ok(formatResult(result));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private String formatResult(MemoryReadResult result) {
		StringBuilder out = new StringBuilder();
		out.append("<memory_get>\n");
		out.append("path: ").append(safe(result.getPath())).append("\n");
		out.append("kind: ").append(safe(result.getKind())).append("\n");
		out.append("blockId: ").append(safe(result.getBlockId())).append("\n");
		out.append("lines: ").append(result.getLineStart()).append("-").append(result.getLineEnd()).append("\n");
		out.append("content:\n");
		String[] lines = (result.getContent() == null ? "" : result.getContent()).split("\\r?\\n", -1);
		for (int i = 0; i < lines.length; i++) {
			out.append(result.getLineStart() + i).append(" | ").append(lines[i]).append("\n");
		}
		out.append("</memory_get>\n");
		return out.toString();
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
