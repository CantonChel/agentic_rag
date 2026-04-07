package com.agenticrag.app.memory;

import java.util.Locale;

public enum MemoryFactBucket {
	USER_PREFERENCE("user.preference"),
	USER_CONSTRAINT("user.constraint"),
	PROJECT_POLICY("project.policy"),
	PROJECT_DECISION("project.decision"),
	PROJECT_CONSTRAINT("project.constraint"),
	PROJECT_REMINDER("project.reminder");

	private final String value;

	MemoryFactBucket(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public String fileName() {
		return value + ".md";
	}

	public static MemoryFactBucket fromValue(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return PROJECT_REMINDER;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (MemoryFactBucket bucket : values()) {
			if (bucket.value.equals(normalized)) {
				return bucket;
			}
		}
		return PROJECT_REMINDER;
	}
}
