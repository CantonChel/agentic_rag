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
	private final String sourceSetId;
	private final String goldPackageVersion;
	private final String goldGeneratorVersion;
	private final int normalizedDocumentCount;
	private final int runtimeChunkCount;
	private final int authoringBlockCount;
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
		String sourceSetId,
		String goldPackageVersion,
		String goldGeneratorVersion,
		int normalizedDocumentCount,
		int runtimeChunkCount,
		int authoringBlockCount,
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
		this.sourceSetId = sourceSetId;
		this.goldPackageVersion = goldPackageVersion;
		this.goldGeneratorVersion = goldGeneratorVersion;
		this.normalizedDocumentCount = normalizedDocumentCount;
		this.runtimeChunkCount = runtimeChunkCount;
		this.authoringBlockCount = authoringBlockCount;
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

	public String getSourceSetId() {
		return sourceSetId;
	}

	public String getGoldPackageVersion() {
		return goldPackageVersion;
	}

	public String getGoldGeneratorVersion() {
		return goldGeneratorVersion;
	}

	public int getNormalizedDocumentCount() {
		return normalizedDocumentCount;
	}

	public int getRuntimeChunkCount() {
		return runtimeChunkCount;
	}

	public int getAuthoringBlockCount() {
		return authoringBlockCount;
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
