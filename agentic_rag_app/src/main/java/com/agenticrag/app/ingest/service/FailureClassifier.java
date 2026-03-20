package com.agenticrag.app.ingest.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FailureClassifier {
	private static final Set<String> RETRYABLE_CODES = new HashSet<>(Arrays.asList(
		"network_timeout",
		"rate_limit",
		"service_unavailable",
		"upstream_timeout",
		"upstream_5xx"
	));

	private static final Set<String> NON_RETRYABLE_CODES = new HashSet<>(Arrays.asList(
		"unsupported_file",
		"corrupted_file",
		"schema_invalid",
		"invalid_signature"
	));

	public boolean isRetryable(String code, Throwable error) {
		String normalized = normalize(code);
		if (NON_RETRYABLE_CODES.contains(normalized)) {
			return false;
		}
		if (RETRYABLE_CODES.contains(normalized)) {
			return true;
		}
		if (error != null) {
			String msg = error.getMessage();
			if (msg != null) {
				String lower = msg.toLowerCase(Locale.ROOT);
				if (lower.contains("timeout") || lower.contains("429") || lower.contains("503") || lower.contains("connection")) {
					return true;
				}
			}
		}
		return false;
	}

	private String normalize(String code) {
		if (code == null) {
			return "";
		}
		return code.trim().toLowerCase(Locale.ROOT);
	}
}
