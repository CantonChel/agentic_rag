package com.agenticrag.app.rag.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Document {
	private final String id;
	private final String content;
	private final Map<String, Object> metadata;

	public Document(String id, String content, Map<String, Object> metadata) {
		this.id = id != null && !id.trim().isEmpty() ? id : UUID.randomUUID().toString();
		this.content = content != null ? content : "";
		if (metadata == null) {
			this.metadata = Collections.emptyMap();
		} else {
			this.metadata = Collections.unmodifiableMap(new HashMap<>(metadata));
		}
	}

	public String getId() {
		return id;
	}

	public String getContent() {
		return content;
	}

	public Map<String, Object> getMetadata() {
		return metadata;
	}
}

