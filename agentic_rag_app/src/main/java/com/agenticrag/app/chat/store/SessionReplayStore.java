package com.agenticrag.app.chat.store;

import com.agenticrag.app.llm.LlmStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionReplayStore {
	private final SessionReplayEventRepository repo;
	private final ObjectMapper objectMapper;

	public SessionReplayStore(SessionReplayEventRepository repo, ObjectMapper objectMapper) {
		this.repo = repo;
		this.objectMapper = objectMapper;
	}

	public void append(String sessionId, LlmStreamEvent event) {
		if (event == null || event.getType() == null || event.getType().trim().isEmpty()) {
			return;
		}
		if ("session_switched".equals(event.getType()) || "session_switched".equals(event.getFinishReason())) {
			return;
		}
		SessionReplayEventEntity entity = new SessionReplayEventEntity();
		entity.setSessionId(normalize(sessionId));
		entity.setType(event.getType());
		entity.setContent(event.getContent());
		entity.setFinishReason(event.getFinishReason());
		entity.setSource(event.getSource());
		entity.setOriginModel(event.getOriginModel());
		entity.setRoundId(event.getRoundId());
		entity.setTurnId(event.getTurnId());
		entity.setSequenceId(event.getSequenceId());
		entity.setEventTs(event.getTs());
		entity.setToolCallId(event.getToolCallId());
		entity.setToolName(event.getToolName());
		entity.setStatus(event.getStatus());
		entity.setDurationMs(event.getDurationMs());
		entity.setArgsPreviewJson(writeJson(event.getArgsPreview()));
		entity.setResultPreviewJson(writeJson(event.getResultPreview()));
		entity.setError(event.getError());
		entity.setCreatedAt(Instant.now());
		repo.save(entity);
	}

	public List<ReplayEventRecord> list(String sessionId) {
		List<SessionReplayEventEntity> rows = repo.findBySessionIdOrderByEventTsAscSequenceIdAscIdAsc(normalize(sessionId));
		List<ReplayEventRecord> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (SessionReplayEventEntity row : rows) {
			if (row == null) {
				continue;
			}
			out.add(
				new ReplayEventRecord(
					row.getSessionId(),
					toStreamEvent(row),
					row.getCreatedAt()
				)
			);
		}
		return out;
	}

	public List<SessionReplayStats> listSessionStats() {
		List<SessionReplayEventRepository.SessionReplayStatsView> rows = repo.fetchSessionStats();
		List<SessionReplayStats> out = new ArrayList<>();
		if (rows == null) {
			return out;
		}
		for (SessionReplayEventRepository.SessionReplayStatsView row : rows) {
			if (row == null || row.getSessionId() == null || row.getSessionId().trim().isEmpty()) {
				continue;
			}
			out.add(new SessionReplayStats(row.getSessionId(), row.getLastEventTs(), row.getEventCount()));
		}
		return out;
	}

	@Transactional
	public void clear(String sessionId) {
		repo.deleteBySessionId(normalize(sessionId));
	}

	private LlmStreamEvent toStreamEvent(SessionReplayEventEntity row) {
		return new LlmStreamEvent(
			row.getType(),
			row.getContent(),
			null,
			row.getFinishReason(),
			null,
			row.getSource(),
			row.getOriginModel(),
			row.getRoundId(),
			null,
			row.getTurnId(),
			row.getSequenceId(),
			row.getEventTs(),
			row.getToolCallId(),
			row.getToolName(),
			row.getStatus(),
			row.getDurationMs(),
			readJson(row.getArgsPreviewJson()),
			readJson(row.getResultPreviewJson()),
			row.getError()
		);
	}

	private String writeJson(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(node);
		} catch (Exception ignored) {
			return node.toString();
		}
	}

	private JsonNode readJson(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return objectMapper.readTree(value);
		} catch (Exception ignored) {
			return objectMapper.createObjectNode().put("_raw", value);
		}
	}

	private String normalize(String sessionId) {
		if (sessionId == null || sessionId.trim().isEmpty()) {
			return "default";
		}
		return sessionId.trim();
	}

	public static class ReplayEventRecord {
		private final String sessionId;
		private final LlmStreamEvent event;
		private final Instant createdAt;

		public ReplayEventRecord(String sessionId, LlmStreamEvent event, Instant createdAt) {
			this.sessionId = sessionId;
			this.event = event;
			this.createdAt = createdAt;
		}

		public String getSessionId() {
			return sessionId;
		}

		public LlmStreamEvent getEvent() {
			return event;
		}

		public Instant getCreatedAt() {
			return createdAt;
		}
	}

	public static class SessionReplayStats {
		private final String sessionId;
		private final Long lastEventTs;
		private final long eventCount;

		public SessionReplayStats(String sessionId, Long lastEventTs, long eventCount) {
			this.sessionId = sessionId;
			this.lastEventTs = lastEventTs;
			this.eventCount = eventCount;
		}

		public String getSessionId() {
			return sessionId;
		}

		public Long getLastEventTs() {
			return lastEventTs;
		}

		public long getEventCount() {
			return eventCount;
		}
	}
}
