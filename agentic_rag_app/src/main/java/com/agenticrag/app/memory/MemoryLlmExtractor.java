package com.agenticrag.app.memory;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MemoryLlmExtractor {
	private static final Pattern CODE_FENCE_START = Pattern.compile("^```[a-zA-Z]*\\s*");
	private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
	private static final Pattern TAG_BLOCK = Pattern.compile("(?is)<[^>]+>");
	private static final Pattern SLUG_INVALID = Pattern.compile("[^a-z0-9-]");
	private static final Pattern DASHES = Pattern.compile("-{2,}");

	private final MemoryProperties properties;
	private final OpenAIClient openAiClient;
	private final OpenAIClient minimaxClient;
	private final OpenAiClientProperties openAiClientProperties;
	private final MinimaxClientProperties minimaxClientProperties;
	private final ObjectMapper objectMapper;
	private final MemoryFactKeyFactory factKeyFactory;

	public MemoryLlmExtractor(
		MemoryProperties properties,
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiClientProperties,
		MinimaxClientProperties minimaxClientProperties,
		ObjectMapper objectMapper,
		MemoryFactKeyFactory factKeyFactory
	) {
		this.properties = properties;
		this.openAiClient = openAiClient;
		this.minimaxClient = minimaxClient;
		this.openAiClientProperties = openAiClientProperties;
		this.minimaxClientProperties = minimaxClientProperties;
		this.objectMapper = objectMapper;
		this.factKeyFactory = factKeyFactory;
	}

	public List<MemoryFactRecord> extractDurableFacts(String userId, String sessionId, String reason, List<String> messageLines) {
		String transcript = buildTranscript(messageLines, properties.getFlushInputMaxChars());
		if (transcript.isEmpty()) {
			return new ArrayList<>();
		}
		String systemPrompt = "You are a durable fact extractor."
			+ " Keep only durable facts that should survive across turns."
			+ " Allowed buckets: user.preference, user.constraint, project.policy, project.decision, project.constraint, project.reminder."
			+ " Output JSON only with shape {\"facts\":[{\"bucket\":\"...\",\"subject\":\"...\",\"attribute\":\"...\",\"value\":\"...\",\"statement\":\"...\"}]}."
			+ " Keep at most "
			+ Math.max(1, properties.getMaxFactsPerFlush())
			+ " facts."
			+ " Ignore short-lived chatter, duplicates, tool traces, and temporary reasoning."
			+ " If there is no durable fact, output exactly: {\"facts\":[]}.";
		String userPrompt = "user_id=" + safe(userId) + "\n"
			+ "session_id=" + safe(sessionId) + "\n"
			+ "reason=" + safe(reason) + "\n"
			+ "transcript:\n" + transcript;
		return parseFactList(complete(systemPrompt, userPrompt, properties.getFlushMaxCompletionTokens()));
	}

	public MemoryFactCompareResult compareFact(
		String userId,
		String sessionId,
		MemoryFactRecord incoming,
		List<MemoryFactRecord> candidates
	) {
		if (incoming == null) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.NONE, -1);
		}
		if (candidates == null || candidates.isEmpty()) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.ADD, -1);
		}
		String systemPrompt = "You compare durable facts."
			+ " Decide whether the incoming fact should ADD a new durable fact, UPDATE one candidate, or do NONE."
			+ " Output JSON only with shape {\"decision\":\"ADD|UPDATE|NONE\",\"match_index\":number|null}."
			+ " UPDATE means same durable fact with changed or refined value."
			+ " NONE means the incoming fact adds no new information."
			+ " If decision is UPDATE, match_index must be a 1-based index of the matching candidate."
			+ " If decision is ADD or NONE, match_index must be null.";
		String userPrompt = buildComparePrompt(userId, sessionId, incoming, candidates);
		return parseCompareResult(complete(systemPrompt, userPrompt, 256), candidates.size());
	}

	public String generateSessionSummary(String userId, String sessionId, String reason, List<String> messageLines) {
		String transcript = buildTranscript(messageLines, properties.getFlushInputMaxChars());
		if (transcript.isEmpty()) {
			return "";
		}
		String systemPrompt = "You write a compact session summary for cross-session continuity."
			+ " Keep only decisions, constraints, pending work, reminders, and user preferences that matter later."
			+ " Output only concise markdown bullets starting with '- '."
			+ " If there is nothing worth saving, output exactly: NO_SESSION_SUMMARY.";
		String userPrompt = "user_id=" + safe(userId) + "\n"
			+ "session_id=" + safe(sessionId) + "\n"
			+ "reason=" + safe(reason) + "\n"
			+ "projection:\n" + transcript;
		return normalizeMarkdown(complete(systemPrompt, userPrompt, properties.getFlushMaxCompletionTokens()), "NO_SESSION_SUMMARY");
	}

	public String generateSessionSlug(String userId, String sessionId, List<String> messageLines) {
		String transcript = buildTranscript(messageLines, properties.getFlushInputMaxChars());
		if (transcript.isEmpty()) {
			return "session-memory";
		}
		String systemPrompt = "Generate one short kebab-case slug for this conversation summary."
			+ " Output slug only.";
		String userPrompt = "user_id=" + safe(userId) + "\n"
			+ "session_id=" + safe(sessionId) + "\n"
			+ "transcript:\n" + transcript;
		String raw = complete(systemPrompt, userPrompt, 64);
		String normalized = normalizeSlug(raw, properties.getSlugMaxLength());
		return normalized.isEmpty() ? "session-memory" : normalized;
	}

	private String buildComparePrompt(
		String userId,
		String sessionId,
		MemoryFactRecord incoming,
		List<MemoryFactRecord> candidates
	) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("user_id", safe(userId));
		payload.put("session_id", safe(sessionId));
		payload.put("incoming", toFactMap(incoming));
		List<Map<String, Object>> candidateMaps = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			Map<String, Object> map = new LinkedHashMap<>(toFactMap(candidates.get(i)));
			map.put("match_index", i + 1);
			candidateMaps.add(map);
		}
		payload.put("candidates", candidateMaps);
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (Exception e) {
			return "";
		}
	}

	private Map<String, Object> toFactMap(MemoryFactRecord fact) {
		Map<String, Object> map = new LinkedHashMap<>();
		if (fact == null) {
			return map;
		}
		map.put("bucket", fact.getBucket() != null ? fact.getBucket().getValue() : "");
		map.put("subject", safe(fact.getSubject()));
		map.put("attribute", safe(fact.getAttribute()));
		map.put("value", safe(fact.getValue()));
		map.put("statement", safe(fact.getStatement()));
		return map;
	}

	private List<MemoryFactRecord> parseFactList(String raw) {
		List<MemoryFactRecord> out = new ArrayList<>();
		Map<String, Object> payload = parseJsonObject(raw);
		Object factsValue = payload.get("facts");
		if (!(factsValue instanceof List<?>)) {
			return out;
		}
		int maxFacts = properties.getMaxFactsPerFlush() > 0 ? properties.getMaxFactsPerFlush() : 3;
		for (Object item : (List<?>) factsValue) {
			if (!(item instanceof Map<?, ?>) || out.size() >= maxFacts) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> map = (Map<String, Object>) item;
			MemoryFactBucket bucket = MemoryFactBucket.fromValue(stringValue(map.get("bucket")));
			String subject = safe(map.get("subject") != null ? String.valueOf(map.get("subject")) : "");
			String attribute = safe(map.get("attribute") != null ? String.valueOf(map.get("attribute")) : "");
			String value = safe(map.get("value") != null ? String.valueOf(map.get("value")) : "");
			String statement = safe(map.get("statement") != null ? String.valueOf(map.get("statement")) : "");
			if (subject.isEmpty() || attribute.isEmpty()) {
				continue;
			}
			if (statement.isEmpty()) {
				statement = value.isEmpty() ? subject + " / " + attribute : subject + " " + attribute + " = " + value;
			}
			out.add(new MemoryFactRecord(
				bucket,
				subject,
				attribute,
				value,
				statement,
				factKeyFactory.build(bucket, subject, attribute)
			));
		}
		return out;
	}

	private MemoryFactCompareResult parseCompareResult(String raw, int candidateCount) {
		Map<String, Object> payload = parseJsonObject(raw);
		String decisionText = stringValue(payload.get("decision")).toUpperCase(Locale.ROOT);
		MemoryFactCompareResult.Decision decision;
		try {
			decision = MemoryFactCompareResult.Decision.valueOf(decisionText);
		} catch (Exception e) {
			decision = MemoryFactCompareResult.Decision.ADD;
		}
		Integer matchIndex = intValue(payload.get("match_index"));
		if (matchIndex == null || matchIndex.intValue() < 1 || matchIndex.intValue() > candidateCount) {
			matchIndex = null;
		}
		if (decision != MemoryFactCompareResult.Decision.UPDATE) {
			return new MemoryFactCompareResult(decision, -1);
		}
		if (matchIndex == null) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.ADD, -1);
		}
		return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, matchIndex.intValue() - 1);
	}

	private Map<String, Object> parseJsonObject(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return new LinkedHashMap<>();
		}
		String sanitized = stripModelDecorations(raw);
		String json = extractJsonObject(sanitized);
		if (json.isEmpty()) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
		} catch (Exception e) {
			return new LinkedHashMap<>();
		}
	}

	private String complete(String systemPrompt, String userPrompt, int maxCompletionTokens) {
		try {
			OpenAIClient client = resolveClient();
			String model = resolveModel();
			if (client == null || model == null || model.trim().isEmpty()) {
				return "";
			}
			ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
				.model(model)
				.addSystemMessage(systemPrompt)
				.addUserMessage(userPrompt);
			if (maxCompletionTokens > 0) {
				builder.maxCompletionTokens((long) maxCompletionTokens);
			}
			ChatCompletion completion = client.chat().completions().create(builder.build());
			if (completion == null || completion.choices() == null || completion.choices().isEmpty()) {
				return "";
			}
			if (completion.choices().get(0) == null || completion.choices().get(0).message() == null) {
				return "";
			}
			return completion.choices().get(0).message().content().orElse("").trim();
		} catch (Exception ignored) {
			return "";
		}
	}

	private OpenAIClient resolveClient() {
		String provider = properties.getFlushProvider();
		if ("openai".equalsIgnoreCase(provider)) {
			return openAiClient;
		}
		return minimaxClient;
	}

	private String resolveModel() {
		String configured = properties.getFlushModel();
		if (configured != null && !configured.trim().isEmpty()) {
			return configured.trim();
		}
		String provider = properties.getFlushProvider();
		if ("openai".equalsIgnoreCase(provider)) {
			return openAiClientProperties.getModel();
		}
		return minimaxClientProperties.getModel();
	}

	private String buildTranscript(List<String> messageLines, int maxChars) {
		if (messageLines == null || messageLines.isEmpty()) {
			return "";
		}
		List<String> cleaned = new ArrayList<>();
		for (String line : messageLines) {
			if (line == null) {
				continue;
			}
			String text = line.trim();
			if (!text.isEmpty()) {
				cleaned.add(text);
			}
		}
		if (cleaned.isEmpty()) {
			return "";
		}
		String joined = String.join("\n", cleaned);
		if (maxChars > 0 && joined.length() > maxChars) {
			return joined.substring(joined.length() - maxChars);
		}
		return joined;
	}

	private String normalizeMarkdown(String text, String emptyMarker) {
		if (text == null) {
			return "";
		}
		String out = stripModelDecorations(text);
		if (out.isEmpty()) {
			return "";
		}
		if (emptyMarker != null && !emptyMarker.trim().isEmpty() && out.toUpperCase(Locale.ROOT).contains(emptyMarker.toUpperCase(Locale.ROOT))) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (String line : out.split("\\r?\\n")) {
			String trimmed = line == null ? "" : line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (trimmed.startsWith("- ")) {
				lines.add(trimmed);
			}
		}
		return String.join("\n", lines).trim();
	}

	private String stripModelDecorations(String text) {
		String out = text == null ? "" : text.trim();
		if (out.startsWith("```")) {
			out = CODE_FENCE_START.matcher(out).replaceFirst("");
			if (out.endsWith("```")) {
				out = out.substring(0, out.length() - 3).trim();
			}
		}
		out = THINK_BLOCK.matcher(out).replaceAll("");
		out = TAG_BLOCK.matcher(out).replaceAll("");
		return out.trim();
	}

	private String extractJsonObject(String raw) {
		int start = raw.indexOf('{');
		if (start < 0) {
			return "";
		}
		int depth = 0;
		boolean inString = false;
		for (int i = start; i < raw.length(); i++) {
			char ch = raw.charAt(i);
			if (ch == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) {
				inString = !inString;
			}
			if (inString) {
				continue;
			}
			if (ch == '{') {
				depth++;
			} else if (ch == '}') {
				depth--;
				if (depth == 0) {
					return raw.substring(start, i + 1);
				}
			}
		}
		return "";
	}

	private String normalizeSlug(String raw, int maxLen) {
		if (raw == null) {
			return "";
		}
		String line = raw.trim();
		int newline = line.indexOf('\n');
		if (newline >= 0) {
			line = line.substring(0, newline).trim();
		}
		line = line.toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
		line = SLUG_INVALID.matcher(line).replaceAll("-");
		line = DASHES.matcher(line).replaceAll("-");
		line = trimDashes(line);
		int limit = maxLen > 0 ? maxLen : 64;
		if (line.length() > limit) {
			line = line.substring(0, limit);
			line = trimDashes(line);
		}
		return line;
	}

	private String trimDashes(String text) {
		String out = text;
		while (out.startsWith("-")) {
			out = out.substring(1);
		}
		while (out.endsWith("-")) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private String stringValue(Object value) {
		return value != null ? String.valueOf(value) : "";
	}

	private Integer intValue(Object value) {
		if (value instanceof Number) {
			return ((Number) value).intValue();
		}
		if (value == null) {
			return null;
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		} catch (Exception e) {
			return null;
		}
	}
}
