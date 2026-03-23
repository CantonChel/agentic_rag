package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MemoryFlushService {
	private final MemoryProperties properties;

	public MemoryFlushService(MemoryProperties properties) {
		this.properties = properties;
	}

	public void flushPreCompaction(String scopedSessionId, List<ChatMessage> messages) {
		if (!properties.isEnabled() || !properties.isFlushEnabled() || !properties.isPreCompactionFlushEnabled()) {
			return;
		}
		if (messages == null || messages.isEmpty()) {
			return;
		}
		String userId = SessionScope.userIdFromScopedSessionId(scopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
		String markdown = buildSnapshotMarkdown("pre-compaction", sessionId, messages);
		if (markdown.trim().isEmpty()) {
			return;
		}
		appendToDailyMemory(userId, markdown);
	}

	public void flushOnSessionReset(
		String scopedSessionId,
		List<ChatMessage> contextMessages,
		List<StoredMessageEntity> persistedMessages
	) {
		if (!properties.isEnabled() || !properties.isFlushEnabled() || !properties.isSessionResetFlushEnabled()) {
			return;
		}
		String userId = SessionScope.userIdFromScopedSessionId(scopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
		String markdown = buildSessionSummaryMarkdown(sessionId, contextMessages, persistedMessages);
		if (markdown.trim().isEmpty()) {
			return;
		}
		appendToSessionSummary(userId, sessionId, markdown);
	}

	private String buildSnapshotMarkdown(String reason, String sessionId, List<ChatMessage> messages) {
		int keep = properties.getFlushRecentMessages() > 0 ? properties.getFlushRecentMessages() : 12;
		int start = Math.max(0, messages.size() - keep);
		List<String> lines = new ArrayList<>();
		for (int i = start; i < messages.size(); i++) {
			ChatMessage m = messages.get(i);
			if (m == null || m.getType() == null || m.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			String content = sanitize(m.getContent());
			if (content.isEmpty()) {
				continue;
			}
			lines.add("- " + m.getType().name() + ": " + content);
		}
		if (lines.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder();
		out.append("## [")
			.append(OffsetDateTime.now())
			.append("] ")
			.append(reason)
			.append(" session=")
			.append(sessionId)
			.append("\n");
		for (String line : lines) {
			out.append(line).append("\n");
		}
		out.append("\n");
		return out.toString();
	}

	private void appendToDailyMemory(String userId, String markdown) {
		Path root = workspaceRoot();
		Path dir = root.resolve(properties.getUserMemoryBaseDir()).resolve(SessionScope.normalizeUserId(userId));
		Path file = dir.resolve(LocalDate.now().toString() + ".md");
		try {
			Files.createDirectories(dir);
			Files.writeString(
				file,
				markdown,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException ignored) {
			// avoid blocking chat flow when memory flush write fails
		}
	}

	private String buildSessionSummaryMarkdown(
		String sessionId,
		List<ChatMessage> contextMessages,
		List<StoredMessageEntity> persistedMessages
	) {
		List<String> lines = new ArrayList<>();
		int keep = properties.getFlushRecentMessages() > 0 ? properties.getFlushRecentMessages() : 12;

		if (persistedMessages != null && !persistedMessages.isEmpty()) {
			int start = Math.max(0, persistedMessages.size() - keep);
			for (int i = start; i < persistedMessages.size(); i++) {
				StoredMessageEntity e = persistedMessages.get(i);
				if (e == null || e.getType() == null || e.getContent() == null) {
					continue;
				}
				String type = e.getType().trim().toUpperCase();
				if (!"USER".equals(type) && !"ASSISTANT".equals(type) && !"THINKING".equals(type)) {
					continue;
				}
				String content = sanitize(e.getContent());
				if (!content.isEmpty()) {
					lines.add("- " + type + ": " + content);
				}
			}
		}

		if (lines.isEmpty() && contextMessages != null && !contextMessages.isEmpty()) {
			int start = Math.max(0, contextMessages.size() - keep);
			for (int i = start; i < contextMessages.size(); i++) {
				ChatMessage m = contextMessages.get(i);
				if (m == null || m.getType() == null || m.getType() == ChatMessageType.SYSTEM) {
					continue;
				}
				String content = sanitize(m.getContent());
				if (!content.isEmpty()) {
					lines.add("- " + m.getType().name() + ": " + content);
				}
			}
		}

		if (lines.isEmpty()) {
			return "";
		}

		StringBuilder out = new StringBuilder();
		out.append("# Session Summary\n");
		out.append("- session_id: ").append(sessionId).append("\n");
		out.append("- flushed_at: ").append(OffsetDateTime.now()).append("\n");
		out.append("- reason: session-reset\n\n");
		out.append("## Recent Messages\n");
		for (String line : lines) {
			out.append(line).append("\n");
		}
		out.append("\n");
		return out.toString();
	}

	private void appendToSessionSummary(String userId, String sessionId, String markdown) {
		Path root = workspaceRoot();
		Path dir = root.resolve(properties.getUserMemoryBaseDir())
			.resolve(SessionScope.normalizeUserId(userId))
			.resolve("sessions");
		Path file = dir.resolve(LocalDate.now() + "-" + SessionScope.normalizeSessionId(sessionId) + ".md");
		try {
			Files.createDirectories(dir);
			Files.writeString(
				file,
				markdown,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException ignored) {
			// avoid breaking reset flow
		}
	}

	private Path workspaceRoot() {
		String configured = properties.getWorkspaceRoot();
		if (configured == null || configured.trim().isEmpty()) {
			return Paths.get("").toAbsolutePath().normalize();
		}
		return Paths.get(configured).toAbsolutePath().normalize();
	}

	private String sanitize(String content) {
		if (content == null) {
			return "";
		}
		String oneLine = content.replace('\n', ' ').replace('\r', ' ').trim();
		if (oneLine.length() <= 220) {
			return oneLine;
		}
		return oneLine.substring(0, 220) + "...";
	}
}
