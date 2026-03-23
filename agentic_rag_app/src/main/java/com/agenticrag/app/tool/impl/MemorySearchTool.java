package com.agenticrag.app.tool.impl;

import com.agenticrag.app.memory.MemoryRecallService;
import com.agenticrag.app.rag.context.ContextAssembler;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class MemorySearchTool implements Tool {
	private final ObjectMapper objectMapper;
	private final MemoryRecallService memoryRecallService;
	private final ContextAssembler contextAssembler;

	public MemorySearchTool(
		ObjectMapper objectMapper,
		MemoryRecallService memoryRecallService,
		ContextAssembler contextAssembler
	) {
		this.objectMapper = objectMapper;
		this.memoryRecallService = memoryRecallService;
		this.contextAssembler = contextAssembler;
	}

	@Override
	public String name() {
		return "memory_search";
	}

	@Override
	public String description() {
		return "Search workspace memory for user history, prior decisions, constraints, and reminders. Query will search global MEMORY.md plus current user's memory and session transcripts. Use this before answering questions about past interactions.";
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

			List<TextChunk> recalled = memoryRecallService.search(context.getUserId(), query, topK);
			if (recalled == null || recalled.isEmpty()) {
				return ToolResult.ok("<context>\n</context>\n");
			}
			return ToolResult.ok(contextAssembler.assemble(recalled));
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
