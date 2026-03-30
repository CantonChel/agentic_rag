package com.agenticrag.app.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MemoryBlockParser {
	private static final Pattern START_PATTERN = Pattern.compile("^<!--\\s*MEMORY_BLOCK\\s+(\\{.*\\})\\s*-->\\s*$");
	private static final String END_MARKER = "<!-- /MEMORY_BLOCK -->";

	private final MemoryFileService memoryFileService;
	private final ObjectMapper objectMapper;

	public MemoryBlockParser(MemoryFileService memoryFileService, ObjectMapper objectMapper) {
		this.memoryFileService = memoryFileService;
		this.objectMapper = objectMapper;
	}

	public List<ParsedMemoryBlock> parse(String userId, Path path) {
		if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
			return Collections.emptyList();
		}
		try {
			String content = Files.readString(path, StandardCharsets.UTF_8);
			return parseContent(userId, path, content);
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	public List<ParsedMemoryBlock> parseContent(String userId, Path path, String content) {
		if (content == null) {
			content = "";
		}
		List<String> lines = Arrays.asList(content.split("\\r?\\n", -1));
		List<ParsedMemoryBlock> blocks = new ArrayList<>();
		int index = 0;
		while (index < lines.size()) {
			Matcher matcher = START_PATTERN.matcher(lines.get(index));
			if (!matcher.matches()) {
				index++;
				continue;
			}
			int startIdx = index;
			int endIdx = -1;
			for (int i = index + 1; i < lines.size(); i++) {
				if (END_MARKER.equals(lines.get(i).trim())) {
					endIdx = i;
					break;
				}
			}
			if (endIdx < 0) {
				break;
			}
			MemoryBlockMetadata metadata = parseMetadata(matcher.group(1));
			String body = joinLines(lines, startIdx + 1, endIdx);
			blocks.add(new ParsedMemoryBlock(
				path,
				memoryFileService.relPath(path),
				resolveKind(userId, path, metadata),
				metadata,
				body,
				startIdx + 2,
				endIdx,
				false
			));
			index = endIdx + 1;
		}
		if (!blocks.isEmpty()) {
			return blocks;
		}
		String legacyText = content.trim();
		if (legacyText.isEmpty()) {
			return Collections.emptyList();
		}
		String kind = memoryFileService.kindOf(userId, path);
		MemoryBlockMetadata metadata = new MemoryBlockMetadata(
			MemoryBlockMetadata.SCHEMA_V1,
			"session_archive".equals(kind) ? MemoryBlockMetadata.KIND_SESSION_ARCHIVE : MemoryBlockMetadata.KIND_DURABLE,
			"legacy-" + shortHash(content),
			userId,
			null,
			null,
			"legacy",
			"legacy:" + shortHash(content),
			null,
			null
		);
		return Collections.singletonList(new ParsedMemoryBlock(
			path,
			memoryFileService.relPath(path),
			kind,
			metadata,
			legacyText,
			1,
			lines.isEmpty() ? 1 : lines.size(),
			true
		));
	}

	public String renderBlock(MemoryBlockMetadata metadata, String body) {
		String normalizedBody = body == null ? "" : body.trim();
		if (metadata == null || normalizedBody.isEmpty()) {
			return "";
		}
		try {
			return "<!-- MEMORY_BLOCK "
				+ objectMapper.writeValueAsString(metadata.toMap())
				+ " -->\n"
				+ normalizedBody
				+ "\n<!-- /MEMORY_BLOCK -->\n\n";
		} catch (Exception e) {
			return "";
		}
	}

	private MemoryBlockMetadata parseMetadata(String json) {
		try {
			Map<String, Object> values = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
			return MemoryBlockMetadata.fromMap(values);
		} catch (Exception e) {
			return null;
		}
	}

	private String resolveKind(String userId, Path path, MemoryBlockMetadata metadata) {
		if (metadata != null) {
			if (MemoryBlockMetadata.KIND_SESSION_ARCHIVE.equals(metadata.getKind())) {
				return "session_archive";
			}
			if (MemoryBlockMetadata.KIND_DURABLE.equals(metadata.getKind())) {
				return "daily_durable";
			}
		}
		return memoryFileService.kindOf(userId, path);
	}

	private String joinLines(List<String> lines, int startInclusive, int endExclusive) {
		StringBuilder sb = new StringBuilder();
		for (int i = startInclusive; i < endExclusive && i < lines.size(); i++) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(lines.get(i));
		}
		return sb.toString().trim();
	}

	private String shortHash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(bytes.length, 8); i++) {
				sb.append(String.format("%02x", bytes[i]));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(value != null ? value.hashCode() : 0);
		}
	}
}
