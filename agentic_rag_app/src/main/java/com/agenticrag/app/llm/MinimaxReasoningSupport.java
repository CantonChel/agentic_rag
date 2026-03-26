package com.agenticrag.app.llm;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.Map;

public final class MinimaxReasoningSupport {
	public static final String SOURCE_REASONING_DETAILS = "reasoning_details";
	public static final String SOURCE_REASONING_FIELD = "reasoning_field";

	private MinimaxReasoningSupport() {
	}

	public static void applyReasoningSplit(
		ChatCompletionCreateParams.Builder paramsBuilder,
		LlmProvider provider,
		MinimaxClientProperties minimaxProperties
	) {
		if (
			paramsBuilder == null ||
			provider != LlmProvider.MINIMAX ||
			minimaxProperties == null ||
			!minimaxProperties.isReasoningSplit()
		) {
			return;
		}
		paramsBuilder.putAdditionalBodyProperty("reasoning_split", JsonValue.from(true));
	}

	public static ExtractedReasoning extractReasoning(ChatCompletionChunk.Choice.Delta delta) {
		if (delta == null) {
			return null;
		}
		Map<String, JsonValue> extras = delta._additionalProperties();
		if (extras == null || extras.isEmpty()) {
			return null;
		}

		String reasoningDetails = extractReasoningText(extras.get("reasoning_details"));
		if (reasoningDetails != null && !reasoningDetails.isEmpty()) {
			return new ExtractedReasoning(reasoningDetails, SOURCE_REASONING_DETAILS);
		}

		String fallback = extractReasoningText(extras.get("reasoning_content"));
		if (fallback == null || fallback.isEmpty()) {
			fallback = extractReasoningText(extras.get("thinking"));
		}
		if (fallback == null || fallback.isEmpty()) {
			fallback = extractReasoningText(extras.get("reasoning"));
		}
		if (fallback == null || fallback.isEmpty()) {
			return null;
		}
		return new ExtractedReasoning(fallback, SOURCE_REASONING_FIELD);
	}

	public static String mergeReasoning(StringBuilder reasoningBuffer, ExtractedReasoning extracted) {
		if (reasoningBuffer == null || extracted == null || extracted.text() == null || extracted.text().isEmpty()) {
			return null;
		}

		if (SOURCE_REASONING_DETAILS.equals(extracted.source())) {
			String previous = reasoningBuffer.toString();
			String snapshot = extracted.text();
			reasoningBuffer.setLength(0);
			reasoningBuffer.append(snapshot);
			if (snapshot.startsWith(previous)) {
				return snapshot.substring(previous.length());
			}
			return snapshot;
		}

		reasoningBuffer.append(extracted.text());
		return extracted.text();
	}

	private static String extractReasoningText(JsonValue value) {
		if (value == null) {
			return null;
		}
		try {
			return flattenReasoningNode(value.convert(JsonNode.class));
		} catch (Exception ignored) {
			return null;
		}
	}

	private static String flattenReasoningNode(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isTextual()) {
			return node.asText();
		}
		if (node.isArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonNode item : node) {
				String text = flattenReasoningNode(item);
				if (text != null && !text.isEmpty()) {
					sb.append(text);
				}
			}
			return sb.length() > 0 ? sb.toString() : null;
		}
		if (node.isObject()) {
			String direct = firstNonBlank(
				textValue(node, "text"),
				textValue(node, "reasoning_text"),
				textValue(node, "content"),
				textValue(node, "value")
			);
			if (direct != null) {
				return direct;
			}
			StringBuilder sb = new StringBuilder();
			node.fields().forEachRemaining(entry -> {
				String text = flattenReasoningNode(entry.getValue());
				if (text != null && !text.isEmpty()) {
					sb.append(text);
				}
			});
			return sb.length() > 0 ? sb.toString() : null;
		}
		return null;
	}

	private static String textValue(JsonNode node, String fieldName) {
		JsonNode child = node.get(fieldName);
		return child != null && child.isTextual() ? child.asText() : null;
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	public record ExtractedReasoning(String text, String source) {
	}
}
