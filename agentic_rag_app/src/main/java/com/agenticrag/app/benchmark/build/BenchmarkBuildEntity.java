package com.agenticrag.app.benchmark.build;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(
	name = "benchmark_build",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_benchmark_build_knowledge_base", columnNames = {"knowledgeBaseId"})
	},
	indexes = {
		@Index(name = "idx_benchmark_build_project_status", columnList = "projectKey,status"),
		@Index(name = "idx_benchmark_build_knowledge_base", columnList = "knowledgeBaseId"),
		@Index(name = "idx_benchmark_build_created_at", columnList = "createdAt")
	}
)
public class BenchmarkBuildEntity {
	@Id
	@Column(nullable = false, length = 64)
	private String buildId;

	@Column(nullable = false, length = 64)
	private String knowledgeBaseId;

	@Column(nullable = false, length = 2048)
	private String packagePath;

	@Column(nullable = false, length = 128)
	private String projectKey;

	@Column(nullable = false, length = 128)
	private String suiteVersion;

	@Column(nullable = false, length = 128)
	private String sourceSnapshotId;

	@Column(nullable = false, length = 128)
	private String chunkStrategyVersion;

	@Column(nullable = false, length = 128)
	private String embeddingModel;

	@Column(nullable = false)
	private int evidenceCount;

	@Column(nullable = false)
	private int sampleCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private BenchmarkBuildStatus status;

	@Column(columnDefinition = "text")
	private String errorMessage;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Column
	private Instant finishedAt;

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

	public String getPackagePath() {
		return packagePath;
	}

	public void setPackagePath(String packagePath) {
		this.packagePath = packagePath;
	}

	public String getProjectKey() {
		return projectKey;
	}

	public void setProjectKey(String projectKey) {
		this.projectKey = projectKey;
	}

	public String getSuiteVersion() {
		return suiteVersion;
	}

	public void setSuiteVersion(String suiteVersion) {
		this.suiteVersion = suiteVersion;
	}

	public String getSourceSnapshotId() {
		return sourceSnapshotId;
	}

	public void setSourceSnapshotId(String sourceSnapshotId) {
		this.sourceSnapshotId = sourceSnapshotId;
	}

	public String getChunkStrategyVersion() {
		return chunkStrategyVersion;
	}

	public void setChunkStrategyVersion(String chunkStrategyVersion) {
		this.chunkStrategyVersion = chunkStrategyVersion;
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public void setEmbeddingModel(String embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	public int getEvidenceCount() {
		return evidenceCount;
	}

	public void setEvidenceCount(int evidenceCount) {
		this.evidenceCount = evidenceCount;
	}

	public int getSampleCount() {
		return sampleCount;
	}

	public void setSampleCount(int sampleCount) {
		this.sampleCount = sampleCount;
	}

	public BenchmarkBuildStatus getStatus() {
		return status;
	}

	public void setStatus(BenchmarkBuildStatus status) {
		this.status = status;
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

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}
}
