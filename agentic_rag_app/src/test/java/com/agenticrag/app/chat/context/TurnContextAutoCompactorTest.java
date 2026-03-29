package com.agenticrag.app.chat.context;

import com.agenticrag.app.agent.AgentTurnContextProperties;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.rag.splitter.TokenCounter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TurnContextAutoCompactorTest {
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final TokenCounter tokenCounter = text -> text != null ? text.length() : 0;

	@Test
	void disabledWindowReturnsRawModelViewWithoutThinking() throws Exception {
		AgentTurnContextProperties properties = new AgentTurnContextProperties();
		properties.setContextWindowTokens(0);
		TurnContextAutoCompactor compactor = new TurnContextAutoCompactor(properties, tokenCounter);

		TurnExecutionContext turnExecutionContext = new TurnExecutionContext("system", List.of(new AssistantMessage("history")));
		turnExecutionContext.addTurnMessage(new UserMessage("question"));
		turnExecutionContext.addTurnMessage(new ThinkingMessage("hidden-thought"));
		turnExecutionContext.addTurnMessage(new AssistantMessage("answer"));

		TurnContextAutoCompactResult result = compactor.compactForNextModelCall(turnExecutionContext);

		Assertions.assertFalse(result.isCompacted());
		Assertions.assertFalse(result.isForcedFinalResponse());
		Assertions.assertEquals(3, result.getMessagesForModel().size());
		Assertions.assertTrue(result.getMessagesForModel().stream().noneMatch(message -> message.getType() == ChatMessageType.THINKING));
		Assertions.assertEquals("history", result.getMessagesForModel().get(0).getContent());
		Assertions.assertEquals("question", result.getMessagesForModel().get(1).getContent());
		Assertions.assertEquals("answer", result.getMessagesForModel().get(2).getContent());
	}

	@Test
	void compactsOlderTurnPrefixIntoAssistantSummary() throws Exception {
		AgentTurnContextProperties properties = new AgentTurnContextProperties();
		properties.setContextWindowTokens(700);
		properties.setReserveTokens(50);
		properties.setKeepRecentTokens(20);
		TurnContextAutoCompactor compactor = new TurnContextAutoCompactor(properties, tokenCounter);

		TurnExecutionContext turnExecutionContext = new TurnExecutionContext("system", List.of(new AssistantMessage("history-anchor")));
		turnExecutionContext.addTurnMessage(new UserMessage("user-anchor"));
		turnExecutionContext.addTurnMessage(new AssistantMessage(repeat("A", 60)));
		turnExecutionContext.addTurnMessage(new ToolCallMessage(List.of(
			new LlmToolCall("call-1", "search_docs", objectMapper.readTree("{\"query\":\"" + repeat("b", 80) + "\"}"))
		)));
		turnExecutionContext.addTurnMessage(new ToolResultMessage("search_docs", "call-1", true, repeat("C", 520), null));
		turnExecutionContext.addTurnMessage(new AssistantMessage("recent-tail"));

		TurnContextAutoCompactResult result = compactor.compactForNextModelCall(turnExecutionContext);

		Assertions.assertTrue(result.isCompacted());
		Assertions.assertFalse(result.isForcedFinalResponse());
		Assertions.assertEquals(4, result.getMessagesForModel().size());
		Assertions.assertEquals(ChatMessageType.ASSISTANT, result.getMessagesForModel().get(0).getType());
		Assertions.assertEquals(ChatMessageType.USER, result.getMessagesForModel().get(1).getType());
		Assertions.assertEquals(ChatMessageType.ASSISTANT, result.getMessagesForModel().get(2).getType());
		Assertions.assertEquals(ChatMessageType.ASSISTANT, result.getMessagesForModel().get(3).getType());
		String summary = result.getMessagesForModel().get(2).getContent();
		Assertions.assertTrue(summary.startsWith("[Context Compact Summary]"));
		Assertions.assertTrue(summary.contains("ASSISTANT:"));
		Assertions.assertTrue(summary.contains("TOOL_CALL: search_docs"));
		Assertions.assertTrue(summary.contains("TOOL_RESULT: search_docs [success]"));
		Assertions.assertEquals("recent-tail", result.getMessagesForModel().get(3).getContent());
	}

	@Test
	void preservesWholeLatestToolExchangeWhenSuffixStartsAtToolResult() throws Exception {
		AgentTurnContextProperties properties = new AgentTurnContextProperties();
		properties.setContextWindowTokens(150);
		properties.setReserveTokens(40);
		properties.setKeepRecentTokens(10);
		TurnContextAutoCompactor compactor = new TurnContextAutoCompactor(properties, tokenCounter);

		TurnExecutionContext turnExecutionContext = new TurnExecutionContext("system", List.of());
		turnExecutionContext.addTurnMessage(new UserMessage("question"));
		turnExecutionContext.addTurnMessage(new AssistantMessage(repeat("P", 50)));
		turnExecutionContext.addTurnMessage(new ToolCallMessage(List.of(
			new LlmToolCall("call-1", "lookup", objectMapper.readTree("{\"q\":\"" + repeat("x", 20) + "\"}"))
		)));
		turnExecutionContext.addTurnMessage(new ToolResultMessage("lookup", "call-1", true, repeat("R", 12), null));
		turnExecutionContext.addTurnMessage(new ToolResultMessage("lookup", "call-1", true, repeat("S", 12), null));

		TurnContextAutoCompactResult result = compactor.compactForNextModelCall(turnExecutionContext);

		Assertions.assertTrue(result.isCompacted());
		List<ChatMessage> messagesForModel = result.getMessagesForModel();
		Assertions.assertEquals(ChatMessageType.USER, messagesForModel.get(0).getType());
		Assertions.assertEquals(ChatMessageType.ASSISTANT, messagesForModel.get(1).getType());
		Assertions.assertEquals(ChatMessageType.TOOL_CALL, messagesForModel.get(2).getType());
		Assertions.assertEquals(ChatMessageType.TOOL_RESULT, messagesForModel.get(3).getType());
		Assertions.assertEquals(ChatMessageType.TOOL_RESULT, messagesForModel.get(4).getType());
	}

	@Test
	void bestEffortCompactionCanForceFinalResponse() {
		AgentTurnContextProperties properties = new AgentTurnContextProperties();
		properties.setContextWindowTokens(100);
		properties.setReserveTokens(40);
		properties.setKeepRecentTokens(10);
		TurnContextAutoCompactor compactor = new TurnContextAutoCompactor(properties, tokenCounter);

		TurnExecutionContext turnExecutionContext = new TurnExecutionContext("system", List.of(new AssistantMessage(repeat("H", 45))));
		turnExecutionContext.addTurnMessage(new UserMessage(repeat("Q", 45)));

		TurnContextAutoCompactResult result = compactor.compactForNextModelCall(turnExecutionContext);

		Assertions.assertFalse(result.isCompacted());
		Assertions.assertTrue(result.isForcedFinalResponse());
		Assertions.assertTrue(result.getModelTokenCount() > result.getTriggerTokenLimit());
	}

	@Test
	void throwsWhenMinimalContextAlreadyExceedsHardWindow() {
		AgentTurnContextProperties properties = new AgentTurnContextProperties();
		properties.setContextWindowTokens(80);
		properties.setReserveTokens(20);
		properties.setKeepRecentTokens(10);
		TurnContextAutoCompactor compactor = new TurnContextAutoCompactor(properties, tokenCounter);

		TurnExecutionContext turnExecutionContext = new TurnExecutionContext("system", List.of(new AssistantMessage(repeat("H", 45))));
		turnExecutionContext.addTurnMessage(new UserMessage(repeat("Q", 45)));
		turnExecutionContext.addTurnMessage(new AssistantMessage("tail"));

		TurnContextWindowExceededException error = Assertions.assertThrows(
			TurnContextWindowExceededException.class,
			() -> compactor.compactForNextModelCall(turnExecutionContext)
		);

		Assertions.assertEquals("context_window_exceeded", error.getMessage());
		Assertions.assertTrue(error.getMinimalTokenCount() > error.getContextWindowTokens());
	}

	private static String repeat(String value, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++) {
			sb.append(value);
		}
		return sb.toString();
	}
}
