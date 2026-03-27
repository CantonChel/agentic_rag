package com.agenticrag.app.agent;

import com.agenticrag.app.agent.execution.AgentEvalMode;
import com.agenticrag.app.agent.execution.AgentExecutionControl;
import com.agenticrag.app.agent.execution.AgentKbScope;
import com.agenticrag.app.agent.execution.AgentThinkingProfile;
import com.agenticrag.app.chat.context.InMemorySessionContextManager;
import com.agenticrag.app.chat.context.LocalExecutionContextRecorder;
import com.agenticrag.app.chat.context.SessionContextProperties;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.rag.splitter.TokenCounter;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.agenticrag.app.tool.impl.CalculatorTool;
import com.agenticrag.app.tool.impl.ThinkingTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class AgentStreamingServiceTest {
	@Test
	void passesKnowledgeBaseIdIntoToolExecutionContext() {
		ObjectMapper objectMapper = new ObjectMapper();
		AtomicReference<ToolExecutionContext> capturedContext = new AtomicReference<>();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("done")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CapturingTool(objectMapper, capturedContext));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"u1",
			"s1",
			"scope it",
			true,
			LlmToolChoiceMode.AUTO,
			null,
			"kb-1"
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertNotNull(capturedContext.get());
		Assertions.assertEquals("kb-1", capturedContext.get().getKnowledgeBaseId());
		Assertions.assertEquals("u1", capturedContext.get().getUserId());
		Assertions.assertEquals("s1", capturedContext.get().getSessionId());
		Assertions.assertEquals("call_1", capturedContext.get().getToolCallId());
		Assertions.assertEquals("call_1", capturedContext.get().getRequestId());
	}

	@Test
	void executesToolCallsAndWritesFinalAnswerToMemory() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("42")));

		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertTrue(events.size() >= 4);
		Assertions.assertEquals("turn_start", events.get(0).getType());
		Assertions.assertEquals("turn_end", events.get(events.size() - 1).getType());
		Assertions.assertTrue(events.stream().anyMatch(e -> "done".equals(e.getType())));
		Assertions.assertTrue(events.stream().anyMatch(e -> "tool_start".equals(e.getType())));
		Assertions.assertTrue(events.stream().anyMatch(e -> "tool_end".equals(e.getType())));
		long previousSeq = -1L;
		for (LlmStreamEvent event : events) {
			if (event.getSequenceId() == null) {
				continue;
			}
			Assertions.assertTrue(event.getSequenceId() > previousSeq);
			previousSeq = event.getSequenceId();
		}

		boolean hasFinalAnswer = false;
		for (ChatMessage msg : contextManager.getContext("anonymous::s1")) {
			if (msg instanceof com.agenticrag.app.chat.message.AssistantMessage) {
				com.agenticrag.app.chat.message.AssistantMessage am = (com.agenticrag.app.chat.message.AssistantMessage) msg;
				if ("42".equals(am.getContent())) {
					hasFinalAnswer = true;
				}
			}
		}
		Assertions.assertTrue(hasFinalAnswer);
	}

	@Test
	void maxIterationsFallbackStopsToolCalls() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(1);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertEquals("turn_start", events.get(0).getType());
		Assertions.assertEquals("turn_end", events.get(events.size() - 1).getType());
		Assertions.assertEquals("max_iterations_fallback", events.get(events.size() - 1).getFinishReason());
		boolean hasFallbackDone = events.stream()
			.anyMatch(e -> "done".equals(e.getType()) && "max_iterations_fallback".equals(e.getFinishReason()));
		Assertions.assertTrue(hasFallbackDone);
	}

	@Test
	void toolLifecycleEventsMatchByToolCallId() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("42")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LlmStreamEvent start = events.stream().filter(e -> "tool_start".equals(e.getType())).findFirst().orElse(null);
		LlmStreamEvent end = events.stream().filter(e -> "tool_end".equals(e.getType())).findFirst().orElse(null);
		Assertions.assertNotNull(start);
		Assertions.assertNotNull(end);
		Assertions.assertEquals(start.getToolCallId(), end.getToolCallId());
		Assertions.assertTrue(start.getSequenceId() < end.getSequenceId());
		Assertions.assertEquals("success", end.getStatus());
	}

	@Test
	void emitsReasoningEventWhenNoToolCalls() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(reasoningChunk("推理内容")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		boolean hasThinking = events.stream()
			.anyMatch(e -> "thinking".equals(e.getType()) && "reasoning_field".equals(e.getSource()));
		Assertions.assertTrue(hasThinking);
	}

	@Test
	void emitsAssistantContentThinkingWhenStepByStep() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(textChunk("步骤 1. 先做A\\n步骤 2. 再做B")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		boolean hasThinking = events.stream()
			.anyMatch(e -> "thinking".equals(e.getType()) && "assistant_content".equals(e.getSource()));
		Assertions.assertTrue(hasThinking);
	}

	@Test
	void thinkingToolOverridesReasoningField() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(thinkingToolCallsChunk("tool thought", "ignore reasoning")));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("done")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));
		toolRouter.register(new ThinkingTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		long toolThinkingCount = events.stream()
			.filter(e -> "thinking".equals(e.getType()) && "thinking_tool".equals(e.getSource()))
			.count();
		long reasoningCount = events.stream()
			.filter(e -> "thinking".equals(e.getType()) && "reasoning_field".equals(e.getSource()))
			.count();
		Assertions.assertEquals(1, toolThinkingCount);
		Assertions.assertEquals(0, reasoningCount);
	}

	@Test
	void minimaxReasoningSplitStreamsReasoningDetailsIncrementally() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(
			reasoningDetailsChunk("先想", ChatCompletionChunk.Choice.FinishReason.STOP)
		));
		Mockito.when(minimaxClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");
		minimaxProps.setReasoningSplit(true);

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new CalculatorTool(objectMapper));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.MINIMAX, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		List<LlmStreamEvent> reasoningEvents = events.stream()
			.filter(e -> "thinking".equals(e.getType()) && "reasoning_details".equals(e.getSource()))
			.toList();
		Assertions.assertEquals(1, reasoningEvents.size());
		Assertions.assertEquals("先想", reasoningEvents.get(0).getContent());

		ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
		Mockito.verify(minimaxClient.chat().completions()).createStreaming(paramsCaptor.capture());
		ChatCompletionCreateParams params = paramsCaptor.getValue();
		Assertions.assertEquals(Boolean.TRUE, params._additionalBodyProperties().get("reasoning_split").convert(Boolean.class));
	}

	@Test
	void agentPromptUsesAgentModeContext() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(textChunk("ok")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator()
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "hello", false, LlmToolChoiceMode.NONE)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		ArgumentCaptor<SystemPromptContext> contextCaptor = ArgumentCaptor.forClass(SystemPromptContext.class);
		Mockito.verify(systemPromptManager).build(contextCaptor.capture());
		SystemPromptContext promptContext = contextCaptor.getValue();
		Assertions.assertEquals(SystemPromptMode.AGENT, promptContext.getMode());
		Assertions.assertFalse(promptContext.isIncludeTools());
	}

	@Test
	void singleTurnIgnoresExistingSessionContext() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> response = new FakeStreamResponse(Stream.of(textChunk("ok")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(response);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		contextManager.ensureSystemPrompt("anonymous::s1", "old system");
		contextManager.addMessage("anonymous::s1", new UserMessage("old question"));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("old answer"));
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator()
		);

		service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"new question",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));

		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertEquals(2, snapshot.getMessages().size());
		Assertions.assertEquals(ChatMessageType.SYSTEM, snapshot.getMessages().get(0).getType());
		Assertions.assertEquals(ChatMessageType.USER, snapshot.getMessages().get(1).getType());
		Assertions.assertEquals("new question", snapshot.getMessages().get(1).getContent());
	}

	@Test
	void memoryDisabledDoesNotExposeMemoryTool() {
		ObjectMapper objectMapper = new ObjectMapper();
		AtomicReference<Boolean> executed = new AtomicReference<>(false);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(memoryToolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("done")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new StubExecutionTool("memory_search", objectMapper, executed));

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator()
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"question",
			true,
			LlmToolChoiceMode.AUTO,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.DEFAULT, false)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertFalse(executed.get());
		LlmStreamEvent toolEnd = events.stream().filter(e -> "tool_end".equals(e.getType())).findFirst().orElse(null);
		Assertions.assertNotNull(toolEnd);
		Assertions.assertEquals("error", toolEnd.getStatus());

		ArgumentCaptor<SystemPromptContext> contextCaptor = ArgumentCaptor.forClass(SystemPromptContext.class);
		Mockito.verify(systemPromptManager).build(contextCaptor.capture());
		SystemPromptContext promptContext = contextCaptor.getValue();
		Assertions.assertFalse(promptContext.isMemoryEnabled());
		Assertions.assertFalse(promptContext.getAllowedToolNames().contains("memory_search"));
	}

	@Test
	void thinkingProfileHideSuppressesThinkingEventsAndMessages() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> response = new FakeStreamResponse(Stream.of(reasoningChunk("推理内容")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(response);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator()
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"hello",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.HIDE, true)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertTrue(events.stream().noneMatch(e -> "thinking".equals(e.getType())));
		Mockito.verify(persistent, Mockito.never()).append(
			Mockito.anyString(),
			Mockito.argThat(message -> message instanceof ThinkingMessage)
		);
	}

	@Test
	void singleTurnRejectsSessionSwitchCommand() {
		ObjectMapper objectMapper = new ObjectMapper();
		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			new InMemorySessionContextManager(ctxProps, tokenCounter),
			Mockito.mock(PersistentMessageStore.class),
			new LocalExecutionContextRecorder(),
			new AgentProperties(),
			new ToolArgumentValidator()
		);

		Assertions.assertThrows(
			ResponseStatusException.class,
			() -> service.stream(
				LlmProvider.OPENAI,
				"anonymous",
				"s1",
				"/new",
				false,
				LlmToolChoiceMode.NONE,
				null,
				new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.DEFAULT, true)
			)
		);
	}

	private static ChatCompletionChunk toolCallsChunk() {
		ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
			.name("calculator")
			.arguments("{\"op\":\"mul\",\"a\":6,\"b\":7}")
			.build();

		ChatCompletionChunk.Choice.Delta.ToolCall tc = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
			.index(0)
			.id("call_1")
			.function(fn)
			.build();

		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.toolCalls(java.util.Collections.singletonList(tc))
			.build();

		ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta)
			.finishReason(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)
			.build();

		return ChatCompletionChunk.builder()
			.id("c1")
			.created(0)
			.model("gpt-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice)
			.build();
	}

	private static ChatCompletionChunk textChunk(String text) {
		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.content(text)
			.build();

		ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta)
			.finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
			.build();

		return ChatCompletionChunk.builder()
			.id("c2")
			.created(0)
			.model("gpt-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice)
			.build();
	}

	private static ChatCompletionChunk reasoningChunk(String reasoning) {
		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.putAdditionalProperty("reasoning_content", JsonValue.from(reasoning))
			.build();

		ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta)
			.finishReason(ChatCompletionChunk.Choice.FinishReason.STOP)
			.build();

		return ChatCompletionChunk.builder()
			.id("c_reason")
			.created(0)
			.model("gpt-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice)
			.build();
	}

	private static ChatCompletionChunk memoryToolCallsChunk() {
		ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
			.name("memory_search")
			.arguments("{\"query\":\"history\"}")
			.build();

		ChatCompletionChunk.Choice.Delta.ToolCall tc = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
			.index(0)
			.id("call_memory")
			.function(fn)
			.build();

		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.toolCalls(java.util.Collections.singletonList(tc))
			.build();

		ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta)
			.finishReason(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)
			.build();

		return ChatCompletionChunk.builder()
			.id("c_memory")
			.created(0)
			.model("gpt-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice)
			.build();
	}

	private static ChatCompletionChunk thinkingToolCallsChunk(String thought, String reasoning) {
		ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
			.name("thinking")
			.arguments("{\"thought\":\"" + thought + "\"}")
			.build();

		ChatCompletionChunk.Choice.Delta.ToolCall tc = ChatCompletionChunk.Choice.Delta.ToolCall.builder()
			.index(0)
			.id("call_thinking")
			.function(fn)
			.build();

		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.toolCalls(java.util.Collections.singletonList(tc))
			.putAdditionalProperty("reasoning_content", JsonValue.from(reasoning))
			.build();

		ChatCompletionChunk.Choice choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta)
			.finishReason(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS)
			.build();

		return ChatCompletionChunk.builder()
			.id("c_thinking")
			.created(0)
			.model("gpt-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice)
			.build();
	}

	private static ChatCompletionChunk reasoningDetailsChunk(
		String snapshot,
		ChatCompletionChunk.Choice.FinishReason finishReason
	) {
		ChatCompletionChunk.Choice.Delta delta = ChatCompletionChunk.Choice.Delta.builder()
			.putAdditionalProperty("reasoning_details", JsonValue.from(List.of(Map.of("text", snapshot))))
			.build();

		ChatCompletionChunk.Choice.Builder choice = ChatCompletionChunk.Choice.builder()
			.index(0)
			.delta(delta);
		if (finishReason != null) {
			choice.finishReason(finishReason);
		}

		return ChatCompletionChunk.builder()
			.id("c_reasoning_details")
			.created(0)
			.model("minimax-test")
			.object_(JsonValue.from("chat.completion.chunk"))
			.addChoice(choice.build())
			.build();
	}

	private static final class CapturingTool implements Tool {
		private final ObjectMapper objectMapper;
		private final AtomicReference<ToolExecutionContext> capturedContext;

		private CapturingTool(ObjectMapper objectMapper, AtomicReference<ToolExecutionContext> capturedContext) {
			this.objectMapper = objectMapper;
			this.capturedContext = capturedContext;
		}

		@Override
		public String name() {
			return "calculator";
		}

		@Override
		public String description() {
			return "captures scope";
		}

		@Override
		public JsonNode parametersSchema() {
			com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
			root.put("type", "object");
			com.fasterxml.jackson.databind.node.ObjectNode properties = root.putObject("properties");
			properties.putObject("op").put("type", "string");
			properties.putObject("a").put("type", "number");
			properties.putObject("b").put("type", "number");
			root.putArray("required").add("op").add("a").add("b");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			capturedContext.set(context);
			return Mono.just(ToolResult.ok("42"));
		}
	}

	private static final class FakeStreamResponse implements StreamResponse<ChatCompletionChunk> {
		private final Stream<ChatCompletionChunk> stream;

		private FakeStreamResponse(Stream<ChatCompletionChunk> stream) {
			this.stream = stream;
		}

		@Override
		public Stream<ChatCompletionChunk> stream() {
			return stream;
		}

		@Override
		public void close() {
		}
	}

	private static final class StubExecutionTool implements Tool {
		private final String name;
		private final ObjectMapper objectMapper;
		private final AtomicReference<Boolean> executed;

		private StubExecutionTool(String name, ObjectMapper objectMapper, AtomicReference<Boolean> executed) {
			this.name = name;
			this.objectMapper = objectMapper;
			this.executed = executed;
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return name + " tool";
		}

		@Override
		public JsonNode parametersSchema() {
			com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
			root.put("type", "object");
			root.putObject("properties").putObject("query").put("type", "string");
			root.putArray("required").add("query");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			executed.set(true);
			return Mono.just(ToolResult.ok("memory"));
		}
	}
}
