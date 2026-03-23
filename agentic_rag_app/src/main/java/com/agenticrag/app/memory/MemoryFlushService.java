package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
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
