package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.session.ChatSession;
import com.agenticrag.app.session.SessionScope;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.session.SessionSummaryService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {
	private final SessionManager sessionManager;
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final SessionReplayStore sessionReplayStore;
	private final SessionSummaryService sessionSummaryService;

	public SessionController(
		SessionManager sessionManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		SessionReplayStore sessionReplayStore,
		SessionSummaryService sessionSummaryService
	) {
		this.sessionManager = sessionManager;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.sessionReplayStore = sessionReplayStore;
		this.sessionSummaryService = sessionSummaryService;
	}

	@PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public CreateSessionResponse create(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		ChatSession session = sessionManager.create(userId);
		return new CreateSessionResponse(session.getId());
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<SessionSummaryResponse> list(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		return sessionSummaryService.listForUser(userId)
			.stream()
			.map(SessionSummaryResponse::new)
			.collect(java.util.stream.Collectors.toList());
	}

	@DeleteMapping("/{sessionId}")
	public void delete(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		sessionManager.delete(userId, sessionId);
	}

	@GetMapping(value = "/{sessionId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ChatMessageView> messages(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "contentsOnly", defaultValue = "false") boolean contentsOnly
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		if (contentsOnly) {
			return contextManager.getContext(scopedSessionId).stream()
				.map(c -> new ChatMessageView(c.getType().name(), c.getContent(), null, null, null, null, null))
				.collect(Collectors.toList());
		}

		return persistentMessageStore.list(scopedSessionId).stream()
			.map(e -> toView(e, persistentMessageStore))
			.collect(Collectors.toList());
	}

	@GetMapping(value = "/{sessionId}/replay", produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ReplayEntryView> replay(
		@PathVariable("sessionId") String sessionId,
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId
	) {
		String scopedSessionId = SessionScope.scopedSessionId(userId, sessionId);
		List<SessionReplayStore.ReplayEventRecord> replayEvents = sessionReplayStore.list(scopedSessionId);
		if (replayEvents == null || replayEvents.isEmpty()) {
			return java.util.Collections.emptyList();
		}

		List<ReplayTimelineItem> timeline = new ArrayList<>();
		for (StoredMessageEntity message : persistentMessageStore.list(scopedSessionId)) {
			if (message == null || !"USER".equals(message.getType())) {
				continue;
			}
			Instant createdAt = message.getCreatedAt();
			long sortTs = createdAt != null ? createdAt.toEpochMilli() : 0L;
			timeline.add(new ReplayTimelineItem(sortTs, 0, null, ReplayEntryView.userMessage(message.getContent(), createdAt)));
		}
		for (SessionReplayStore.ReplayEventRecord record : replayEvents) {
			if (record == null || record.getEvent() == null) {
				continue;
			}
			LlmStreamEvent event = record.getEvent();
			long sortTs = event.getTs() != null
				? event.getTs()
				: (record.getCreatedAt() != null ? record.getCreatedAt().toEpochMilli() : 0L);
			timeline.add(
				new ReplayTimelineItem(
					sortTs,
					1,
					event.getSequenceId(),
					ReplayEntryView.assistantEvent(event, record.getCreatedAt())
				)
			);
		}

		timeline.sort(
			Comparator.comparingLong(ReplayTimelineItem::getSortBucket)
				.thenComparingInt(ReplayTimelineItem::getKindOrder)
				.thenComparingLong(ReplayTimelineItem::getSortTs)
				.thenComparing(ReplayTimelineItem::getSequenceId, Comparator.nullsLast(Long::compareTo))
		);
		return timeline.stream()
			.map(ReplayTimelineItem::getView)
			.collect(Collectors.toList());
	}

	private static ChatMessageView toView(StoredMessageEntity e, PersistentMessageStore store) {
		if (e == null) {
			return new ChatMessageView("UNKNOWN", null, null, null, null, null, null);
		}

		if ("TOOL_CALL".equals(e.getType())) {
			List<LlmToolCall> calls = store.parseToolCalls(e.getContent());
			return new ChatMessageView(e.getType(), null, calls, null, null, null, null);
		}
		if ("TOOL_RESULT".equals(e.getType())) {
			return new ChatMessageView(
				e.getType(),
				e.getContent(),
				null,
				e.getToolName(),
				e.getToolCallId(),
				e.getSuccess(),
				e.getContent()
			);
		}
		return new ChatMessageView(e.getType(), e.getContent(), null, null, null, null, null);
	}

	public static class CreateSessionResponse {
		private final String sessionId;

		public CreateSessionResponse(String sessionId) {
			this.sessionId = sessionId;
		}

		public String getSessionId() {
			return sessionId;
		}
	}

	public static class SessionSummaryResponse {
		private final String sessionId;
		private final java.time.Instant createdAt;
		private final java.time.Instant lastActiveAt;
		private final boolean hasMessages;

		public SessionSummaryResponse(SessionSummaryService.SessionSummary summary) {
			this.sessionId = summary != null ? summary.getSessionId() : null;
			this.createdAt = summary != null ? summary.getCreatedAt() : null;
			this.lastActiveAt = summary != null ? summary.getLastActiveAt() : null;
			this.hasMessages = summary != null && summary.isHasMessages();
		}

		public String getSessionId() {
			return sessionId;
		}

		public java.time.Instant getCreatedAt() {
			return createdAt;
		}

		public java.time.Instant getLastActiveAt() {
			return lastActiveAt;
		}

		public boolean isHasMessages() {
			return hasMessages;
		}
	}

	public static class ChatMessageView {
		private final String type;
		private final String content;
		private final List<LlmToolCall> toolCalls;
		private final String toolName;
		private final String toolCallId;
		private final Boolean success;
		private final String result;

		public ChatMessageView(
			String type,
			String content,
			List<LlmToolCall> toolCalls,
			String toolName,
			String toolCallId,
			Boolean success,
			String result
		) {
			this.type = type;
			this.content = content;
			this.toolCalls = toolCalls;
			this.toolName = toolName;
			this.toolCallId = toolCallId;
			this.success = success;
			this.result = result;
		}

		public String getType() {
			return type;
		}

		public String getContent() {
			return content;
		}

		public List<LlmToolCall> getToolCalls() {
			return toolCalls;
		}

		public String getToolName() {
			return toolName;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public Boolean getSuccess() {
			return success;
		}

		public String getResult() {
			return result;
		}
	}

	public static class ReplayEntryView {
		private final String kind;
		private final Instant createdAt;
		private final String type;
		private final String content;
		private final String finishReason;
		private final String source;
		private final String originModel;
		private final Integer roundId;
		private final String turnId;
		private final Long sequenceId;
		private final Long ts;
		private final String toolCallId;
		private final String toolName;
		private final String status;
		private final Long durationMs;
		private final com.fasterxml.jackson.databind.JsonNode argsPreview;
		private final com.fasterxml.jackson.databind.JsonNode resultPreview;
		private final String error;

		private ReplayEntryView(
			String kind,
			Instant createdAt,
			String type,
			String content,
			String finishReason,
			String source,
			String originModel,
			Integer roundId,
			String turnId,
			Long sequenceId,
			Long ts,
			String toolCallId,
			String toolName,
			String status,
			Long durationMs,
			com.fasterxml.jackson.databind.JsonNode argsPreview,
			com.fasterxml.jackson.databind.JsonNode resultPreview,
			String error
		) {
			this.kind = kind;
			this.createdAt = createdAt;
			this.type = type;
			this.content = content;
			this.finishReason = finishReason;
			this.source = source;
			this.originModel = originModel;
			this.roundId = roundId;
			this.turnId = turnId;
			this.sequenceId = sequenceId;
			this.ts = ts;
			this.toolCallId = toolCallId;
			this.toolName = toolName;
			this.status = status;
			this.durationMs = durationMs;
			this.argsPreview = argsPreview;
			this.resultPreview = resultPreview;
			this.error = error;
		}

		public static ReplayEntryView userMessage(String content, Instant createdAt) {
			return new ReplayEntryView("user_message", createdAt, null, content, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
		}

		public static ReplayEntryView assistantEvent(LlmStreamEvent event, Instant createdAt) {
			return new ReplayEntryView(
				"assistant_event",
				createdAt,
				event.getType(),
				event.getContent(),
				event.getFinishReason(),
				event.getSource(),
				event.getOriginModel(),
				event.getRoundId(),
				event.getTurnId(),
				event.getSequenceId(),
				event.getTs(),
				event.getToolCallId(),
				event.getToolName(),
				event.getStatus(),
				event.getDurationMs(),
				event.getArgsPreview(),
				event.getResultPreview(),
				event.getError()
			);
		}

		public String getKind() {
			return kind;
		}

		public Instant getCreatedAt() {
			return createdAt;
		}

		public String getType() {
			return type;
		}

		public String getContent() {
			return content;
		}

		public String getFinishReason() {
			return finishReason;
		}

		public String getSource() {
			return source;
		}

		public String getOriginModel() {
			return originModel;
		}

		public Integer getRoundId() {
			return roundId;
		}

		public String getTurnId() {
			return turnId;
		}

		public Long getSequenceId() {
			return sequenceId;
		}

		public Long getTs() {
			return ts;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public String getToolName() {
			return toolName;
		}

		public String getStatus() {
			return status;
		}

		public Long getDurationMs() {
			return durationMs;
		}

		public com.fasterxml.jackson.databind.JsonNode getArgsPreview() {
			return argsPreview;
		}

		public com.fasterxml.jackson.databind.JsonNode getResultPreview() {
			return resultPreview;
		}

		public String getError() {
			return error;
		}
	}

	private static class ReplayTimelineItem {
		private static final long SORT_BUCKET_WINDOW_MS = 5000L;

		private final long sortBucket;
		private final long sortTs;
		private final int kindOrder;
		private final Long sequenceId;
		private final ReplayEntryView view;

		private ReplayTimelineItem(long sortTs, int kindOrder, Long sequenceId, ReplayEntryView view) {
			this.sortBucket = sortTs / SORT_BUCKET_WINDOW_MS;
			this.sortTs = sortTs;
			this.kindOrder = kindOrder;
			this.sequenceId = sequenceId;
			this.view = view;
		}

		public long getSortBucket() {
			return sortBucket;
		}

		public long getSortTs() {
			return sortTs;
		}

		public int getKindOrder() {
			return kindOrder;
		}

		public Long getSequenceId() {
			return sequenceId;
		}

		public ReplayEntryView getView() {
			return view;
		}
	}
}
