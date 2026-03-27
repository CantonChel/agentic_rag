package com.agenticrag.app.benchmark.retrieval;

import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.model.TextChunkMetadataHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RetrievalTraceCollector {
	private final String traceId;
	private final String toolCallId;
	private final String toolName;
	private final String knowledgeBaseId;
	private final String query;
	private final List<RetrievalTraceRecord> records = new ArrayList<>();

	public RetrievalTraceCollector(
		String traceId,
		String toolCallId,
		String toolName,
		String knowledgeBaseId,
		String query
	) {
		this.traceId = normalize(traceId);
		this.toolCallId = normalize(toolCallId);
		this.toolName = normalize(toolName);
		this.knowledgeBaseId = nullable(knowledgeBaseId);
		this.query = normalize(query);
	}

	public synchronized void recordStage(RetrievalTraceStage stage, List<TextChunk> chunks) {
		if (stage == null) {
			return;
		}
		String buildId = firstBuildId(chunks);
		int hitCount = chunks != null ? chunks.size() : 0;
		records.add(
			RetrievalTraceRecord.stageSummary(
				traceId,
				toolCallId,
				toolName,
				knowledgeBaseId,
				buildId,
				query,
				stage,
				hitCount
			)
		);
		if (chunks == null || chunks.isEmpty()) {
			return;
		}
		for (int i = 0; i < chunks.size(); i++) {
			TextChunk chunk = chunks.get(i);
			if (chunk == null) {
				continue;
			}
			records.add(
				RetrievalTraceRecord.chunk(
					traceId,
					toolCallId,
					toolName,
					resolveKnowledgeBaseId(chunk),
					TextChunkMetadataHelper.metadataText(chunk, "build_id"),
					query,
					chunk.getDocumentId(),
					chunk.getChunkId(),
					TextChunkMetadataHelper.metadataText(chunk, "evidence_id"),
					i + 1,
					TextChunkMetadataHelper.retrievalScore(chunk),
					chunk.getText(),
					TextChunkMetadataHelper.metadataText(chunk, "source"),
					stage
				)
			);
		}
	}

	public synchronized List<RetrievalTraceRecord> getRecords() {
		return new ArrayList<>(records);
	}

	public synchronized JsonNode buildSidecar() {
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		root.put("type", "retrieval_context_v1");
		root.put("traceId", traceId);
		root.put("toolCallId", toolCallId);
		root.put("toolName", toolName);
		if (knowledgeBaseId != null) {
			root.put("knowledgeBaseId", knowledgeBaseId);
		}
		ArrayNode items = root.putArray("items");
		records.stream()
			.filter(record -> record != null && record.getRecordType() == RetrievalTraceRecordType.CHUNK)
			.filter(record -> record.getStage() == RetrievalTraceStage.CONTEXT_OUTPUT)
			.sorted(Comparator.comparingInt(record -> record.getRank() != null ? record.getRank() : Integer.MAX_VALUE))
			.forEach(record -> items.add(toSidecarItem(record)));
		return root;
	}

	private ObjectNode toSidecarItem(RetrievalTraceRecord record) {
		ObjectNode item = JsonNodeFactory.instance.objectNode();
		if (record.getBuildId() != null) {
			item.put("buildId", record.getBuildId());
		}
		item.put("query", record.getQuery());
		if (record.getDocumentId() != null) {
			item.put("documentId", record.getDocumentId());
		}
		if (record.getChunkId() != null) {
			item.put("chunkId", record.getChunkId());
		}
		if (record.getEvidenceId() != null) {
			item.put("evidenceId", record.getEvidenceId());
		}
		if (record.getRank() != null) {
			item.put("rank", record.getRank());
		}
		if (record.getScore() != null) {
			item.put("score", record.getScore());
		}
		item.put("chunkText", record.getChunkText() != null ? record.getChunkText() : "");
		item.put("toolCallId", record.getToolCallId());
		if (record.getSource() != null) {
			item.put("source", record.getSource());
		}
		return item;
	}

	private String resolveKnowledgeBaseId(TextChunk chunk) {
		String fromChunk = TextChunkMetadataHelper.metadataText(chunk, "knowledge_base_id");
		return fromChunk != null ? fromChunk : knowledgeBaseId;
	}

	private String firstBuildId(List<TextChunk> chunks) {
		if (chunks == null) {
			return null;
		}
		for (TextChunk chunk : chunks) {
			String buildId = TextChunkMetadataHelper.metadataText(chunk, "build_id");
			if (buildId != null) {
				return buildId;
			}
		}
		return null;
	}

	private String normalize(String value) {
		if (value == null || value.trim().isEmpty()) {
			return "n/a";
		}
		return value.trim();
	}

	private String nullable(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		return value.trim();
	}
}
