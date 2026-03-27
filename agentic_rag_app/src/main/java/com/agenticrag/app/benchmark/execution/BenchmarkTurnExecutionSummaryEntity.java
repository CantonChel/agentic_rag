package com.agenticrag.app.benchmark.execution;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(
	name = "benchmark_turn_execution_summary",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_benchmark_turn_execution_summary_turn", columnNames = {"turnId"})
	},
	indexes = {
		@Index(name = "idx_benchmark_turn_execution_summary_session", columnList = "sessionId"),
		@Index(name = "idx_benchmark_turn_execution_summary_build", columnList = "buildId"),
		@Index(name = "idx_benchmark_turn_execution_summary_knowledge_base", columnList = "knowledgeBaseId"),
		@Index(name = "idx_benchmark_turn_execution_summary_trace", columnList = "traceId"),
		@Index(name = "idx_benchmark_turn_execution_summary_created_at", columnList = "createdAt")
	}
)
public class BenchmarkTurnExecutionSummaryEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 128)
	private String turnId;

	@Column(nullable = false, length = 128)
	private String sessionId;

	@Column(nullable = false, length = 128)
	private String userId;

	@Column(length = 128)
	private String traceId;

	@Column(nullable = false, length = 32)
	private String provider;

	@Column(length = 128)
	private String originModel;

	@Column(length = 64)
	private String buildId;

	@Column(length = 64)
	private String knowledgeBaseId;

	@Column(nullable = false, length = 32)
	private String kbScope;

	@Column(nullable = false, length = 32)
	private String evalMode;

	@Column(nullable = false, length = 32)
	private String thinkingProfile;

	@Column(nullable = false)
	private boolean memoryEnabled;

	@Column(nullable = false, columnDefinition = "text")
	private String userQuestion;

	@Column(columnDefinition = "text")
	private String finalAnswer;

	@Column(nullable = false, length = 64)
	private String finishReason;

	@Column
	private Long latencyMs;

	@Column(nullable = false, columnDefinition = "text")
	private String toolCallsJson;

	@Column(nullable = false, columnDefinition = "text")
	private String retrievalTraceIdsJson;

	@Column(nullable = false, columnDefinition = "text")
	private String retrievalTraceRefsJson;

	@Column(columnDefinition = "text")
	private String errorMessage;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant completedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTurnId() {
		return turnId;
	}

	public void setTurnId(String turnId) {
		this.turnId = turnId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getOriginModel() {
		return originModel;
	}

	public void setOriginModel(String originModel) {
		this.originModel = originModel;
	}

	public String getBuildId() {
		return buildId;
	}

	public void setBuildId(String buildId) {
		this.buildId = buildId;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public void setKnowledgeBaseId(String knowledgeBaseId) {
		this.knowledgeBaseId = knowledgeBaseId;
	}

	public String getKbScope() {
		return kbScope;
	}

	public void setKbScope(String kbScope) {
		this.kbScope = kbScope;
	}

	public String getEvalMode() {
		return evalMode;
	}

	public void setEvalMode(String evalMode) {
		this.evalMode = evalMode;
	}

	public String getThinkingProfile() {
		return thinkingProfile;
	}

	public void setThinkingProfile(String thinkingProfile) {
		this.thinkingProfile = thinkingProfile;
	}

	public boolean isMemoryEnabled() {
		return memoryEnabled;
	}

	public void setMemoryEnabled(boolean memoryEnabled) {
		this.memoryEnabled = memoryEnabled;
	}

	public String getUserQuestion() {
		return userQuestion;
	}

	public void setUserQuestion(String userQuestion) {
		this.userQuestion = userQuestion;
	}

	public String getFinalAnswer() {
		return finalAnswer;
	}

	public void setFinalAnswer(String finalAnswer) {
		this.finalAnswer = finalAnswer;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public void setFinishReason(String finishReason) {
		this.finishReason = finishReason;
	}

	public Long getLatencyMs() {
		return latencyMs;
	}

	public void setLatencyMs(Long latencyMs) {
		this.latencyMs = latencyMs;
	}

	public String getToolCallsJson() {
		return toolCallsJson;
	}

	public void setToolCallsJson(String toolCallsJson) {
		this.toolCallsJson = toolCallsJson;
	}

	public String getRetrievalTraceIdsJson() {
		return retrievalTraceIdsJson;
	}

	public void setRetrievalTraceIdsJson(String retrievalTraceIdsJson) {
		this.retrievalTraceIdsJson = retrievalTraceIdsJson;
	}

	public String getRetrievalTraceRefsJson() {
		return retrievalTraceRefsJson;
	}

	public void setRetrievalTraceRefsJson(String retrievalTraceRefsJson) {
		this.retrievalTraceRefsJson = retrievalTraceRefsJson;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public void setCompletedAt(Instant completedAt) {
		this.completedAt = completedAt;
	}
}
