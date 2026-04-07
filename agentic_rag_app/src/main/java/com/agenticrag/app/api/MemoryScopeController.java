package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.SessionContextSnapshotStore;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.session.SessionScope;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/memory")
public class MemoryScopeController {
	private final MemoryFileService memoryFileService;
	private final SessionContextSnapshotStore sessionContextSnapshotStore;

	public MemoryScopeController(
		MemoryFileService memoryFileService,
		SessionContextSnapshotStore sessionContextSnapshotStore
	) {
		this.memoryFileService = memoryFileService;
		this.sessionContextSnapshotStore = sessionContextSnapshotStore;
	}

	@GetMapping(value = "/scopes", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<MemoryScopeView> scopes(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "includeGlobal", defaultValue = "true") boolean includeGlobal
	) {
		return Mono.fromCallable(() -> buildScopeView(userId, includeGlobal))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/session-context", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<SessionContextDetailView> sessionContext(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam("sessionId") String sessionId
	) {
		return Mono.fromCallable(() -> buildSessionContextDetail(userId, sessionId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private MemoryScopeView buildScopeView(String userId, boolean includeGlobal) {
		String normalizedUserId = SessionScope.normalizeUserId(userId);
		List<Path> files = new ArrayList<>(memoryFileService.discoverMemoryFiles(normalizedUserId, includeGlobal));
		List<MemoryBrowseController.MemoryFileView> globalFiles = files.stream()
			.filter(path -> "global".equals(memoryFileService.kindOf(normalizedUserId, path)))
			.map(path -> toFileView(normalizedUserId, path))
			.sorted(Comparator.comparing(MemoryBrowseController.MemoryFileView::getUpdatedAt).reversed())
			.collect(Collectors.toList());
		List<MemoryBrowseController.MemoryFileView> factFiles = files.stream()
			.filter(path -> "fact".equals(memoryFileService.kindOf(normalizedUserId, path)))
			.map(path -> toFileView(normalizedUserId, path))
			.sorted(
				Comparator.comparing(
					MemoryScopeController::factSortKey,
					Comparator.nullsLast(String::compareTo)
				).thenComparing(
					MemoryBrowseController.MemoryFileView::getPath,
					Comparator.nullsLast(String::compareTo)
				)
			)
			.collect(Collectors.toList());
		List<MemoryBrowseController.MemoryFileView> summaryFiles = files.stream()
			.filter(path -> "session_summary".equals(memoryFileService.kindOf(normalizedUserId, path)))
			.map(path -> toFileView(normalizedUserId, path))
			.sorted(
				Comparator.comparing(MemoryBrowseController.MemoryFileView::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
					.thenComparing(MemoryBrowseController.MemoryFileView::getPath, Comparator.nullsLast(String::compareTo))
			)
			.collect(Collectors.toList());
		List<SessionContextSummaryView> sessionContexts = sessionContextSnapshotStore.listUserSnapshots(normalizedUserId)
			.stream()
			.sorted(
				Comparator.comparing(
					SessionContextSnapshotStore.SessionContextSnapshotSummary::getUpdatedAt,
					Comparator.nullsLast(Comparator.reverseOrder())
				).thenComparing(SessionContextSnapshotStore.SessionContextSnapshotSummary::getSessionId, Comparator.nullsLast(String::compareTo))
			)
			.map(SessionContextSummaryView::new)
			.collect(Collectors.toList());
		return new MemoryScopeView(globalFiles, factFiles, summaryFiles, sessionContexts);
	}

	private SessionContextDetailView buildSessionContextDetail(String userId, String sessionId) {
		String normalizedUserId = SessionScope.normalizeUserId(userId);
		String normalizedSessionId = SessionScope.normalizeSessionId(sessionId);
		SessionContextSnapshotStore.SessionContextSnapshotSummary summary = sessionContextSnapshotStore.listUserSnapshots(normalizedUserId)
			.stream()
			.filter(item -> normalizedSessionId.equals(item.getSessionId()))
			.findFirst()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session context not found"));
		List<ChatMessage> messages = sessionContextSnapshotStore.loadSnapshot(summary.getScopedSessionId());
		if (messages.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session context not found");
		}
		List<SessionContextMessageView> messageViews = messages.stream()
			.map(SessionContextMessageView::new)
			.collect(Collectors.toList());
		return new SessionContextDetailView(
			summary.getSessionId(),
			summary.getMessageCount(),
			summary.getUpdatedAt() != null ? summary.getUpdatedAt().toString() : Instant.EPOCH.toString(),
			messageViews
		);
	}

	private MemoryBrowseController.MemoryFileView toFileView(String userId, Path path) {
		String rel = memoryFileService.relPath(path);
		String kind = memoryFileService.kindOf(userId, path);
		long size = 0L;
		String updatedAt = Instant.EPOCH.toString();
		try {
			size = java.nio.file.Files.size(path);
			updatedAt = java.nio.file.Files.getLastModifiedTime(path).toInstant().toString();
		} catch (Exception ignored) {
			// keep defaults
		}
		return new MemoryBrowseController.MemoryFileView(rel, rel, kind, size, updatedAt);
	}

	private String fileNameOf(String path) {
		if (path == null || path.trim().isEmpty()) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		if (slash < 0) {
			return path;
		}
		return path.substring(slash + 1);
	}

	private static String factSortKey(MemoryBrowseController.MemoryFileView view) {
		if (view == null) {
			return "";
		}
		String path = view.getPath();
		if (path == null || path.trim().isEmpty()) {
			return "";
		}
		int slash = path.lastIndexOf('/');
		if (slash < 0) {
			return path;
		}
		return path.substring(slash + 1);
	}

	public static class MemoryScopeView {
		private final List<MemoryBrowseController.MemoryFileView> globalFiles;
		private final List<MemoryBrowseController.MemoryFileView> factFiles;
		private final List<MemoryBrowseController.MemoryFileView> summaryFiles;
		private final List<SessionContextSummaryView> sessionContexts;

		public MemoryScopeView(
			List<MemoryBrowseController.MemoryFileView> globalFiles,
			List<MemoryBrowseController.MemoryFileView> factFiles,
			List<MemoryBrowseController.MemoryFileView> summaryFiles,
			List<SessionContextSummaryView> sessionContexts
		) {
			this.globalFiles = globalFiles;
			this.factFiles = factFiles;
			this.summaryFiles = summaryFiles;
			this.sessionContexts = sessionContexts;
		}

		public List<MemoryBrowseController.MemoryFileView> getGlobalFiles() {
			return globalFiles;
		}

		public List<MemoryBrowseController.MemoryFileView> getFactFiles() {
			return factFiles;
		}

		public List<MemoryBrowseController.MemoryFileView> getSummaryFiles() {
			return summaryFiles;
		}

		public List<SessionContextSummaryView> getSessionContexts() {
			return sessionContexts;
		}
	}

	public static class SessionContextSummaryView {
		private final String sessionId;
		private final long messageCount;
		private final String updatedAt;

		public SessionContextSummaryView(SessionContextSnapshotStore.SessionContextSnapshotSummary summary) {
			this.sessionId = summary != null ? summary.getSessionId() : null;
			this.messageCount = summary != null ? summary.getMessageCount() : 0L;
			this.updatedAt = summary != null && summary.getUpdatedAt() != null
				? summary.getUpdatedAt().toString()
				: Instant.EPOCH.toString();
		}

		public String getSessionId() {
			return sessionId;
		}

		public long getMessageCount() {
			return messageCount;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class SessionContextDetailView {
		private final String sessionId;
		private final long messageCount;
		private final String updatedAt;
		private final List<SessionContextMessageView> messages;

		public SessionContextDetailView(
			String sessionId,
			long messageCount,
			String updatedAt,
			List<SessionContextMessageView> messages
		) {
			this.sessionId = sessionId;
			this.messageCount = messageCount;
			this.updatedAt = updatedAt;
			this.messages = messages;
		}

		public String getSessionId() {
			return sessionId;
		}

		public long getMessageCount() {
			return messageCount;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}

		public List<SessionContextMessageView> getMessages() {
			return messages;
		}
	}

	public static class SessionContextMessageView {
		private final String type;
		private final String content;

		public SessionContextMessageView(ChatMessage message) {
			this.type = message != null && message.getType() != null ? message.getType().name() : "UNKNOWN";
			this.content = message != null ? message.getContent() : null;
		}

		public String getType() {
			return type;
		}

		public String getContent() {
			return content;
		}
	}
}
