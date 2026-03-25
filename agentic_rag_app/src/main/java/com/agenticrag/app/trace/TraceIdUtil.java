package com.agenticrag.app.trace;

import java.util.UUID;

public final class TraceIdUtil {
	public static final String HEADER_NAME = "X-Trace-Id";

	private TraceIdUtil() {
	}

	public static String normalizeOrGenerate(String incoming) {
		if (incoming != null) {
			String trimmed = incoming.trim();
			if (!trimmed.isEmpty()) {
				return trimmed;
			}
		}
		return UUID.randomUUID().toString().replace("-", "");
	}
}
