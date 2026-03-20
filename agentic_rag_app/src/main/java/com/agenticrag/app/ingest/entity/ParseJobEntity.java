package com.agenticrag.app.ingest.entity;

import com.agenticrag.app.ingest.model.ParseJobStatus;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(
	name = "parse_job",
	indexes = {
		@Index(name = "idx_parse_job_knowledge", columnList = "knowledgeId"),
		@Index(name = "idx_parse_job_status", columnList = "status")
	}
)
public class ParseJobEntity {
	@Id
	@Column(nullable = false, length = 64)
	private String id;

	@Column(nullable = false, length = 64)
	private String knowledgeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ParseJobStatus status;

	@Column(nullable = false)
	private int retryCount;

	@Column(nullable = false)
	private int maxRetry;

	@Column
	private Instant nextRetryAt;

	@Column
	private Instant leaseUntil;

	@Column(length = 64)
	private String lastErrorCode;

	@Column(length = 2048)
	private String lastErrorMessage;

	@Column(nullable = false, length = 64)
	private String pipelineVersion;

	@Column(nullable = false, length = 256, unique = true)
	private String idempotencyKey;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(String knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public ParseJobStatus getStatus() {
		return status;
	}

	public void setStatus(ParseJobStatus status) {
		this.status = status;
	}

	public int getRetryCount() {
		return retryCount;
	}

	public void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	public int getMaxRetry() {
		return maxRetry;
	}

	public void setMaxRetry(int maxRetry) {
		this.maxRetry = maxRetry;
	}

	public Instant getNextRetryAt() {
		return nextRetryAt;
	}

	public void setNextRetryAt(Instant nextRetryAt) {
		this.nextRetryAt = nextRetryAt;
	}

	public Instant getLeaseUntil() {
		return leaseUntil;
	}

	public void setLeaseUntil(Instant leaseUntil) {
		this.leaseUntil = leaseUntil;
	}

	public String getLastErrorCode() {
		return lastErrorCode;
	}

	public void setLastErrorCode(String lastErrorCode) {
		this.lastErrorCode = lastErrorCode;
	}

	public String getLastErrorMessage() {
		return lastErrorMessage;
	}

	public void setLastErrorMessage(String lastErrorMessage) {
		this.lastErrorMessage = lastErrorMessage;
	}

	public String getPipelineVersion() {
		return pipelineVersion;
	}

	public void setPipelineVersion(String pipelineVersion) {
		this.pipelineVersion = pipelineVersion;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
