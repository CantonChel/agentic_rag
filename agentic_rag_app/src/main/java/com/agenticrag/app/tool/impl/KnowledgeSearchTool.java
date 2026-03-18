package com.agenticrag.app.tool.impl;

import com.agenticrag.app.rag.context.ContextAssembler;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.HybridRetriever;
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
public class KnowledgeSearchTool implements Tool {
	private final ObjectMapper objectMapper;
	private final HybridRetriever hybridRetriever;
	private final ContextAssembler contextAssembler;

	public KnowledgeSearchTool(ObjectMapper objectMapper, HybridRetriever hybridRetriever, ContextAssembler contextAssembler) {
		this.objectMapper = objectMapper;
		this.hybridRetriever = hybridRetriever;
		this.contextAssembler = contextAssembler;
	}

	@Override
	public String name() {
		return "search_knowledge_base";
	}

	@Override
	public String description() {
		return "When you need to answer questions about organization-specific policies, internal documents, product specs, model numbers, or any domain knowledge you are not fully sure about, you must call this tool before answering. Input should be a short, high-signal search phrase or a concise declarative statement, not a long question. The tool returns <context> with ranked excerpts and sources; you must base the answer strictly on it and cite sources like [引用 1].";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");
		ObjectNode query = properties.putObject("query");
		query.put("type", "string");
		root.putArray("required").add("query");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		return Mono.fromCallable(() -> {
			if (arguments == null || !arguments.hasNonNull("query")) {
				return ToolResult.error("Missing query");
			}
			String q = arguments.get("query").asText("");
			if (q.trim().isEmpty()) {
				return ToolResult.error("Empty query");
			}
			List<TextChunk> top = hybridRetriever.retrieve(q, 20, 5);
			String ctx = contextAssembler.assemble(top);
			return ToolResult.ok(ctx);
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
