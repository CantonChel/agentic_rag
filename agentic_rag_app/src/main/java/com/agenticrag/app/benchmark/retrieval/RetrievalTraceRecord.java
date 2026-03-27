package com.agenticrag.app.benchmark.retrieval;

public class RetrievalTraceRecord {
	private final RetrievalTraceRecordType recordType;
	private final String traceId;
	private final String toolCallId;
	private final String toolName;
	private final String knowledgeBaseId;
	private final String buildId;
	private final String query;
	private final String documentId;
	private final String chunkId;
	private final String evidenceId;
	private final Integer rank;
	private final Double score;
	private final String chunkText;
	private final String source;
	private final RetrievalTraceStage stage;
	private final Integer hitCount;

	private RetrievalTraceRecord(
		RetrievalTraceRecordType recordType,
		String traceId,
		String toolCallId,
		String toolName,
		String knowledgeBaseId,
		String buildId,
		String query,
		String documentId,
		String chunkId,
		String evidenceId,
		Integer rank,
		Double score,
		String chunkText,
		String source,
		RetrievalTraceStage stage,
		Integer hitCount
	) {
		this.recordType = recordType;
		this.traceId = traceId;
		this.toolCallId = toolCallId;
		this.toolName = toolName;
		this.knowledgeBaseId = knowledgeBaseId;
		this.buildId = buildId;
		this.query = query;
		this.documentId = documentId;
		this.chunkId = chunkId;
		this.evidenceId = evidenceId;
		this.rank = rank;
		this.score = score;
		this.chunkText = chunkText;
		this.source = source;
		this.stage = stage;
		this.hitCount = hitCount;
	}

	public static RetrievalTraceRecord chunk(
		String traceId,
		String toolCallId,
		String toolName,
		String knowledgeBaseId,
		String buildId,
		String query,
		String documentId,
		String chunkId,
		String evidenceId,
		Integer rank,
		Double score,
		String chunkText,
		String source,
		RetrievalTraceStage stage
	) {
		return new RetrievalTraceRecord(
			RetrievalTraceRecordType.CHUNK,
			traceId,
			toolCallId,
			toolName,
			knowledgeBaseId,
			buildId,
			query,
			documentId,
			chunkId,
			evidenceId,
			rank,
			score,
			chunkText,
			source,
			stage,
			null
		);
	}

	public static RetrievalTraceRecord stageSummary(
		String traceId,
		String toolCallId,
		String toolName,
		String knowledgeBaseId,
		String buildId,
		String query,
		RetrievalTraceStage stage,
		int hitCount
	) {
		return new RetrievalTraceRecord(
			RetrievalTraceRecordType.STAGE_SUMMARY,
			traceId,
			toolCallId,
			toolName,
			knowledgeBaseId,
			buildId,
			query,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			stage,
			hitCount
		);
	}

	public RetrievalTraceRecordType getRecordType() {
		return recordType;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getBuildId() {
		return buildId;
	}

	public String getQuery() {
		return query;
	}

	public String getDocumentId() {
		return documentId;
	}

	public String getChunkId() {
		return chunkId;
	}

	public String getEvidenceId() {
		return evidenceId;
	}

	public Integer getRank() {
		return rank;
	}

	public Double getScore() {
		return score;
	}

	public String getChunkText() {
		return chunkText;
	}

	public String getSource() {
		return source;
	}

	public RetrievalTraceStage getStage() {
		return stage;
	}

	public Integer getHitCount() {
		return hitCount;
	}
}
