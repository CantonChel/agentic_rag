package com.agenticrag.app.chat.context;

import com.agenticrag.app.agent.AgentTurnContextProperties;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TurnContextAutoCompactor {
	private static final String SUMMARY_HEADER = "[Context Compact Summary]";
	private static final int SUMMARY_LINE_LIMIT = 20;
	private static final int SUMMARY_LINE_MAX_CHARS = 240;

	private final AgentTurnContextProperties properties;
	private final TokenCounter tokenCounter;

	public TurnContextAutoCompactor() {
		this(new AgentTurnContextProperties(), null);
	}

	@Autowired
	public TurnContextAutoCompactor(AgentTurnContextProperties properties, TokenCounter tokenCounter) {
		this.properties = properties != null ? properties : new AgentTurnContextProperties();
		this.tokenCounter = tokenCounter;
	}

	public TurnContextAutoCompactResult compactForNextModelCall(TurnExecutionContext turnExecutionContext) {
		if (turnExecutionContext == null) {
			return new TurnContextAutoCompactResult(Collections.emptyList(), false, false, 0, 0, 0);
		}

		List<ChatMessage> historicalMessages = filterModelMessages(turnExecutionContext.getHistoricalMessages());
		List<ChatMessage> turnMessages = filterModelMessages(turnExecutionContext.getTurnMessages());
		List<ChatMessage> rawMessages = new ArrayList<>(historicalMessages.size() + turnMessages.size());
		rawMessages.addAll(historicalMessages);
		rawMessages.addAll(turnMessages);

		int rawTokenCount = countModelContextTokens(turnExecutionContext.getSystemPrompt(), rawMessages);
		int triggerTokenLimit = resolveTriggerTokenLimit();
		if (!isEnabled()) {
			return new TurnContextAutoCompactResult(rawMessages, false, false, rawTokenCount, rawTokenCount, triggerTokenLimit);
		}
		if (rawTokenCount <= triggerTokenLimit) {
			return new TurnContextAutoCompactResult(rawMessages, false, false, rawTokenCount, rawTokenCount, triggerTokenLimit);
		}

		ChatMessage firstUser = extractFirstTurnUser(turnMessages);
		int minimalTokenCount = countMinimalContextTokens(turnExecutionContext.getSystemPrompt(), historicalMessages, firstUser);
		int contextWindowTokens = resolveContextWindowTokens();
		if (contextWindowTokens > 0 && minimalTokenCount > contextWindowTokens) {
			throw new TurnContextWindowExceededException(minimalTokenCount, contextWindowTokens);
		}

		int suffixStart = resolveRecentSuffixStart(turnMessages);
		int firstUserIndex = indexOf(turnMessages, firstUser);
		if (firstUserIndex >= 0 && suffixStart <= firstUserIndex) {
			suffixStart = firstUserIndex + 1;
		}

		List<ChatMessage> omittedPrefix = extractOmittedPrefix(turnMessages, firstUserIndex, suffixStart);
		List<ChatMessage> compactedMessages = new ArrayList<>(historicalMessages.size() + turnMessages.size() + 1);
		compactedMessages.addAll(historicalMessages);
		if (firstUser != null) {
			compactedMessages.add(firstUser);
		}
		AssistantMessage summaryMessage = buildSummaryMessage(omittedPrefix);
		if (summaryMessage != null) {
			compactedMessages.add(summaryMessage);
		}
		for (int i = Math.max(0, suffixStart); i < turnMessages.size(); i++) {
			compactedMessages.add(turnMessages.get(i));
		}

		int modelTokenCount = countModelContextTokens(turnExecutionContext.getSystemPrompt(), compactedMessages);
		boolean compacted = !sameSequence(rawMessages, compactedMessages);
		boolean forcedFinalResponse = modelTokenCount > triggerTokenLimit;
		return new TurnContextAutoCompactResult(
			compactedMessages,
			compacted,
			forcedFinalResponse,
			rawTokenCount,
			modelTokenCount,
			triggerTokenLimit
		);
	}

	private boolean isEnabled() {
		return resolveContextWindowTokens() > 0;
	}

	private int resolveContextWindowTokens() {
		return properties != null ? Math.max(0, properties.getContextWindowTokens()) : 0;
	}

	private int resolveTriggerTokenLimit() {
		int contextWindowTokens = resolveContextWindowTokens();
		if (contextWindowTokens <= 0) {
			return 0;
		}
		int reserveTokens = properties != null ? Math.max(0, properties.getReserveTokens()) : 0;
		return Math.max(1, contextWindowTokens - reserveTokens);
	}

	private int resolveKeepRecentTokens() {
		return properties != null ? Math.max(0, properties.getKeepRecentTokens()) : 0;
	}

	private List<ChatMessage> filterModelMessages(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return Collections.emptyList();
		}
		List<ChatMessage> out = new ArrayList<>(messages.size());
		for (ChatMessage message : messages) {
			if (message == null || message.getType() == null || message.getType() == ChatMessageType.THINKING) {
				continue;
			}
			out.add(message);
		}
		return out;
	}

	private ChatMessage extractFirstTurnUser(List<ChatMessage> turnMessages) {
		for (ChatMessage message : turnMessages) {
			if (message != null && message.getType() == ChatMessageType.USER) {
				return message;
			}
		}
		return null;
	}

	private int indexOf(List<ChatMessage> messages, ChatMessage target) {
		if (messages == null || target == null) {
			return -1;
		}
		for (int i = 0; i < messages.size(); i++) {
			if (messages.get(i) == target) {
				return i;
			}
		}
		return -1;
	}

	private int countMinimalContextTokens(String systemPrompt, List<ChatMessage> historicalMessages, ChatMessage firstUser) {
		List<ChatMessage> minimalMessages = new ArrayList<>(historicalMessages.size() + (firstUser != null ? 1 : 0));
		minimalMessages.addAll(historicalMessages);
		if (firstUser != null) {
			minimalMessages.add(firstUser);
		}
		return countModelContextTokens(systemPrompt, minimalMessages);
	}

	private int resolveRecentSuffixStart(List<ChatMessage> turnMessages) {
		if (turnMessages == null || turnMessages.isEmpty()) {
			return 0;
		}
		int keepRecentTokens = resolveKeepRecentTokens();
		int suffixStart = turnMessages.size();
		int suffixTokens = 0;
		for (int i = turnMessages.size() - 1; i >= 0; i--) {
			ChatMessage message = turnMessages.get(i);
			int messageTokens = countMessageTokens(message);
			if (suffixStart < turnMessages.size() && keepRecentTokens > 0 && suffixTokens + messageTokens > keepRecentTokens) {
				break;
			}
			if (suffixStart < turnMessages.size() && keepRecentTokens <= 0) {
				break;
			}
			suffixStart = i;
			suffixTokens += messageTokens;
		}
		if (suffixStart >= turnMessages.size()) {
			suffixStart = Math.max(0, turnMessages.size() - 1);
		}
		return adjustToWholeToolExchange(turnMessages, suffixStart);
	}

	private int adjustToWholeToolExchange(List<ChatMessage> turnMessages, int suffixStart) {
		if (turnMessages == null || turnMessages.isEmpty()) {
			return 0;
		}
		int adjusted = Math.max(0, Math.min(suffixStart, turnMessages.size() - 1));
		if (turnMessages.get(adjusted).getType() != ChatMessageType.TOOL_RESULT) {
			return adjusted;
		}
		while (adjusted > 0 && turnMessages.get(adjusted - 1).getType() == ChatMessageType.TOOL_RESULT) {
			adjusted--;
		}
		while (adjusted > 0) {
			ChatMessage previous = turnMessages.get(adjusted - 1);
			if (previous.getType() == ChatMessageType.TOOL_CALL) {
				return adjusted - 1;
			}
			if (previous.getType() != ChatMessageType.TOOL_RESULT) {
				return adjusted;
			}
			adjusted--;
		}
		return adjusted;
	}

	private List<ChatMessage> extractOmittedPrefix(List<ChatMessage> turnMessages, int firstUserIndex, int suffixStart) {
		if (turnMessages == null || turnMessages.isEmpty()) {
			return Collections.emptyList();
		}
		int start = Math.max(0, firstUserIndex + 1);
		int end = Math.max(start, Math.min(suffixStart, turnMessages.size()));
		if (start >= end) {
			return Collections.emptyList();
		}
		return new ArrayList<>(turnMessages.subList(start, end));
	}

	private AssistantMessage buildSummaryMessage(List<ChatMessage> omittedPrefix) {
		if (omittedPrefix == null || omittedPrefix.isEmpty()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		lines.add(SUMMARY_HEADER);
		boolean overflowed = false;
		for (ChatMessage message : omittedPrefix) {
			List<String> renderedLines = renderSummaryLines(message);
			for (String renderedLine : renderedLines) {
				if (lines.size() >= SUMMARY_LINE_LIMIT) {
					overflowed = true;
					break;
				}
				lines.add(truncate(renderedLine, SUMMARY_LINE_MAX_CHARS));
			}
			if (overflowed) {
				break;
			}
		}
		if (overflowed) {
			if (lines.size() >= SUMMARY_LINE_LIMIT) {
				lines.set(SUMMARY_LINE_LIMIT - 1, "...");
			} else {
				lines.add("...");
			}
		}
		if (lines.size() == 1) {
			return null;
		}
		return new AssistantMessage(String.join("\n", lines));
	}

	private List<String> renderSummaryLines(ChatMessage message) {
		if (message == null || message.getType() == null) {
			return Collections.emptyList();
		}
		if (message.getType() == ChatMessageType.USER) {
			return List.of("USER: " + normalizeLine(message.getContent()));
		}
		if (message.getType() == ChatMessageType.ASSISTANT) {
			return List.of("ASSISTANT: " + normalizeLine(message.getContent()));
		}
		if (message.getType() == ChatMessageType.TOOL_RESULT && message instanceof ToolResultMessage toolResultMessage) {
			String status = toolResultMessage.isSuccess() ? "success" : "error";
			String output = toolResultMessage.isSuccess() ? toolResultMessage.getOutput() : toolResultMessage.getError();
			return List.of(
				"TOOL_RESULT: "
					+ safe(toolResultMessage.getToolName())
					+ " ["
					+ status
					+ "] "
					+ normalizeLine(output)
			);
		}
		if (message.getType() == ChatMessageType.TOOL_CALL && message instanceof ToolCallMessage toolCallMessage) {
			List<String> lines = new ArrayList<>();
			List<LlmToolCall> toolCalls = toolCallMessage.getToolCalls();
			if (toolCalls == null || toolCalls.isEmpty()) {
				lines.add("TOOL_CALL: unknown");
				return lines;
			}
			for (LlmToolCall toolCall : toolCalls) {
				if (toolCall == null) {
					continue;
				}
				lines.add(
					"TOOL_CALL: "
						+ safe(toolCall.getName())
						+ " "
						+ normalizeLine(toolCall.getArguments() != null ? toolCall.getArguments().toString() : null)
				);
			}
			return lines;
		}
		return Collections.emptyList();
	}

	private int countModelContextTokens(String systemPrompt, List<ChatMessage> messages) {
		return countTextTokens(systemPrompt) + countMessagesTokens(messages);
	}

	private int countMessagesTokens(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int total = 0;
		for (ChatMessage message : messages) {
			total += countMessageTokens(message);
		}
		return total;
	}

	private int countMessageTokens(ChatMessage message) {
		if (message == null || message.getType() == null) {
			return 0;
		}
		if (message.getType() == ChatMessageType.TOOL_CALL && message instanceof ToolCallMessage toolCallMessage) {
			int total = 0;
			List<LlmToolCall> toolCalls = toolCallMessage.getToolCalls();
			if (toolCalls != null) {
				for (LlmToolCall toolCall : toolCalls) {
					if (toolCall == null) {
						continue;
					}
					total += countTextTokens(toolCall.getName());
					total += countTextTokens(toolCall.getArguments() != null ? toolCall.getArguments().toString() : null);
				}
			}
			return total;
		}
		if (message.getType() == ChatMessageType.TOOL_RESULT && message instanceof ToolResultMessage toolResultMessage) {
			String payload = safe(toolResultMessage.getToolName())
				+ " "
				+ (toolResultMessage.isSuccess() ? "success" : "error")
				+ " "
				+ safe(toolResultMessage.isSuccess() ? toolResultMessage.getOutput() : toolResultMessage.getError());
			return countTextTokens(payload);
		}
		return countTextTokens(message.getContent());
	}

	private int countTextTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return tokenCounter != null ? tokenCounter.count(text) : text.length();
	}

	private boolean sameSequence(List<ChatMessage> left, List<ChatMessage> right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			ChatMessage leftMessage = left.get(i);
			ChatMessage rightMessage = right.get(i);
			ChatMessageType leftType = leftMessage != null ? leftMessage.getType() : null;
			ChatMessageType rightType = rightMessage != null ? rightMessage.getType() : null;
			if (leftType != rightType) {
				return false;
			}
			String leftContent = leftMessage != null ? leftMessage.getContent() : null;
			String rightContent = rightMessage != null ? rightMessage.getContent() : null;
			if (leftContent == null ? rightContent != null : !leftContent.equals(rightContent)) {
				return false;
			}
		}
		return true;
	}

	private String normalizeLine(String value) {
		String normalized = safe(value).replaceAll("\\s+", " ").trim();
		return truncate(normalized, SUMMARY_LINE_MAX_CHARS);
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength) + "...";
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
