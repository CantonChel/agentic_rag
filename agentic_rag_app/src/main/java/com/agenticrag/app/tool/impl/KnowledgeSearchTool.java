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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KnowledgeSearchTool implements Tool {
	private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);
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
		return "Semantic retrieval over the knowledge base. Use this after keyword anchoring or when you need broader recall, better coverage, or more context than exact keywords can provide. Input should be a short, high-signal search phrase or concise declarative statement, not a long question. The tool returns <context> with ranked excerpts and sources; you must base the answer strictly on it and cite sources like [引用 1].";
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
			long startNs = System.nanoTime();
			String traceId = context != null ? context.getTraceId() : "n/a";
			if (arguments == null || !arguments.hasNonNull("query")) {
				log.warn("event=kb_tool_invalid_request traceId={} reason=missing_query", traceId);
				return ToolResult.error("Missing query");
			}
			String q = arguments.get("query").asText("");
			if (q.trim().isEmpty()) {
				log.warn("event=kb_tool_invalid_request traceId={} reason=empty_query", traceId);
				return ToolResult.error("Empty query");
			}
			log.info(
				"event=kb_tool_start traceId={} userId={} sessionId={} knowledgeBaseId={} requestId={} query={} recallTopK={} rerankTopK={}",
				traceId,
				context != null ? context.getUserId() : "anonymous",
				context != null ? context.getSessionId() : "default",
				context != null ? context.getKnowledgeBaseId() : null,
				context != null ? context.getRequestId() : "n/a",
				q,
				20,
				5
			);
			List<TextChunk> top = hybridRetriever.retrieve(
				q,
				20,
				5,
				traceId,
				context != null ? context.getKnowledgeBaseId() : null
			);
			String ctx = contextAssembler.assemble(top);
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=kb_tool_end traceId={} knowledgeBaseId={} requestId={} chunks={} contextChars={} durationMs={}",
				traceId,
				context != null ? context.getKnowledgeBaseId() : null,
				context != null ? context.getRequestId() : "n/a",
				top != null ? top.size() : 0,
				ctx != null ? ctx.length() : 0,
				durationMs
			);
			return ToolResult.ok(ctx);
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
