package com.agenticrag.app.memory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MemoryFactMarkdownCodec {
	public String render(MemoryFactRecord record) {
		if (record == null) {
			return "";
		}
		String statement = safe(record.getStatement());
		String subject = safe(record.getSubject());
		String attribute = safe(record.getAttribute());
		String value = safe(record.getValue());
		if (statement.isEmpty() && subject.isEmpty() && attribute.isEmpty() && value.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		appendLine(out, "statement", statement);
		appendLine(out, "subject", subject);
		appendLine(out, "attribute", attribute);
		appendLine(out, "value", value);
		return out.toString().trim();
	}

	public MemoryFactRecord parse(ParsedMemoryBlock block) {
		if (block == null) {
			return null;
		}
		Map<String, String> fields = new LinkedHashMap<>();
		String content = block.getContent() != null ? block.getContent().trim() : "";
		if (!content.isEmpty()) {
			for (String line : content.split("\\r?\\n")) {
				String trimmed = line == null ? "" : line.trim();
				if (!trimmed.startsWith("- ")) {
					continue;
				}
				String payload = trimmed.substring(2).trim();
				int colon = payload.indexOf(':');
				if (colon <= 0) {
					continue;
				}
				String key = payload.substring(0, colon).trim().toLowerCase(Locale.ROOT);
				String value = payload.substring(colon + 1).trim();
				if (!key.isEmpty() && !value.isEmpty()) {
					fields.put(key, value);
				}
			}
		}
		MemoryBlockMetadata metadata = block.getMetadata();
		return new MemoryFactRecord(
			MemoryFactBucket.fromValue(metadata != null ? metadata.getBucket() : null),
			fields.getOrDefault("subject", ""),
			fields.getOrDefault("attribute", ""),
			fields.getOrDefault("value", ""),
			fields.getOrDefault("statement", content),
			metadata != null ? metadata.getFactKey() : null
		);
	}

	private void appendLine(StringBuilder out, String key, String value) {
		if (value == null || value.trim().isEmpty()) {
			return;
		}
		if (out.length() > 0) {
			out.append('\n');
		}
		out.append("- ").append(key).append(": ").append(value.trim());
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}
}
