package com.agenticrag.app.llm;

public enum LlmToolChoiceMode {
	AUTO,
	REQUIRED,
	NONE;

	public static LlmToolChoiceMode from(String raw) {
		if (raw == null) {
			return AUTO;
		}
		String v = raw.trim().toUpperCase();
		if ("REQUIRED".equals(v)) {
			return REQUIRED;
		}
		if ("NONE".equals(v)) {
			return NONE;
		}
		return AUTO;
	}
}

