package com.agenticrag.app.memory;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.openai.client.OpenAIClient;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
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

	public MemoryLlmExtractor(
		MemoryProperties properties,
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiClientProperties,
		MinimaxClientProperties minimaxClientProperties
	) {
		this.properties = properties;
		this.openAiClient = openAiClient;
		this.minimaxClient = minimaxClient;
		this.openAiClientProperties = openAiClientProperties;
		this.minimaxClientProperties = minimaxClientProperties;
	}

	public String extractDurableMarkdown(String userId, String sessionId, String reason, List<String> messageLines) {
		String transcript = buildTranscript(messageLines, properties.getFlushInputMaxChars());
		if (transcript.isEmpty()) {
			return "";
		}
		String systemPrompt = "You are a durable-memory extractor."
			+ " Keep only long-term useful facts, preferences, decisions, deadlines, and constraints."
			+ " Ignore short-lived chatter and duplicate items."
			+ " Output only concise markdown bullets starting with '- '."
			+ " Never output <think> tags, analysis text, or direct replies to user."
			+ " If there is no durable memory, output exactly: NO_DURABLE_MEMORY.";
		String userPrompt = "user_id=" + safe(userId) + "\n"
			+ "session_id=" + safe(sessionId) + "\n"
			+ "reason=" + safe(reason) + "\n"
			+ "transcript:\n" + transcript;
		String raw = complete(systemPrompt, userPrompt, properties.getFlushMaxCompletionTokens());
		return normalizeMarkdown(raw);
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

	private String normalizeMarkdown(String text) {
		if (text == null) {
			return "";
		}
		String out = text.trim();
		if (out.isEmpty()) {
			return "";
		}
		if (out.startsWith("```")) {
			out = CODE_FENCE_START.matcher(out).replaceFirst("");
			if (out.endsWith("```")) {
				out = out.substring(0, out.length() - 3).trim();
			}
		}
		out = THINK_BLOCK.matcher(out).replaceAll("");
		out = TAG_BLOCK.matcher(out).replaceAll("");
		if (out.toUpperCase(Locale.ROOT).contains("NO_DURABLE_MEMORY")) {
			return "";
		}
		String bullets = toBulletOnly(out);
		return bullets;
	}

	private String toBulletOnly(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (String line : raw.split("\\r?\\n")) {
			if (line == null) {
				continue;
			}
			String t = line.trim();
			if (t.isEmpty()) {
				continue;
			}
			if (t.startsWith("- ")) {
				lines.add(t);
				continue;
			}
			if (t.startsWith("* ")) {
				lines.add("- " + t.substring(2).trim());
				continue;
			}
			if (t.matches("^\\d+[\\.、]\\s+.*")) {
				String norm = t.replaceFirst("^\\d+[\\.、]\\s+", "").trim();
				if (!norm.isEmpty()) {
					lines.add("- " + norm);
				}
			}
		}
		return lines.stream().distinct().collect(Collectors.joining("\n")).trim();
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
}
