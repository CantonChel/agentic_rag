package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SessionArchiveService {
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final MemoryProperties properties;
	private final MemoryLlmExtractor memoryLlmExtractor;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;

	public SessionArchiveService(
		MemoryProperties properties,
		MemoryLlmExtractor memoryLlmExtractor,
		MemoryFileService memoryFileService,
		MemoryBlockParser memoryBlockParser
	) {
		this.properties = properties;
		this.memoryLlmExtractor = memoryLlmExtractor;
		this.memoryFileService = memoryFileService;
		this.memoryBlockParser = memoryBlockParser;
	}

	public void archive(
		String scopedSessionId,
		String reason,
		List<ChatMessage> contextMessages,
		List<StoredMessageEntity> persistedMessages
	) {
		if (!properties.isEnabled() || !properties.isFlushEnabled()) {
			return;
		}
		String userId = SessionScope.userIdFromScopedSessionId(scopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
		List<String> projectedLines = extractProjectedLines(contextMessages);
		if (projectedLines.isEmpty()) {
			return;
		}
		String normalizedReason = normalizeReason(reason);
		String body = memoryLlmExtractor.generateSessionSummary(userId, sessionId, normalizedReason, projectedLines);
		if (body == null || body.trim().isEmpty()) {
			return;
		}
		String slug = memoryLlmExtractor.generateSessionSlug(userId, sessionId, projectedLines);
		Path file = memoryFileService.summariesDir(userId).resolve(LocalDate.now() + "-" + normalizeSlug(slug) + ".md");
		MemoryBlockMetadata metadata = new MemoryBlockMetadata(
			MemoryBlockMetadata.SCHEMA_V2,
			MemoryBlockMetadata.KIND_SESSION_SUMMARY,
			UUID.randomUUID().toString(),
			SessionScope.normalizeUserId(userId),
			SessionScope.normalizeSessionId(sessionId),
			OffsetDateTime.now().toString(),
			null,
			"session_lifecycle",
			null,
			null,
			normalizedReason,
			normalizeSlug(slug)
		);
		appendBlock(file, metadata, body.trim());
	}

	private List<String> extractProjectedLines(List<ChatMessage> contextMessages) {
		List<String> lines = new ArrayList<>();
		if (contextMessages == null || contextMessages.isEmpty()) {
			return lines;
		}
		for (ChatMessage message : contextMessages) {
			if (message == null || message.getType() == null || message.getContent() == null) {
				continue;
			}
			String type = message.getType().name();
			if (!"USER".equals(type) && !"ASSISTANT".equals(type)) {
				continue;
			}
			String content = sanitize(message.getContent());
			if (content.isEmpty()) {
				continue;
			}
			lines.add(type + ": " + content);
		}
		int maxMessages = 15;
		if (lines.size() <= maxMessages) {
			return lines;
		}
		return new ArrayList<>(lines.subList(lines.size() - maxMessages, lines.size()));
	}

	private void appendBlock(Path file, MemoryBlockMetadata metadata, String body) {
		String markdown = memoryBlockParser.renderBlock(metadata, body);
		if (markdown.trim().isEmpty()) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(
				file,
				markdown,
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.APPEND
			);
		} catch (IOException ignored) {
			// avoid blocking session lifecycle on memory summary write failure
		}
	}

	private String sanitize(String content) {
		if (content == null) {
			return "";
		}
		return WHITESPACE.matcher(content).replaceAll(" ").trim();
	}

	private String normalizeReason(String reason) {
		String normalized = reason == null ? "" : reason.trim();
		return normalized.isEmpty() ? "session_lifecycle" : normalized;
	}

	private String normalizeSlug(String raw) {
		String candidate = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (candidate.isEmpty()) {
			return "session-memory";
		}
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < candidate.length(); i++) {
			char ch = candidate.charAt(i);
			if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
				out.append(ch);
			} else if (ch == '-' || ch == '_' || Character.isWhitespace(ch)) {
				if (out.length() == 0 || out.charAt(out.length() - 1) == '-') {
					continue;
				}
				out.append('-');
			}
		}
		while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
			out.deleteCharAt(out.length() - 1);
		}
		if (out.length() == 0) {
			return "session-memory";
		}
		int max = properties.getSlugMaxLength() > 0 ? properties.getSlugMaxLength() : 64;
		if (out.length() > max) {
			return out.substring(0, max);
		}
		return out.toString();
	}
}
