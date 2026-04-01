package com.agenticrag.app.memory.index;

public enum MemoryIndexScopeType {
	GLOBAL("global"),
	USER("user");

	private final String value;

	MemoryIndexScopeType(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

	public static MemoryIndexScopeType fromValue(String value) {
		if (value == null) {
			return USER;
		}
		for (MemoryIndexScopeType candidate : values()) {
			if (candidate.value.equalsIgnoreCase(value.trim())) {
				return candidate;
			}
		}
		return USER;
	}
}
