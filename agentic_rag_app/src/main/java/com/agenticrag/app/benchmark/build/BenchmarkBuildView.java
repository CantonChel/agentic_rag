package com.agenticrag.app.benchmark.build;

public class BenchmarkBuildView {
	private final String buildId;
	private final String knowledgeBaseId;
	private final String packagePath;
	private final String projectKey;
	private final String suiteVersion;
	private final String sourceSnapshotId;
	private final String chunkStrategyVersion;
	private final String embeddingModel;
	private final int evidenceCount;
	private final int sampleCount;
	private final String status;
	private final String errorMessage;
	private final String createdAt;
	private final String updatedAt;
	private final String finishedAt;

	public BenchmarkBuildView(
		String buildId,
		String knowledgeBaseId,
		String packagePath,
		String projectKey,
		String suiteVersion,
		String sourceSnapshotId,
		String chunkStrategyVersion,
		String embeddingModel,
		int evidenceCount,
		int sampleCount,
		String status,
		String errorMessage,
		String createdAt,
		String updatedAt,
		String finishedAt
	) {
		this.buildId = buildId;
		this.knowledgeBaseId = knowledgeBaseId;
		this.packagePath = packagePath;
		this.projectKey = projectKey;
		this.suiteVersion = suiteVersion;
		this.sourceSnapshotId = sourceSnapshotId;
		this.chunkStrategyVersion = chunkStrategyVersion;
		this.embeddingModel = embeddingModel;
		this.evidenceCount = evidenceCount;
		this.sampleCount = sampleCount;
		this.status = status;
		this.errorMessage = errorMessage;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.finishedAt = finishedAt;
	}

	public String getBuildId() {
		return buildId;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getPackagePath() {
		return packagePath;
	}

	public String getProjectKey() {
		return projectKey;
	}

	public String getSuiteVersion() {
		return suiteVersion;
	}

	public String getSourceSnapshotId() {
		return sourceSnapshotId;
	}

	public String getChunkStrategyVersion() {
		return chunkStrategyVersion;
	}

	public String getEmbeddingModel() {
		return embeddingModel;
	}

	public int getEvidenceCount() {
		return evidenceCount;
	}

	public int getSampleCount() {
		return sampleCount;
	}

	public String getStatus() {
		return status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public String getUpdatedAt() {
		return updatedAt;
	}

	public String getFinishedAt() {
		return finishedAt;
	}
}
