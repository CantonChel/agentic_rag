package com.agenticrag.app.benchmark.retrieval;

public class BenchmarkRetrievalTraceView {
	private final Long id;
	private final String recordType;
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
	private final String stage;
	private final Integer hitCount;
	private final String createdAt;

	public BenchmarkRetrievalTraceView(
		Long id,
		String recordType,
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
		String stage,
		Integer hitCount,
		String createdAt
	) {
		this.id = id;
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
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public String getRecordType() {
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

	public String getStage() {
		return stage;
	}

	public Integer getHitCount() {
		return hitCount;
	}

	public String getCreatedAt() {
		return createdAt;
	}
}
