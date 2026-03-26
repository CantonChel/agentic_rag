package com.agenticrag.app.ingest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Collections;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TextSanitizer {
	private static final String NULL_CHAR = "\u0000";

	public String sanitizeText(String value) {
		if (value == null) {
			return null;
		}
		return value.indexOf(NULL_CHAR) >= 0 ? value.replace(NULL_CHAR, "") : value;
	}

	public Map<String, Object> sanitizeMap(Map<String, Object> value, ObjectMapper objectMapper) {
		if (value == null || value.isEmpty()) {
			return Collections.emptyMap();
		}
		JsonNode sanitized = sanitizeJsonNode(objectMapper.valueToTree(value));
		@SuppressWarnings("unchecked")
		Map<String, Object> map = objectMapper.convertValue(sanitized, Map.class);
		return map != null ? map : Collections.emptyMap();
	}

	public JsonNode sanitizeJsonValue(Object value, ObjectMapper objectMapper) {
		if (value == null) {
			return null;
		}
		return sanitizeJsonNode(objectMapper.valueToTree(value));
	}

	private JsonNode sanitizeJsonNode(JsonNode node) {
		if (node == null || node.isNull()) {
			return node;
		}
		if (node.isTextual()) {
			return TextNode.valueOf(sanitizeText(node.asText()));
		}
		if (node.isObject()) {
			ObjectNode out = JsonNodeFactory.instance.objectNode();
			node.fields().forEachRemaining(entry -> out.set(entry.getKey(), sanitizeJsonNode(entry.getValue())));
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = JsonNodeFactory.instance.arrayNode();
			for (JsonNode item : node) {
				out.add(sanitizeJsonNode(item));
			}
			return out;
		}
		return node;
	}
}
