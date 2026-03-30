package com.agenticrag.app.memory;

import java.util.LinkedHashMap;
import java.util.Map;

public class MemoryBlockMetadata {
	public static final String SCHEMA_V1 = "memory.v1";
	public static final String KIND_DURABLE = "durable";
	public static final String KIND_SESSION_ARCHIVE = "session_archive";

	private final String schema;
	private final String kind;
	private final String blockId;
	private final String userId;
	private final String sessionId;
	private final String createdAt;
	private final String trigger;
	private final String dedupeKey;
	private final String reason;
	private final String slug;

	public MemoryBlockMetadata(
		String schema,
		String kind,
		String blockId,
		String userId,
		String sessionId,
		String createdAt,
		String trigger,
		String dedupeKey,
		String reason,
		String slug
	) {
		this.schema = schema;
		this.kind = kind;
		this.blockId = blockId;
		this.userId = userId;
		this.sessionId = sessionId;
		this.createdAt = createdAt;
		this.trigger = trigger;
		this.dedupeKey = dedupeKey;
		this.reason = reason;
		this.slug = slug;
	}

	public String getSchema() {
		return schema;
	}

	public String getKind() {
		return kind;
	}

	public String getBlockId() {
		return blockId;
	}

	public String getUserId() {
		return userId;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public String getTrigger() {
		return trigger;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	public String getReason() {
		return reason;
	}

	public String getSlug() {
		return slug;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("schema", schema);
		out.put("kind", kind);
		out.put("block_id", blockId);
		out.put("user_id", userId);
		out.put("session_id", sessionId);
		out.put("created_at", createdAt);
		out.put("trigger", trigger);
		out.put("dedupe_key", dedupeKey);
		if (reason != null && !reason.trim().isEmpty()) {
			out.put("reason", reason);
		}
		if (slug != null && !slug.trim().isEmpty()) {
			out.put("slug", slug);
		}
		return out;
	}

	public static MemoryBlockMetadata fromMap(Map<String, Object> values) {
		if (values == null) {
			return null;
		}
		return new MemoryBlockMetadata(
			stringValue(values.get("schema")),
			stringValue(values.get("kind")),
			stringValue(values.get("block_id")),
			stringValue(values.get("user_id")),
			stringValue(values.get("session_id")),
			stringValue(values.get("created_at")),
			stringValue(values.get("trigger")),
			stringValue(values.get("dedupe_key")),
			stringValue(values.get("reason")),
			stringValue(values.get("slug"))
		);
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
