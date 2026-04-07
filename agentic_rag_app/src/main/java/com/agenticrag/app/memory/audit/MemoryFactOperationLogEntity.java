package com.agenticrag.app.memory.audit;

import com.agenticrag.app.memory.MemoryFactCompareResult;
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
	name = "memory_fact_operation_logs",
	indexes = {
		@Index(name = "idx_memory_fact_operation_logs_flush", columnList = "flush_id"),
		@Index(name = "idx_memory_fact_operation_logs_user_path_created", columnList = "user_id,file_path,created_at")
	}
)
public class MemoryFactOperationLogEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "flush_id", nullable = false, length = 64)
	private String flushId;

	@Column(name = "user_id", nullable = false, length = 128)
	private String userId;

	@Column(name = "session_id", nullable = false, length = 128)
	private String sessionId;

	@Column(name = "trigger", nullable = false, length = 64)
	private String trigger;

	@Column(name = "file_path", nullable = false, length = 1024)
	private String filePath;

	@Column(name = "bucket", nullable = false, length = 64)
	private String bucket;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", nullable = false, length = 16)
	private MemoryFactCompareResult.Decision decision;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision_source", nullable = false, length = 32)
	private MemoryFactOperationDecisionSource decisionSource;

	@Enumerated(EnumType.STRING)
	@Column(name = "write_outcome", nullable = false, length = 32)
	private MemoryFactOperationWriteOutcome writeOutcome;

	@Column(name = "candidate_count", nullable = false)
	private int candidateCount;

	@Column(name = "matched_block_id", length = 128)
	private String matchedBlockId;

	@Column(name = "target_block_id", length = 128)
	private String targetBlockId;

	@Column(name = "incoming_fact_json", nullable = false, columnDefinition = "text")
	private String incomingFactJson;

	@Column(name = "matched_fact_json", columnDefinition = "text")
	private String matchedFactJson;

	@Column(name = "candidate_facts_json", nullable = false, columnDefinition = "text")
	private String candidateFactsJson;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getFlushId() {
		return flushId;
	}

	public void setFlushId(String flushId) {
		this.flushId = flushId;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getTrigger() {
		return trigger;
	}

	public void setTrigger(String trigger) {
		this.trigger = trigger;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getBucket() {
		return bucket;
	}

	public void setBucket(String bucket) {
		this.bucket = bucket;
	}

	public MemoryFactCompareResult.Decision getDecision() {
		return decision;
	}

	public void setDecision(MemoryFactCompareResult.Decision decision) {
		this.decision = decision;
	}

	public MemoryFactOperationDecisionSource getDecisionSource() {
		return decisionSource;
	}

	public void setDecisionSource(MemoryFactOperationDecisionSource decisionSource) {
		this.decisionSource = decisionSource;
	}

	public MemoryFactOperationWriteOutcome getWriteOutcome() {
		return writeOutcome;
	}

	public void setWriteOutcome(MemoryFactOperationWriteOutcome writeOutcome) {
		this.writeOutcome = writeOutcome;
	}

	public int getCandidateCount() {
		return candidateCount;
	}

	public void setCandidateCount(int candidateCount) {
		this.candidateCount = candidateCount;
	}

	public String getMatchedBlockId() {
		return matchedBlockId;
	}

	public void setMatchedBlockId(String matchedBlockId) {
		this.matchedBlockId = matchedBlockId;
	}

	public String getTargetBlockId() {
		return targetBlockId;
	}

	public void setTargetBlockId(String targetBlockId) {
		this.targetBlockId = targetBlockId;
	}

	public String getIncomingFactJson() {
		return incomingFactJson;
	}

	public void setIncomingFactJson(String incomingFactJson) {
		this.incomingFactJson = incomingFactJson;
	}

	public String getMatchedFactJson() {
		return matchedFactJson;
	}

	public void setMatchedFactJson(String matchedFactJson) {
		this.matchedFactJson = matchedFactJson;
	}

	public String getCandidateFactsJson() {
		return candidateFactsJson;
	}

	public void setCandidateFactsJson(String candidateFactsJson) {
		this.candidateFactsJson = candidateFactsJson;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
