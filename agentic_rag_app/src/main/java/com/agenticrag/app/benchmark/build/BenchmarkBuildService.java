package com.agenticrag.app.benchmark.build;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BenchmarkBuildService {
	private final BenchmarkBuildRepository benchmarkBuildRepository;

	public BenchmarkBuildService(BenchmarkBuildRepository benchmarkBuildRepository) {
		this.benchmarkBuildRepository = benchmarkBuildRepository;
	}

	@Transactional
	public BenchmarkBuildEntity createPendingBuild(
		String packagePath,
		String projectKey,
		String suiteVersion,
		String sourceSnapshotId,
		String chunkStrategyVersion,
		String embeddingModel,
		String sourceSetId,
		String goldPackageVersion,
		String goldGeneratorVersion,
		int normalizedDocumentCount,
		int runtimeChunkCount,
		int authoringBlockCount,
		int evidenceCount,
		int sampleCount
	) {
		Instant now = Instant.now();
		String buildId = UUID.randomUUID().toString();
		BenchmarkBuildEntity entity = new BenchmarkBuildEntity();
		entity.setBuildId(buildId);
		entity.setKnowledgeBaseId(toKnowledgeBaseId(buildId));
		entity.setPackagePath(normalize(packagePath));
		entity.setProjectKey(normalize(projectKey));
		entity.setSuiteVersion(normalize(suiteVersion));
		entity.setSourceSnapshotId(normalize(sourceSnapshotId));
		entity.setChunkStrategyVersion(normalize(chunkStrategyVersion));
		entity.setEmbeddingModel(normalize(embeddingModel));
		entity.setSourceSetId(normalize(sourceSetId));
		entity.setGoldPackageVersion(normalize(goldPackageVersion));
		entity.setGoldGeneratorVersion(normalize(goldGeneratorVersion));
		entity.setNormalizedDocumentCount(Math.max(0, normalizedDocumentCount));
		entity.setRuntimeChunkCount(Math.max(0, runtimeChunkCount));
		entity.setAuthoringBlockCount(Math.max(0, authoringBlockCount));
		entity.setEvidenceCount(Math.max(0, evidenceCount));
		entity.setSampleCount(Math.max(0, sampleCount));
		entity.setStatus(BenchmarkBuildStatus.PENDING);
		entity.setErrorMessage(null);
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		entity.setFinishedAt(null);
		return benchmarkBuildRepository.save(entity);
	}

	@Transactional
	public BenchmarkBuildEntity markBuilding(String buildId) {
		BenchmarkBuildEntity entity = requireBuild(buildId);
		entity.setStatus(BenchmarkBuildStatus.BUILDING);
		entity.setErrorMessage(null);
		entity.setUpdatedAt(Instant.now());
		entity.setFinishedAt(null);
		return benchmarkBuildRepository.save(entity);
	}

	@Transactional
	public BenchmarkBuildEntity markReady(String buildId) {
		BenchmarkBuildEntity entity = requireBuild(buildId);
		Instant now = Instant.now();
		entity.setStatus(BenchmarkBuildStatus.READY);
		entity.setErrorMessage(null);
		entity.setUpdatedAt(now);
		entity.setFinishedAt(now);
		return benchmarkBuildRepository.save(entity);
	}

	@Transactional
	public BenchmarkBuildEntity updateMaterializationStats(String buildId, int runtimeChunkCount) {
		BenchmarkBuildEntity entity = requireBuild(buildId);
		entity.setRuntimeChunkCount(Math.max(0, runtimeChunkCount));
		entity.setUpdatedAt(Instant.now());
		return benchmarkBuildRepository.save(entity);
	}

	@Transactional
	public BenchmarkBuildEntity markFailed(String buildId, String errorMessage) {
		BenchmarkBuildEntity entity = requireBuild(buildId);
		Instant now = Instant.now();
		entity.setStatus(BenchmarkBuildStatus.FAILED);
		entity.setErrorMessage(normalizeNullable(errorMessage));
		entity.setUpdatedAt(now);
		entity.setFinishedAt(now);
		return benchmarkBuildRepository.save(entity);
	}

	@Transactional(readOnly = true)
	public Optional<BenchmarkBuildEntity> findBuild(String buildId) {
		return benchmarkBuildRepository.findById(normalize(buildId));
	}

	@Transactional(readOnly = true)
	public Optional<BenchmarkBuildView> findBuildView(String buildId) {
		return findBuild(buildId).map(this::toView);
	}

	@Transactional(readOnly = true)
	public Optional<BenchmarkBuildEntity> findBuildByKnowledgeBaseId(String knowledgeBaseId) {
		return benchmarkBuildRepository.findByKnowledgeBaseId(normalize(knowledgeBaseId));
	}

	@Transactional(readOnly = true)
	public List<BenchmarkBuildView> listBuilds(String projectKey, BenchmarkBuildStatus status) {
		String normalizedProjectKey = normalizeNullable(projectKey);
		List<BenchmarkBuildEntity> rows;
		if (normalizedProjectKey != null && status != null) {
			rows = benchmarkBuildRepository.findByProjectKeyAndStatusOrderByCreatedAtDesc(normalizedProjectKey, status);
		} else if (normalizedProjectKey != null) {
			rows = benchmarkBuildRepository.findByProjectKeyOrderByCreatedAtDesc(normalizedProjectKey);
		} else if (status != null) {
			rows = benchmarkBuildRepository.findByStatusOrderByCreatedAtDesc(status);
		} else {
			rows = benchmarkBuildRepository.findAllByOrderByCreatedAtDesc();
		}
		return rows.stream().map(this::toView).collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public BenchmarkBuildView getBuildView(String buildId) {
		return toView(requireBuild(buildId));
	}

	public String toKnowledgeBaseId(String buildId) {
		return "bm-" + normalize(buildId);
	}

	private BenchmarkBuildEntity requireBuild(String buildId) {
		return benchmarkBuildRepository.findById(normalize(buildId))
			.orElseThrow(() -> new IllegalArgumentException("build not found: " + buildId));
	}

	private BenchmarkBuildView toView(BenchmarkBuildEntity entity) {
		return new BenchmarkBuildView(
			entity.getBuildId(),
			entity.getKnowledgeBaseId(),
			entity.getPackagePath(),
			entity.getProjectKey(),
			entity.getSuiteVersion(),
			entity.getSourceSnapshotId(),
			entity.getChunkStrategyVersion(),
			entity.getEmbeddingModel(),
			entity.getEvidenceCount(),
			entity.getSourceSetId(),
			entity.getGoldPackageVersion(),
			entity.getGoldGeneratorVersion(),
			entity.getNormalizedDocumentCount(),
			entity.getRuntimeChunkCount(),
			entity.getAuthoringBlockCount(),
			entity.getSampleCount(),
			entity.getStatus() != null ? entity.getStatus().name().toLowerCase(Locale.ROOT) : null,
			entity.getErrorMessage(),
			entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
			entity.getUpdatedAt() != null ? entity.getUpdatedAt().toString() : null,
			entity.getFinishedAt() != null ? entity.getFinishedAt().toString() : null
		);
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	private String normalizeNullable(String value) {
		String normalized = normalize(value);
		return normalized.isEmpty() ? null : normalized;
	}
}
