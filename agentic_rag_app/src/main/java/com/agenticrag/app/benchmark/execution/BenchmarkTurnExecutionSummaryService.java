package com.agenticrag.app.benchmark.execution;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BenchmarkTurnExecutionSummaryService {
	private static final TypeReference<List<BenchmarkTurnExecutionToolCall>> TOOL_CALLS_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<BenchmarkTurnRetrievalTraceRef>> TRACE_REFS_TYPE = new TypeReference<>() {
	};

	private final BenchmarkTurnExecutionSummaryRepository benchmarkTurnExecutionSummaryRepository;
	private final BenchmarkBuildService benchmarkBuildService;
	private final ObjectMapper objectMapper;

	public BenchmarkTurnExecutionSummaryService(
		BenchmarkTurnExecutionSummaryRepository benchmarkTurnExecutionSummaryRepository,
		BenchmarkBuildService benchmarkBuildService,
		ObjectMapper objectMapper
	) {
		this.benchmarkTurnExecutionSummaryRepository = benchmarkTurnExecutionSummaryRepository;
		this.benchmarkBuildService = benchmarkBuildService;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public BenchmarkTurnExecutionSummaryEntity saveSummary(BenchmarkTurnExecutionSummaryWriteModel summary) {
		if (summary == null) {
			throw new IllegalArgumentException("summary is required");
		}
		String turnId = normalize(summary.turnId(), null);
		if (turnId == null) {
			throw new IllegalArgumentException("turnId is required");
		}

		Instant now = Instant.now();
		BenchmarkTurnExecutionSummaryEntity entity = benchmarkTurnExecutionSummaryRepository.findByTurnId(turnId)
			.orElseGet(BenchmarkTurnExecutionSummaryEntity::new);
		if (entity.getCreatedAt() == null) {
			entity.setCreatedAt(summary.createdAt() != null ? summary.createdAt() : now);
		}
		entity.setTurnId(turnId);
		entity.setSessionId(normalize(summary.sessionId(), "default"));
		entity.setUserId(normalize(summary.userId(), "anonymous"));
		entity.setTraceId(normalizeNullable(summary.traceId()));
		entity.setProvider(normalizeLower(summary.provider(), "unknown"));
		entity.setOriginModel(normalizeNullable(summary.originModel()));
		entity.setKnowledgeBaseId(normalizeNullable(summary.knowledgeBaseId()));
		entity.setBuildId(resolveBuildId(summary.buildId(), summary.knowledgeBaseId()));
		entity.setKbScope(normalizeLower(summary.kbScope(), "auto"));
		entity.setEvalMode(normalizeLower(summary.evalMode(), "default"));
		entity.setThinkingProfile(normalizeLower(summary.thinkingProfile(), "default"));
		entity.setMemoryEnabled(summary.memoryEnabled());
		entity.setUserQuestion(normalize(summary.userQuestion(), ""));
		entity.setFinalAnswer(normalizeNullable(summary.finalAnswer()));
		entity.setFinishReason(normalizeLower(summary.finishReason(), "unknown"));
		entity.setLatencyMs(summary.latencyMs());
		entity.setToolCallsJson(serialize(summary.toolCalls()));
		entity.setRetrievalTraceIdsJson(serialize(summary.retrievalTraceIds()));
		entity.setRetrievalTraceRefsJson(serialize(summary.retrievalTraceRefs()));
		entity.setErrorMessage(normalizeNullable(summary.errorMessage()));
		entity.setCompletedAt(summary.completedAt() != null ? summary.completedAt() : now);
		return benchmarkTurnExecutionSummaryRepository.save(entity);
	}

	@Transactional(readOnly = true)
	public Optional<BenchmarkTurnExecutionSummaryEntity> findByTurnId(String turnId) {
		String normalizedTurnId = normalizeNullable(turnId);
		if (normalizedTurnId == null) {
			return Optional.empty();
		}
		return benchmarkTurnExecutionSummaryRepository.findByTurnId(normalizedTurnId);
	}

	@Transactional(readOnly = true)
	public Optional<BenchmarkTurnExecutionSummaryView> findViewByTurnId(String turnId) {
		return findByTurnId(turnId).map(this::toView);
	}

	private BenchmarkTurnExecutionSummaryView toView(BenchmarkTurnExecutionSummaryEntity entity) {
		return new BenchmarkTurnExecutionSummaryView(
			entity.getTurnId(),
			entity.getSessionId(),
			entity.getUserId(),
			entity.getTraceId(),
			entity.getProvider(),
			entity.getOriginModel(),
			entity.getBuildId(),
			entity.getKnowledgeBaseId(),
			entity.getKbScope(),
			entity.getEvalMode(),
			entity.getThinkingProfile(),
			entity.isMemoryEnabled(),
			entity.getUserQuestion(),
			entity.getFinalAnswer(),
			entity.getFinishReason(),
			entity.getLatencyMs(),
			readList(entity.getToolCallsJson(), TOOL_CALLS_TYPE),
			readList(entity.getRetrievalTraceIdsJson(), STRING_LIST_TYPE),
			readList(entity.getRetrievalTraceRefsJson(), TRACE_REFS_TYPE),
			entity.getErrorMessage(),
			entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null,
			entity.getCompletedAt() != null ? entity.getCompletedAt().toString() : null
		);
	}

	private String resolveBuildId(String buildId, String knowledgeBaseId) {
		String normalizedBuildId = normalizeNullable(buildId);
		if (normalizedBuildId != null) {
			return normalizedBuildId;
		}
		String normalizedKnowledgeBaseId = normalizeNullable(knowledgeBaseId);
		if (normalizedKnowledgeBaseId == null) {
			return null;
		}
		Optional<BenchmarkBuildEntity> build = benchmarkBuildService.findBuildByKnowledgeBaseId(normalizedKnowledgeBaseId);
		return build.map(BenchmarkBuildEntity::getBuildId).orElse(null);
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value != null ? value : Collections.emptyList());
		} catch (Exception e) {
			throw new IllegalArgumentException("failed to serialize turn execution summary", e);
		}
	}

	private <T> List<T> readList(String raw, TypeReference<List<T>> typeReference) {
		String normalizedRaw = normalizeNullable(raw);
		if (normalizedRaw == null) {
			return new ArrayList<>();
		}
		try {
			return objectMapper.readValue(normalizedRaw, typeReference);
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	private String normalize(String value, String fallback) {
		String normalized = normalizeNullable(value);
		return normalized != null ? normalized : fallback;
	}

	private String normalizeLower(String value, String fallback) {
		String normalized = normalize(value, fallback);
		return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
