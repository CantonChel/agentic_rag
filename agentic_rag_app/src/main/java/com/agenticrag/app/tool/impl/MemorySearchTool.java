package com.agenticrag.app.tool.impl;

import com.agenticrag.app.memory.MemoryRecallService;
import com.agenticrag.app.memory.MemorySearchHit;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class MemorySearchTool implements Tool {
	private final ObjectMapper objectMapper;
	private final MemoryRecallService memoryRecallService;

	public MemorySearchTool(
		ObjectMapper objectMapper,
		MemoryRecallService memoryRecallService
	) {
		this.objectMapper = objectMapper;
		this.memoryRecallService = memoryRecallService;
	}

	@Override
	public String name() {
		return "memory_search";
	}

	@Override
	public String description() {
		return "Search markdown-backed memory for candidate history, decisions, preferences, constraints, and reminders. Use memory_get after this to read exact lines from the returned path and line range.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode props = root.putObject("properties");
		props.putObject("query").put("type", "string");
		props.putObject("topK").put("type", "integer").put("minimum", 1).put("maximum", 20);
		root.putArray("required").add("query");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		return Mono.fromCallable(() -> {
			if (arguments == null || !arguments.hasNonNull("query")) {
				return ToolResult.error("Missing query");
			}
			String query = arguments.get("query").asText("");
			if (query.trim().isEmpty()) {
				return ToolResult.error("Empty query");
			}
			Integer topK = null;
			if (arguments.hasNonNull("topK")) {
				topK = arguments.get("topK").asInt(5);
			}

			List<MemorySearchHit> hits = memoryRecallService.search(context.getUserId(), query, topK);
			if (hits == null || hits.isEmpty()) {
				return ToolResult.ok("<memory_search_results>\n</memory_search_results>\n");
			}
			return ToolResult.ok(formatHits(hits));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private String formatHits(List<MemorySearchHit> hits) {
		StringBuilder out = new StringBuilder();
		out.append("<memory_search_results>\n");
		int index = 1;
		for (MemorySearchHit hit : hits) {
			if (hit == null) {
				continue;
			}
			out.append("[result ").append(index).append("]\n");
			out.append("path: ").append(safe(hit.getPath())).append("\n");
			out.append("kind: ").append(safe(hit.getKind())).append("\n");
			out.append("blockId: ").append(safe(hit.getBlockId())).append("\n");
			out.append("lines: ").append(hit.getLineStart()).append("-").append(hit.getLineEnd()).append("\n");
			out.append("score: ").append(String.format(Locale.ROOT, "%.4f", hit.getScore())).append("\n");
			out.append("snippet:\n");
			out.append(safe(hit.getSnippet())).append("\n\n");
			index++;
		}
		out.append("</memory_search_results>\n");
		return out.toString();
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
