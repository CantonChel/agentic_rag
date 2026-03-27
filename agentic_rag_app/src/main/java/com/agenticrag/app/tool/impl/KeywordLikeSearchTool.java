package com.agenticrag.app.tool.impl;

import com.agenticrag.app.benchmark.retrieval.RetrievalTraceCollector;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import com.agenticrag.app.rag.context.ContextAssemblyResult;
import com.agenticrag.app.rag.context.ContextAssembler;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.PostgresBm25Retriever;
import com.agenticrag.app.rag.retriever.PostgresKeywordLikeRetriever;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KeywordLikeSearchTool implements Tool {
	private static final Logger log = LoggerFactory.getLogger(KeywordLikeSearchTool.class);
	private final ObjectMapper objectMapper;
	private final ContextAssembler contextAssembler;
	private final ObjectProvider<PostgresKeywordLikeRetriever> postgresKeywordLikeRetriever;
	private final ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever;

	public KeywordLikeSearchTool(
		ObjectMapper objectMapper,
		ContextAssembler contextAssembler,
		ObjectProvider<PostgresKeywordLikeRetriever> postgresKeywordLikeRetriever,
		ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever
	) {
		this.objectMapper = objectMapper;
		this.contextAssembler = contextAssembler;
		this.postgresKeywordLikeRetriever = postgresKeywordLikeRetriever;
		this.postgresBm25Retriever = postgresBm25Retriever;
	}

	@Override
	public String name() {
		return "search_knowledge_keywords";
	}

	@Override
	public String description() {
		return "Fast keyword retrieval over knowledge chunks (SQL LIKE / BM25 style). Use this first for short anchors, exact terms, model numbers, IDs, entity names, or open-ended coverage discovery when you need to see what exists in the KB around a topic. Returns ranked excerpts in <context>.";
	}

	@Override
	public JsonNode parametersSchema() {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");
		properties.putObject("query").put("type", "string");
		properties.putObject("topK").put("type", "integer").put("minimum", 1).put("maximum", 20);
		root.putArray("required").add("query");
		return root;
	}

	@Override
	public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
		return Mono.fromCallable(() -> {
			long startNs = System.nanoTime();
			String traceId = context != null ? context.getTraceId() : "n/a";
			if (arguments == null || !arguments.hasNonNull("query")) {
				return ToolResult.error("Missing query");
			}
			String query = arguments.get("query").asText("");
			if (query.trim().isEmpty()) {
				return ToolResult.error("Empty query");
			}
			int topK = 8;
			if (arguments.hasNonNull("topK")) {
				topK = Math.max(1, Math.min(20, arguments.get("topK").asInt(8)));
			}

			List<TextChunk> chunks = new ArrayList<>();
			String knowledgeBaseId = context != null ? context.getKnowledgeBaseId() : null;
			RetrievalTraceCollector collector = new RetrievalTraceCollector(
				traceId,
				context != null ? context.getToolCallId() : null,
				name(),
				knowledgeBaseId,
				query
			);
			PostgresKeywordLikeRetriever keywordRetriever = postgresKeywordLikeRetriever.getIfAvailable();
			if (keywordRetriever != null) {
				chunks = keywordRetriever.retrieve(query, topK, traceId, knowledgeBaseId);
				collector.recordStage(RetrievalTraceStage.KEYWORD_LIKE, chunks);
			} else {
				PostgresBm25Retriever bm25Retriever = postgresBm25Retriever.getIfAvailable();
				if (bm25Retriever != null) {
					chunks = bm25Retriever.retrieve(query, topK, traceId, knowledgeBaseId);
					collector.recordStage(RetrievalTraceStage.BM25, chunks);
				}
			}

			ContextAssemblyResult assembled = contextAssembler.assemble(chunks, collector);
			String output = assembled.getContextText();
			long durationMs = (System.nanoTime() - startNs) / 1_000_000;
			log.info(
				"event=keyword_tool_end traceId={} knowledgeBaseId={} requestId={} query={} topK={} chunks={} durationMs={}",
				traceId,
				knowledgeBaseId,
				context != null ? context.getRequestId() : "n/a",
				query,
				topK,
				chunks != null ? chunks.size() : 0,
				durationMs
			);
			return ToolResult.ok(output, assembled.getSidecar());
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
