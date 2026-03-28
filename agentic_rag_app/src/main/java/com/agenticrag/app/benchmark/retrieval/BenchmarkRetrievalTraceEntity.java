package com.agenticrag.app.benchmark.retrieval;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(
	name = "benchmark_retrieval_trace",
	indexes = {
		@Index(name = "idx_benchmark_retrieval_trace_trace", columnList = "traceId"),
		@Index(name = "idx_benchmark_retrieval_trace_tool_call", columnList = "toolCallId"),
		@Index(name = "idx_benchmark_retrieval_trace_knowledge_base", columnList = "knowledgeBaseId"),
		@Index(name = "idx_benchmark_retrieval_trace_build", columnList = "buildId"),
		@Index(name = "idx_benchmark_retrieval_trace_created_at", columnList = "createdAt")
	}
)
public class BenchmarkRetrievalTraceEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private RetrievalTraceRecordType recordType;

	@Column(nullable = false, length = 128)
	private String traceId;

	@Column(nullable = false, length = 128)
	private String toolCallId;

	@Column(nullable = false, length = 128)
	private String toolName;

	@Column(length = 64)
	private String knowledgeBaseId;

	@Column(length = 64)
	private String buildId;

	@Column(nullable = false, columnDefinition = "text")
	private String queryText;

	@Column(length = 128)
	private String documentId;

	@Column(length = 128)
	private String chunkId;

	@Column(length = 128)
	private String evidenceId;

	@Column
	private Integer rank;

	@Column
	private Double score;

	@Column(columnDefinition = "text")
	private String chunkText;

	@Column(length = 2048)
	private String source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private RetrievalTraceStage stage;

	@Column
	private Integer hitCount;

	@Column(nullable = false)
	private Instant createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public RetrievalTraceRecordType getRecordType() {
		return recordType;
	}

	public void setRecordType(RetrievalTraceRecordType recordType) {
		this.recordType = recordType;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getToolCallId() {
		return toolCallId;
	}

	public void setToolCallId(String toolCallId) {
		this.toolCallId = toolCallId;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public void setKnowledgeBaseId(String knowledgeBaseId) {
		this.knowledgeBaseId = knowledgeBaseId;
	}

	public String getBuildId() {
		return buildId;
	}

	public void setBuildId(String buildId) {
		this.buildId = buildId;
	}

	public String getQueryText() {
		return queryText;
	}

	public void setQueryText(String queryText) {
		this.queryText = queryText;
	}

	public String getDocumentId() {
		return documentId;
	}

	public void setDocumentId(String documentId) {
		this.documentId = documentId;
	}

	public String getChunkId() {
		return chunkId;
	}

	public void setChunkId(String chunkId) {
		this.chunkId = chunkId;
	}

	public String getEvidenceId() {
		return evidenceId;
	}

	public void setEvidenceId(String evidenceId) {
		this.evidenceId = evidenceId;
	}

	public Integer getRank() {
		return rank;
	}

	public void setRank(Integer rank) {
		this.rank = rank;
	}

	public Double getScore() {
		return score;
	}

	public void setScore(Double score) {
		this.score = score;
	}

	public String getChunkText() {
		return chunkText;
	}

	public void setChunkText(String chunkText) {
		this.chunkText = chunkText;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public RetrievalTraceStage getStage() {
		return stage;
	}

	public void setStage(RetrievalTraceStage stage) {
		this.stage = stage;
	}

	public Integer getHitCount() {
		return hitCount;
	}

	public void setHitCount(Integer hitCount) {
		this.hitCount = hitCount;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
