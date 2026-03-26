package com.agenticrag.app.session;

import java.time.Instant;

public class ChatSession {
	private final String id;
	private final Instant createdAt;

	public ChatSession(String id) {
		this(id, Instant.now());
	}

	public ChatSession(String id, Instant createdAt) {
		this.id = id;
		this.createdAt = createdAt != null ? createdAt : Instant.now();
	}

	public String getId() {
		return id;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
