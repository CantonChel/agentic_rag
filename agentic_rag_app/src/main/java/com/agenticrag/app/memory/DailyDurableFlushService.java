package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
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
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DailyDurableFlushService {
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final MemoryProperties properties;
	private final MemoryLlmExtractor memoryLlmExtractor;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;

	public DailyDurableFlushService(
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

	public void flush(String scopedSessionId, List<ChatMessage> messages) {
		if (!properties.isEnabled() || !properties.isFlushEnabled()) {
			return;
		}
		if (messages == null || messages.isEmpty()) {
			return;
		}
		String userId = SessionScope.userIdFromScopedSessionId(scopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
		List<String> lines = extractContextLines(messages);
		if (lines.isEmpty()) {
			return;
		}
		String durableBody = memoryLlmExtractor.extractDurableMarkdown(userId, sessionId, "preflight-compact", lines);
		if (durableBody == null || durableBody.trim().isEmpty()) {
			return;
		}
		String normalizedBody = durableBody.trim();
		Path file = memoryFileService.dailyDir(userId).resolve(LocalDate.now() + ".md");
		String dedupeKey = "durable:" + normalizeForDedupe(normalizedBody);
		if (hasDedupeKey(userId, file, dedupeKey)) {
			return;
		}
		MemoryBlockMetadata metadata = new MemoryBlockMetadata(
			MemoryBlockMetadata.SCHEMA_V1,
			MemoryBlockMetadata.KIND_DURABLE,
			UUID.randomUUID().toString(),
			SessionScope.normalizeUserId(userId),
			SessionScope.normalizeSessionId(sessionId),
			OffsetDateTime.now().toString(),
			"preflight_compact",
			dedupeKey,
			null,
			null
		);
		appendBlock(file, metadata, normalizedBody);
	}

	private List<String> extractContextLines(List<ChatMessage> messages) {
		List<String> lines = new ArrayList<>();
		for (ChatMessage message : messages) {
			if (message == null || message.getType() == null || message.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			String content = sanitize(message.getContent());
			if (content.isEmpty()) {
				continue;
			}
			lines.add(message.getType().name() + ": " + content);
		}
		return lines;
	}

	private boolean hasDedupeKey(String userId, Path file, String dedupeKey) {
		for (ParsedMemoryBlock block : memoryBlockParser.parse(userId, file)) {
			if (block.getMetadata() != null && dedupeKey.equals(block.getMetadata().getDedupeKey())) {
				return true;
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
			// avoid blocking chat flow on durable memory write failure
		}
	}

	private String sanitize(String content) {
		if (content == null) {
			return "";
		}
		String normalized = WHITESPACE.matcher(content).replaceAll(" ").trim();
		int maxChars = properties.getFlushInputMaxChars() > 0 ? properties.getFlushInputMaxChars() : 6000;
		if (normalized.length() <= maxChars) {
			return normalized;
		}
		return normalized.substring(normalized.length() - maxChars);
	}

	private String normalizeForDedupe(String body) {
		return WHITESPACE.matcher(body == null ? "" : body.trim().toLowerCase()).replaceAll(" ");
	}
}
