package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
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
		List<String> transcriptLines = extractTranscriptLines(contextMessages, persistedMessages);
		if (transcriptLines.isEmpty()) {
			return;
		}
		String normalizedReason = normalizeReason(reason);
		String body = String.join("\n", transcriptLines).trim();
		String fingerprint = fingerprint(body);
		List<String> slugSource = new ArrayList<>(transcriptLines);
		String slug = memoryLlmExtractor != null
			? memoryLlmExtractor.generateSessionSlug(userId, sessionId, slugSource)
			: "session-memory";
		Path file = memoryFileService.sessionsDir(userId).resolve(LocalDate.now() + "-" + normalizeSlug(slug) + ".md");
		String dedupeKey = "archive:" + SessionScope.normalizeSessionId(sessionId) + ":" + normalizedReason + ":" + fingerprint;
		if (hasDedupeKey(userId, dedupeKey)) {
			return;
		}
		MemoryBlockMetadata metadata = new MemoryBlockMetadata(
			MemoryBlockMetadata.SCHEMA_V1,
			MemoryBlockMetadata.KIND_SESSION_ARCHIVE,
			UUID.randomUUID().toString(),
			SessionScope.normalizeUserId(userId),
			SessionScope.normalizeSessionId(sessionId),
			OffsetDateTime.now().toString(),
			"session_lifecycle",
			dedupeKey,
			normalizedReason,
			normalizeSlug(slug)
		);
		appendBlock(file, metadata, body);
	}

	private List<String> extractTranscriptLines(
		List<ChatMessage> contextMessages,
		List<StoredMessageEntity> persistedMessages
	) {
		List<String> lines = new ArrayList<>();
		if (persistedMessages != null) {
			for (StoredMessageEntity message : persistedMessages) {
				String line = formatStoredMessage(message);
				if (!line.isEmpty()) {
					lines.add(line);
				}
			}
		}
		if (!lines.isEmpty()) {
			return lines;
		}
		if (contextMessages != null) {
			for (ChatMessage message : contextMessages) {
				String line = formatContextMessage(message);
				if (!line.isEmpty()) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	private String formatStoredMessage(StoredMessageEntity message) {
		if (message == null || message.getType() == null || message.getContent() == null) {
			return "";
		}
		String type = message.getType().trim().toUpperCase(Locale.ROOT);
		if (!"USER".equals(type) && !"ASSISTANT".equals(type)) {
			return "";
		}
		String content = sanitize(message.getContent());
		if (content.isEmpty()) {
			return "";
		}
		return "- " + type + ": " + content;
	}

	private String formatContextMessage(ChatMessage message) {
		if (message == null || message.getType() == null || message.getContent() == null) {
			return "";
		}
		String type = message.getType().name();
		if (!"USER".equals(type) && !"ASSISTANT".equals(type)) {
			return "";
		}
		String content = sanitize(message.getContent());
		if (content.isEmpty()) {
			return "";
		}
		return "- " + type + ": " + content;
	}

	private boolean hasDedupeKey(String userId, String dedupeKey) {
		for (Path file : memoryFileService.discoverMemoryFiles(userId, false)) {
			if (!"session_archive".equals(memoryFileService.kindOf(userId, file))) {
				continue;
			}
			for (ParsedMemoryBlock block : memoryBlockParser.parse(userId, file)) {
				if (block.getMetadata() != null && dedupeKey.equals(block.getMetadata().getDedupeKey())) {
					return true;
				}
			}
		}
		return false;
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
			// avoid blocking session lifecycle on archive write failure
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

	private String fingerprint(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(bytes.length, 12); i++) {
				sb.append(String.format("%02x", bytes[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(content.hashCode());
		}
	}
}
