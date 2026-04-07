package com.agenticrag.app.memory.audit;

import com.agenticrag.app.memory.MemoryFactBucket;
import com.agenticrag.app.memory.MemoryFactCompareResult;
import com.agenticrag.app.memory.MemoryFactRecord;
import com.agenticrag.app.memory.audit.repo.MemoryFactOperationLogRepository;
import com.agenticrag.app.session.SessionScope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MemoryFactOperationLogService {
	private static final int MAX_LIMIT = 200;
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};
	private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<List<Map<String, Object>>>() {};

	private final MemoryFactOperationLogRepository repository;
	private final ObjectMapper objectMapper;

	public MemoryFactOperationLogService(
		MemoryFactOperationLogRepository repository,
		ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public void append(AppendRequest request) {
		if (request == null) {
			return;
		}
		MemoryFactOperationLogEntity entity = new MemoryFactOperationLogEntity();
		entity.setFlushId(safe(request.flushId()));
		entity.setUserId(SessionScope.normalizeUserId(request.userId()));
		entity.setSessionId(SessionScope.normalizeSessionId(request.sessionId()));
		entity.setTrigger(safe(request.trigger()));
		entity.setFilePath(safe(request.filePath()));
		entity.setBucket(resolveBucketValue(request.bucket(), request.incomingFact()));
		entity.setDecision(request.decision() != null ? request.decision() : MemoryFactCompareResult.Decision.NONE);
		entity.setDecisionSource(
			request.decisionSource() != null ? request.decisionSource() : MemoryFactOperationDecisionSource.LLM_COMPARE
		);
		entity.setWriteOutcome(
			request.writeOutcome() != null ? request.writeOutcome() : MemoryFactOperationWriteOutcome.SKIPPED_NONE
		);
		entity.setCandidateCount(Math.max(0, request.candidateCount()));
		entity.setMatchedBlockId(blankToNull(request.matchedBlockId()));
		entity.setTargetBlockId(blankToNull(request.targetBlockId()));
		entity.setIncomingFactJson(toJson(toFactMap(request.incomingFact()), "{}"));
		entity.setMatchedFactJson(toNullableJson(request.matchedFact()));
		entity.setCandidateFactsJson(toJson(toFactList(request.candidateFacts()), "[]"));
		entity.setCreatedAt(request.createdAt() != null ? request.createdAt() : Instant.now());
		repository.save(entity);
	}

	public List<FactOperationView> list(String userId, String filePath, int limit) {
		String normalizedUserId = SessionScope.normalizeUserId(userId);
		int sanitizedLimit = sanitizeLimit(limit);
		List<MemoryFactOperationLogEntity> entities = repository.findByUserIdAndFilePathOrderByCreatedAtDescIdDesc(
			normalizedUserId,
			safe(filePath)
		);
		if (entities.isEmpty()) {
			return Collections.emptyList();
		}
		List<FactOperationView> views = new ArrayList<>();
		for (MemoryFactOperationLogEntity entity : entities) {
			if (entity == null) {
				continue;
			}
			views.add(new FactOperationView(
				entity.getFlushId(),
				entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : Instant.EPOCH.toString(),
				entity.getDecision() != null ? entity.getDecision().name() : MemoryFactCompareResult.Decision.NONE.name(),
				entity.getDecisionSource() != null ? entity.getDecisionSource().name() : MemoryFactOperationDecisionSource.LLM_COMPARE.name(),
				entity.getWriteOutcome() != null ? entity.getWriteOutcome().name() : MemoryFactOperationWriteOutcome.SKIPPED_NONE.name(),
				entity.getTrigger(),
				entity.getSessionId(),
				entity.getFilePath(),
				entity.getBucket(),
				entity.getCandidateCount(),
				entity.getMatchedBlockId(),
				entity.getTargetBlockId(),
				parseMap(entity.getIncomingFactJson()),
				parseNullableMap(entity.getMatchedFactJson()),
				parseList(entity.getCandidateFactsJson())
			));
			if (views.size() >= sanitizedLimit) {
				break;
			}
		}
		return views;
	}

	private int sanitizeLimit(int limit) {
		if (limit <= 0) {
			return 50;
		}
		return Math.min(limit, MAX_LIMIT);
	}

	private String resolveBucketValue(MemoryFactBucket bucket, MemoryFactRecord fact) {
		if (bucket != null) {
			return bucket.getValue();
		}
		if (fact != null && fact.getBucket() != null) {
			return fact.getBucket().getValue();
		}
		return "";
	}

	private String toNullableJson(MemoryFactRecord fact) {
		Map<String, Object> payload = toFactMap(fact);
		if (payload.isEmpty()) {
			return null;
		}
		return toJson(payload, null);
	}

	private String toJson(Object value, String fallback) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			return fallback;
		}
	}

	private Map<String, Object> parseMap(String json) {
		if (json == null || json.trim().isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		} catch (Exception e) {
			return Collections.emptyMap();
		}
	}

	private Map<String, Object> parseNullableMap(String json) {
		if (json == null || json.trim().isEmpty()) {
			return null;
		}
		Map<String, Object> payload = parseMap(json);
		return payload.isEmpty() ? null : payload;
	}

	private List<Map<String, Object>> parseList(String json) {
		if (json == null || json.trim().isEmpty()) {
			return Collections.emptyList();
		}
		try {
			return objectMapper.readValue(json, LIST_TYPE);
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private List<Map<String, Object>> toFactList(List<MemoryFactRecord> facts) {
		List<Map<String, Object>> payload = new ArrayList<>();
		if (facts == null || facts.isEmpty()) {
			return payload;
		}
		for (MemoryFactRecord fact : facts) {
			Map<String, Object> map = toFactMap(fact);
			if (!map.isEmpty()) {
				payload.add(map);
			}
		}
		return payload;
	}

	private Map<String, Object> toFactMap(MemoryFactRecord fact) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (fact == null) {
			return payload;
		}
		if (fact.getBucket() != null) {
			payload.put("bucket", fact.getBucket().getValue());
		}
		payload.put("subject", safe(fact.getSubject()));
		payload.put("attribute", safe(fact.getAttribute()));
		payload.put("value", safe(fact.getValue()));
		payload.put("statement", safe(fact.getStatement()));
		return payload;
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private String blankToNull(String value) {
		String normalized = safe(value);
		return normalized.isEmpty() ? null : normalized;
	}

	public record AppendRequest(
		String flushId,
		String userId,
		String sessionId,
		String trigger,
		String filePath,
		MemoryFactBucket bucket,
		MemoryFactCompareResult.Decision decision,
		MemoryFactOperationDecisionSource decisionSource,
		MemoryFactOperationWriteOutcome writeOutcome,
		int candidateCount,
		String matchedBlockId,
		String targetBlockId,
		MemoryFactRecord incomingFact,
		MemoryFactRecord matchedFact,
		List<MemoryFactRecord> candidateFacts,
		Instant createdAt
	) {}

	public static class FactOperationView {
		private final String flushId;
		private final String createdAt;
		private final String decision;
		private final String decisionSource;
		private final String writeOutcome;
		private final String trigger;
		private final String sessionId;
		private final String filePath;
		private final String bucket;
		private final int candidateCount;
		private final String matchedBlockId;
		private final String targetBlockId;
		private final Map<String, Object> incomingFact;
		private final Map<String, Object> matchedFact;
		private final List<Map<String, Object>> candidateFacts;

		public FactOperationView(
			String flushId,
			String createdAt,
			String decision,
			String decisionSource,
			String writeOutcome,
			String trigger,
			String sessionId,
			String filePath,
			String bucket,
			int candidateCount,
			String matchedBlockId,
			String targetBlockId,
			Map<String, Object> incomingFact,
			Map<String, Object> matchedFact,
			List<Map<String, Object>> candidateFacts
		) {
			this.flushId = flushId;
			this.createdAt = createdAt;
			this.decision = decision;
			this.decisionSource = decisionSource;
			this.writeOutcome = writeOutcome;
			this.trigger = trigger;
			this.sessionId = sessionId;
			this.filePath = filePath;
			this.bucket = bucket;
			this.candidateCount = candidateCount;
			this.matchedBlockId = matchedBlockId;
			this.targetBlockId = targetBlockId;
			this.incomingFact = incomingFact;
			this.matchedFact = matchedFact;
			this.candidateFacts = candidateFacts;
		}

		public String getFlushId() {
			return flushId;
		}

		public String getCreatedAt() {
			return createdAt;
		}

		public String getDecision() {
			return decision;
		}

		public String getDecisionSource() {
			return decisionSource;
		}

		public String getWriteOutcome() {
			return writeOutcome;
		}

		public String getTrigger() {
			return trigger;
		}

		public String getSessionId() {
			return sessionId;
		}

		public String getFilePath() {
			return filePath;
		}

		public String getBucket() {
			return bucket;
		}

		public int getCandidateCount() {
			return candidateCount;
		}

		public String getMatchedBlockId() {
			return matchedBlockId;
		}

		public String getTargetBlockId() {
			return targetBlockId;
		}

		public Map<String, Object> getIncomingFact() {
			return incomingFact;
		}

		public Map<String, Object> getMatchedFact() {
			return matchedFact;
		}

		public List<Map<String, Object>> getCandidateFacts() {
			return candidateFacts;
		}
	}
}
