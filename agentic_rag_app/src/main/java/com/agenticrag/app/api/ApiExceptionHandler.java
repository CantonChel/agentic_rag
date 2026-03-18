package com.agenticrag.app.api;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@RestControllerAdvice
public class ApiExceptionHandler {
	@ExceptionHandler(WebClientResponseException.class)
	public ResponseEntity<Map<String, Object>> handleWebClientResponse(WebClientResponseException e) {
		Map<String, Object> body = new HashMap<>();
		body.put("error", "upstream_request_failed");
		body.put("status", e.getRawStatusCode());
		body.put("message", truncate(e.getResponseBodyAsString(), 800));
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
		Map<String, Object> body = new HashMap<>();
		body.put("error", "internal_error");
		body.put("type", e.getClass().getSimpleName());
		body.put("message", truncate(e.getMessage(), 800));
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
	}

	private String truncate(String s, int maxLen) {
		if (s == null) {
			return "";
		}
		if (maxLen <= 0) {
			return "";
		}
		if (s.length() <= maxLen) {
			return s;
		}
		return s.substring(0, maxLen);
	}
}

