package com.agenticrag.app.memory;

import java.util.LinkedHashMap;
import java.util.Map;

public class MemoryBlockMetadata {
	public static final String SCHEMA_V2 = "memory.v2";
	public static final String KIND_FACT = "fact";
	public static final String KIND_SESSION_SUMMARY = "session_summary";
	@Deprecated
	public static final String SCHEMA_V1 = SCHEMA_V2;
	@Deprecated
	public static final String KIND_DURABLE = KIND_FACT;
	@Deprecated
	public static final String KIND_SESSION_ARCHIVE = KIND_SESSION_SUMMARY;

	private final String schema;
	private final String kind;
	private final String blockId;
	private final String userId;
	private final String sessionId;
	private final String createdAt;
	private final String updatedAt;
	private final String trigger;
	private final String bucket;
	private final String factKey;
	private final String reason;
	private final String slug;

	public MemoryBlockMetadata(
		String schema,
		String kind,
		String blockId,
		String userId,
		String sessionId,
		String createdAt,
		String updatedAt,
		String trigger,
		String bucket,
		String factKey,
		String reason,
		String slug
	) {
		this.schema = schema;
		this.kind = kind;
		this.blockId = blockId;
		this.userId = userId;
		this.sessionId = sessionId;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
		this.trigger = trigger;
		this.bucket = bucket;
		this.factKey = factKey;
		this.reason = reason;
		this.slug = slug;
	}

	@Deprecated
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
		this(schema, kind, blockId, userId, sessionId, createdAt, null, trigger, null, dedupeKey, reason, slug);
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

	public String getUpdatedAt() {
		return updatedAt;
	}

	public String getTrigger() {
		return trigger;
	}

	public String getBucket() {
		return bucket;
	}

	public String getFactKey() {
		return factKey;
	}

	@Deprecated
	public String getDedupeKey() {
		return factKey;
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
		if (updatedAt != null && !updatedAt.trim().isEmpty()) {
			out.put("updated_at", updatedAt);
		}
		out.put("trigger", trigger);
		if (bucket != null && !bucket.trim().isEmpty()) {
			out.put("bucket", bucket);
		}
		if (factKey != null && !factKey.trim().isEmpty()) {
			out.put("fact_key", factKey);
		}
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
			stringValue(values.get("updated_at")),
			stringValue(values.get("trigger")),
			stringValue(values.get("bucket")),
			stringValue(values.get("fact_key")),
			stringValue(values.get("reason")),
			stringValue(values.get("slug"))
		);
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
