package com.agenticrag.app.benchmark.retrieval;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BenchmarkRetrievalTraceService {
	private final BenchmarkRetrievalTraceRepository benchmarkRetrievalTraceRepository;
	private final BenchmarkBuildService benchmarkBuildService;

	public BenchmarkRetrievalTraceService(
		BenchmarkRetrievalTraceRepository benchmarkRetrievalTraceRepository,
		BenchmarkBuildService benchmarkBuildService
	) {
		this.benchmarkRetrievalTraceRepository = benchmarkRetrievalTraceRepository;
		this.benchmarkBuildService = benchmarkBuildService;
	}

	@Transactional
	public void persistCollector(RetrievalTraceCollector collector) {
		if (collector == null) {
			return;
		}
		persistRecords(collector.getRecords());
	}

	@Transactional
	public void persistRecords(List<RetrievalTraceRecord> records) {
		if (records == null || records.isEmpty()) {
			return;
		}
		Instant now = Instant.now();
		List<BenchmarkRetrievalTraceEntity> entities = new ArrayList<>();
		for (RetrievalTraceRecord record : records) {
			if (record == null) {
				continue;
			}
			entities.add(toEntity(record, now));
		}
		if (!entities.isEmpty()) {
			benchmarkRetrievalTraceRepository.saveAll(entities);
		}
	}

	@Transactional(readOnly = true)
	public List<BenchmarkRetrievalTraceView> listTraceViews(
		String traceId,
		String toolCallId,
		String toolName,
		String knowledgeBaseId,
		String buildId,
		RetrievalTraceStage stage,
		RetrievalTraceRecordType recordType
	) {
		String normalizedTraceId = normalizeNullable(traceId);
		if (normalizedTraceId == null) {
			throw new IllegalArgumentException("traceId is required");
		}
		return benchmarkRetrievalTraceRepository.findByTraceIdOrderByCreatedAtAscToolCallIdAscStageAscRankAscIdAsc(normalizedTraceId).stream()
			.filter(entity -> matchesNullable(entity.getToolCallId(), toolCallId))
			.filter(entity -> matchesNullable(entity.getToolName(), toolName))
			.filter(entity -> matchesNullable(entity.getKnowledgeBaseId(), knowledgeBaseId))
			.filter(entity -> matchesNullable(entity.getBuildId(), buildId))
			.filter(entity -> stage == null || stage == entity.getStage())
			.filter(entity -> recordType == null || recordType == entity.getRecordType())
			.map(this::toView)
			.collect(Collectors.toList());
	}

	private BenchmarkRetrievalTraceEntity toEntity(RetrievalTraceRecord record, Instant now) {
		BenchmarkRetrievalTraceEntity entity = new BenchmarkRetrievalTraceEntity();
		entity.setRecordType(record.getRecordType());
		entity.setTraceId(normalize(record.getTraceId(), "n/a"));
		entity.setToolCallId(normalize(record.getToolCallId(), "n/a"));
		entity.setToolName(normalize(record.getToolName(), "unknown"));
		entity.setKnowledgeBaseId(normalizeNullable(record.getKnowledgeBaseId()));
		entity.setBuildId(resolveBuildId(record));
		entity.setQueryText(normalize(record.getQuery(), ""));
		entity.setDocumentId(normalizeNullable(record.getDocumentId()));
		entity.setChunkId(normalizeNullable(record.getChunkId()));
		entity.setEvidenceId(normalizeNullable(record.getEvidenceId()));
		entity.setRank(record.getRank());
		entity.setScore(record.getScore());
		entity.setChunkText(record.getChunkText());
		entity.setSource(normalizeNullable(record.getSource()));
		entity.setStage(record.getStage());
		entity.setHitCount(record.getHitCount());
		entity.setCreatedAt(now);
		return entity;
	}

	private BenchmarkRetrievalTraceView toView(BenchmarkRetrievalTraceEntity entity) {
		return new BenchmarkRetrievalTraceView(
			entity.getId(),
			entity.getRecordType() != null ? entity.getRecordType().getValue() : null,
			entity.getTraceId(),
			entity.getToolCallId(),
			entity.getToolName(),
			entity.getKnowledgeBaseId(),
			entity.getBuildId(),
			entity.getQueryText(),
			entity.getDocumentId(),
			entity.getChunkId(),
			entity.getEvidenceId(),
			entity.getRank(),
			entity.getScore(),
			entity.getChunkText(),
			entity.getSource(),
			entity.getStage() != null ? entity.getStage().getValue() : null,
			entity.getHitCount(),
			entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null
		);
	}

	private String resolveBuildId(RetrievalTraceRecord record) {
		String buildId = normalizeNullable(record != null ? record.getBuildId() : null);
		if (buildId != null) {
			return buildId;
		}
		String knowledgeBaseId = normalizeNullable(record != null ? record.getKnowledgeBaseId() : null);
		if (knowledgeBaseId == null) {
			return null;
		}
		Optional<BenchmarkBuildEntity> build = benchmarkBuildService.findBuildByKnowledgeBaseId(knowledgeBaseId);
		return build.map(BenchmarkBuildEntity::getBuildId).orElse(null);
	}

	private String normalize(String value, String fallback) {
		String normalized = value == null ? "" : value.trim();
		return normalized.isEmpty() ? fallback : normalized;
	}

	private String normalizeNullable(String value) {
		String normalized = value == null ? "" : value.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private boolean matchesNullable(String actual, String expected) {
		String normalizedExpected = normalizeNullable(expected);
		if (normalizedExpected == null) {
			return true;
		}
		String normalizedActual = normalizeNullable(actual);
		if (normalizedActual == null) {
			return false;
		}
		return normalizedActual.equalsIgnoreCase(normalizedExpected);
	}
}
