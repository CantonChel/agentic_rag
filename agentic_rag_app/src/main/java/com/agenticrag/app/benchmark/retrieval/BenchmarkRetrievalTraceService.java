package com.agenticrag.app.benchmark.retrieval;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
}
