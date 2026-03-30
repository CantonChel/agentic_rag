package com.agenticrag.app.agent;

import com.agenticrag.app.agent.execution.AgentEvalMode;
import com.agenticrag.app.agent.execution.AgentExecutionControl;
import com.agenticrag.app.agent.execution.AgentKbScope;
import com.agenticrag.app.agent.execution.AgentThinkingProfile;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryWriteModel;
import com.agenticrag.app.chat.context.InMemorySessionContextManager;
import com.agenticrag.app.chat.context.LocalExecutionContextRecorder;
import com.agenticrag.app.chat.context.SessionContextBudgetEvaluator;
import com.agenticrag.app.chat.context.SessionContextPreflightCompactor;
import com.agenticrag.app.chat.context.SessionContextProperties;
import com.agenticrag.app.chat.context.SessionContextProjector;
import com.agenticrag.app.chat.context.TurnContextAutoCompactor;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
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
import com.agenticrag.app.memory.DailyDurableFlushService;
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
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
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
	void minimaxFinalIterationUsesSingleSystemMessage() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("收尾答案")));
		Mockito.when(minimaxClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
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
		ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
		Mockito.verify(minimaxClient.chat().completions(), Mockito.times(2)).createStreaming(paramsCaptor.capture());

		List<ChatCompletionCreateParams> allParams = paramsCaptor.getAllValues();
		Assertions.assertEquals(2, allParams.size());

		ChatCompletionCreateParams finalParams = allParams.get(1);
		List<ChatCompletionMessageParam> systemMessages = finalParams.messages().stream()
			.filter(ChatCompletionMessageParam::isSystem)
			.toList();
		Assertions.assertEquals(1, systemMessages.size());
		String systemContent = systemMessages.get(0).asSystem().content().asText();
		Assertions.assertTrue(systemContent.contains("system"));
		Assertions.assertTrue(systemContent.contains("系统警告：你已达到最大思考步数"));
	}

	@Test
	void finalIterationSanitizesMinimaxToolMarkupAnswer() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		String fakeMarkup = "<minimax:tool_call>\n"
			+ "<invoke name=\"search_knowledge_base\">\n"
			+ "<parameter name=\"query\">通讯录模板 字段信息</parameter>\n"
			+ "</invoke>\n"
			+ "</minimax:tool_call>";
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk(fakeMarkup)));
		Mockito.when(minimaxClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
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
		List<LlmStreamEvent> deltaEvents = events.stream()
			.filter(e -> "delta".equals(e.getType()))
			.toList();
		Assertions.assertFalse(deltaEvents.isEmpty());
		Assertions.assertTrue(deltaEvents.stream().noneMatch(e -> e.getContent() != null && e.getContent().contains("<minimax:tool_call>")));
		Assertions.assertEquals("根据当前已检索到的内容，我暂时无法给出确定答案。", deltaEvents.get(deltaEvents.size() - 1).getContent());
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
		toolRouter.register(new StubExecutionTool("memory_get", objectMapper, executed));

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
		Assertions.assertFalse(promptContext.getAllowedToolNames().contains("memory_get"));
	}

	@Test
	void memoryDisabledSkipsPreCompactionFlushAndProjectsCleanSessionContext() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk(repeat("答", 24))));
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
		ctxProps.setMaxTokens(20);
		ctxProps.setKeepLastMessages(10);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
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
			repeat("问", 24),
			true,
			LlmToolChoiceMode.AUTO,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, false)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Mockito.verify(dailyDurableFlushService, Mockito.never())
			.flush(Mockito.anyString(), Mockito.anyList());

		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertEquals(ChatMessageType.SYSTEM, snapshot.getMessages().get(0).getType());
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> msg.getType() == ChatMessageType.TOOL_CALL));
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> msg.getType() == ChatMessageType.TOOL_RESULT));

		List<ChatMessage> sessionContext = contextManager.getContext("anonymous::s1");
		Assertions.assertEquals(ChatMessageType.SYSTEM, sessionContext.get(0).getType());
		Assertions.assertTrue(sessionContext.stream().anyMatch(msg -> msg.getType() == ChatMessageType.USER));
		Assertions.assertTrue(sessionContext.stream().anyMatch(msg -> msg.getType() == ChatMessageType.ASSISTANT));
		Assertions.assertTrue(sessionContext.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_CALL));
		Assertions.assertTrue(sessionContext.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_RESULT));
	}

	@Test
	void secondTurnReadsProjectedSessionMessagesWithoutToolTrajectory() {
		ObjectMapper objectMapper = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("42")));
		StreamResponse<ChatCompletionChunk> r3 = new FakeStreamResponse(Stream.of(textChunk("next answer")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2, r3);

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

		List<LlmStreamEvent> firstTurn = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"first question",
			true,
			LlmToolChoiceMode.AUTO,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));
		Assertions.assertNotNull(firstTurn);

		List<ChatMessage> sessionContextAfterFirstTurn = contextManager.getContext("anonymous::s1");
		Assertions.assertTrue(sessionContextAfterFirstTurn.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_CALL));
		Assertions.assertTrue(sessionContextAfterFirstTurn.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_RESULT));

		List<LlmStreamEvent> secondTurn = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"follow up",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));
		Assertions.assertNotNull(secondTurn);

		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertEquals(ChatMessageType.SYSTEM, snapshot.getMessages().get(0).getType());
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_CALL));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_RESULT));
		long userCount = snapshot.getMessages().stream().filter(msg -> msg.getType() == ChatMessageType.USER).count();
		long assistantCount = snapshot.getMessages().stream().filter(msg -> msg.getType() == ChatMessageType.ASSISTANT).count();
		Assertions.assertEquals(2, userCount);
		Assertions.assertEquals(1, assistantCount);
	}

	@Test
	void preflightCompactsSessionHistoryBeforeTurnInitialization() {
		ObjectMapper objectMapper = new ObjectMapper();
		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(new FakeStreamResponse(Stream.of(textChunk("fresh answer"))));

		ToolRouter toolRouter = new ToolRouter();
		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(600);
		ctxProps.setPreflightReserveTokens(180);
		ctxProps.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		contextManager.ensureSystemPrompt("anonymous::s1", "system");
		contextManager.addMessage("anonymous::s1", new UserMessage("old-1-user-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("old-1-assistant-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new UserMessage("keep-user-" + repeat("b", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("keep-assistant-" + repeat("b", 100)));

		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();
		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = newServiceWithPreflight(
			openAiClient,
			minimaxClient,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator(),
			ctxProps,
			tokenCounter,
			dailyDurableFlushService
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"current question",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertEquals(ChatMessageType.SYSTEM, snapshot.getMessages().get(0).getType());
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith("keep-user-")));
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith("keep-assistant-")));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("old-1-user-")));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("old-1-assistant-")));
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> "current question".equals(messageContent(msg))));
		Mockito.verify(dailyDurableFlushService, Mockito.times(1))
			.flush(Mockito.eq("anonymous::s1"), Mockito.anyList());
	}

	@Test
	void memoryDisabledPreflightCompactsWithoutFlushBeforeTurnStarts() {
		ObjectMapper objectMapper = new ObjectMapper();
		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(new FakeStreamResponse(Stream.of(textChunk("fresh answer"))));

		ToolRouter toolRouter = new ToolRouter();
		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(600);
		ctxProps.setPreflightReserveTokens(180);
		ctxProps.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		contextManager.ensureSystemPrompt("anonymous::s1", "system");
		contextManager.addMessage("anonymous::s1", new UserMessage("old-1-user-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("old-1-assistant-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new UserMessage("keep-user-" + repeat("b", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("keep-assistant-" + repeat("b", 100)));

		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();
		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = newServiceWithPreflight(
			openAiClient,
			minimaxClient,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator(),
			ctxProps,
			tokenCounter,
			dailyDurableFlushService
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"memory off question",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, false)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith("keep-user-")));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("old-1-user-")));
		Mockito.verify(dailyDurableFlushService, Mockito.never())
			.flush(Mockito.anyString(), Mockito.anyList());
	}

	@Test
	void singleTurnSkipsPreflightCompactionAndIgnoresSessionHistory() {
		ObjectMapper objectMapper = new ObjectMapper();
		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(new FakeStreamResponse(Stream.of(textChunk("fresh answer"))));

		ToolRouter toolRouter = new ToolRouter();
		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(600);
		ctxProps.setPreflightReserveTokens(180);
		ctxProps.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		DailyDurableFlushService dailyDurableFlushService = Mockito.mock(DailyDurableFlushService.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		contextManager.ensureSystemPrompt("anonymous::s1", "system");
		contextManager.addMessage("anonymous::s1", new UserMessage("old-1-user-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("old-1-assistant-" + repeat("a", 100)));
		contextManager.addMessage("anonymous::s1", new UserMessage("keep-user-" + repeat("b", 100)));
		contextManager.addMessage("anonymous::s1", new AssistantMessage("keep-assistant-" + repeat("b", 100)));

		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();
		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		AgentStreamingService service = newServiceWithPreflight(
			openAiClient,
			minimaxClient,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator(),
			ctxProps,
			tokenCounter,
			dailyDurableFlushService
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"single turn question",
			false,
			LlmToolChoiceMode.NONE,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		long userCount = snapshot.getMessages().stream().filter(msg -> msg.getType() == ChatMessageType.USER).count();
		long assistantCount = snapshot.getMessages().stream().filter(msg -> msg.getType() == ChatMessageType.ASSISTANT).count();
		Assertions.assertEquals(1, userCount);
		Assertions.assertEquals(0, assistantCount);
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("keep-user-")));
		Mockito.verify(dailyDurableFlushService, Mockito.never())
			.flush(Mockito.anyString(), Mockito.anyList());
	}

	@Test
	void turnAutoCompactRewritesThirdModelCallButKeepsPersistentRawTrace() {
		ObjectMapper objectMapper = new ObjectMapper();
		ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk("large_lookup", "{\"q\":\"first\"}")));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(toolCallsChunk("large_lookup", "{\"q\":\"second\"}")));
		StreamResponse<ChatCompletionChunk> r3 = new FakeStreamResponse(Stream.of(textChunk("final answer")));
		Mockito.when(openAiClient.chat().completions().createStreaming(paramsCaptor.capture()))
			.thenReturn(r1, r2, r3);

		ToolRouter toolRouter = new ToolRouter();
		String firstOutput = "FIRST-" + repeat("甲", 520);
		String secondOutput = "SECOND-" + repeat("乙", 520);
		toolRouter.register(new SequencedLargeResultTool("large_lookup", objectMapper, firstOutput, secondOutput));

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

		AgentTurnContextProperties turnProps = new AgentTurnContextProperties();
		turnProps.setContextWindowTokens(980);
		turnProps.setReserveTokens(50);
		turnProps.setKeepRecentTokens(80);

		AgentStreamingService service = newServiceWithCompaction(
			openAiClient,
			minimaxClient,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator(),
			ctxProps,
			tokenCounter,
			null,
			turnProps
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"please inspect twice",
			true,
			LlmToolChoiceMode.AUTO,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertTrue(events.stream().anyMatch(e -> "done".equals(e.getType()) && "stop".equals(e.getFinishReason())));

		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).contains("[Context Compact Summary]")));
		long toolResultCount = snapshot.getMessages().stream().filter(msg -> msg.getType() == ChatMessageType.TOOL_RESULT).count();
		Assertions.assertEquals(1, toolResultCount);
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith(secondOutput)));

		List<ChatCompletionCreateParams> calls = paramsCaptor.getAllValues();
		Assertions.assertEquals(3, calls.size());
		String thirdCallPayload = String.valueOf(calls.get(2));
		Assertions.assertTrue(thirdCallPayload.contains("[Context Compact Summary]"));
		Assertions.assertTrue(thirdCallPayload.contains("SECOND-"));

		ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
		Mockito.verify(persistent, Mockito.atLeast(1)).append(Mockito.eq("anonymous::s1"), messageCaptor.capture());
		List<ChatMessage> persistedMessages = messageCaptor.getAllValues();
		List<ToolResultMessage> toolResults = persistedMessages.stream()
			.filter(ToolResultMessage.class::isInstance)
			.map(ToolResultMessage.class::cast)
			.toList();
		Assertions.assertEquals(2, toolResults.size());
		Assertions.assertTrue(toolResults.stream().anyMatch(message -> message.getContent().startsWith(firstOutput)));
		Assertions.assertTrue(toolResults.stream().anyMatch(message -> message.getContent().startsWith(secondOutput)));
	}

	@Test
	void contextWindowFallbackDisablesToolsAndProjectsFinalAssistant() {
		ObjectMapper objectMapper = new ObjectMapper();
		ArgumentCaptor<ChatCompletionCreateParams> paramsCaptor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk("large_lookup", "{\"q\":\"first\"}")));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(toolCallsChunk("large_lookup", "{\"q\":\"second\"}")));
		StreamResponse<ChatCompletionChunk> r3 = new FakeStreamResponse(Stream.of(textChunk("fallback final answer")));
		Mockito.when(openAiClient.chat().completions().createStreaming(paramsCaptor.capture()))
			.thenReturn(r1, r2, r3);

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(
			new SequencedLargeResultTool(
				"large_lookup",
				objectMapper,
				"FIRST-" + repeat("甲", 320),
				"SECOND-" + repeat("乙", 320)
			)
		);

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

		AgentTurnContextProperties turnProps = new AgentTurnContextProperties();
		turnProps.setContextWindowTokens(900);
		turnProps.setReserveTokens(600);
		turnProps.setKeepRecentTokens(80);

		AgentStreamingService service = newServiceWithCompaction(
			openAiClient,
			minimaxClient,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistent,
			recorder,
			agentProps,
			new ToolArgumentValidator(),
			ctxProps,
			tokenCounter,
			null,
			turnProps
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"anonymous",
			"s1",
			"need fallback",
			true,
			LlmToolChoiceMode.AUTO,
			null,
			new AgentExecutionControl(null, null, AgentKbScope.AUTO, AgentEvalMode.DEFAULT, AgentThinkingProfile.DEFAULT, true)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LlmStreamEvent doneEvent = events.stream().filter(e -> "done".equals(e.getType())).findFirst().orElse(null);
		Assertions.assertNotNull(doneEvent);
		Assertions.assertEquals("context_window_fallback", doneEvent.getFinishReason());

		List<ChatCompletionCreateParams> calls = paramsCaptor.getAllValues();
		Assertions.assertEquals(2, calls.size());
		ChatCompletionCreateParams fallbackCall = calls.get(1);
		Assertions.assertTrue(String.valueOf(fallbackCall).contains("当前上下文已接近窗口上限"));
		Assertions.assertTrue(fallbackCall.tools().orElse(List.of()).isEmpty());
		Assertions.assertEquals(ChatCompletionToolChoiceOption.Auto.NONE, fallbackCall.toolChoice().orElseThrow().asAuto());

		List<ChatMessage> sessionContext = contextManager.getContext("anonymous::s1");
		Assertions.assertEquals(ChatMessageType.SYSTEM, sessionContext.get(0).getType());
		Assertions.assertTrue(sessionContext.stream().anyMatch(msg -> msg.getType() == ChatMessageType.USER));
		Assertions.assertTrue(sessionContext.stream().anyMatch(msg -> msg.getType() == ChatMessageType.ASSISTANT));
		Assertions.assertTrue(sessionContext.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_CALL));
		Assertions.assertTrue(sessionContext.stream().noneMatch(msg -> msg.getType() == ChatMessageType.TOOL_RESULT));
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

	@Test
	void savesTurnExecutionSummaryOnSuccessfulToolRun() {
		ObjectMapper objectMapper = new ObjectMapper();
		BenchmarkTurnExecutionSummaryService summaryService = Mockito.mock(BenchmarkTurnExecutionSummaryService.class);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk()));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("final answer")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new RetrievalSidecarTool(objectMapper, "kb-1"));

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
			new ToolArgumentValidator(),
			summaryService
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"user-1",
			"s1",
			"question",
			true,
			LlmToolChoiceMode.AUTO,
			"trace-1",
			new AgentExecutionControl("build-1", "kb-1", AgentKbScope.BENCHMARK_BUILD, AgentEvalMode.SINGLE_TURN, AgentThinkingProfile.DEFAULT, false)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);

		ArgumentCaptor<BenchmarkTurnExecutionSummaryWriteModel> captor = ArgumentCaptor.forClass(BenchmarkTurnExecutionSummaryWriteModel.class);
		Mockito.verify(summaryService).saveSummary(captor.capture());
		BenchmarkTurnExecutionSummaryWriteModel summary = captor.getValue();

		Assertions.assertEquals("s1", summary.sessionId());
		Assertions.assertEquals("user-1", summary.userId());
		Assertions.assertEquals("trace-1", summary.traceId());
		Assertions.assertEquals("OPENAI", summary.provider());
		Assertions.assertEquals("gpt-test", summary.originModel());
		Assertions.assertEquals("build-1", summary.buildId());
		Assertions.assertEquals("kb-1", summary.knowledgeBaseId());
		Assertions.assertEquals("BENCHMARK_BUILD", summary.kbScope());
		Assertions.assertEquals("SINGLE_TURN", summary.evalMode());
		Assertions.assertEquals("DEFAULT", summary.thinkingProfile());
		Assertions.assertFalse(summary.memoryEnabled());
		Assertions.assertEquals("question", summary.userQuestion());
		Assertions.assertEquals("final answer", summary.finalAnswer());
		Assertions.assertTrue(summary.finishReason().equalsIgnoreCase("stop"));
		Assertions.assertNull(summary.errorMessage());
		Assertions.assertEquals(1, summary.toolCalls().size());
		Assertions.assertEquals("call_1", summary.toolCalls().get(0).toolCallId());
		Assertions.assertEquals("calculator", summary.toolCalls().get(0).toolName());
		Assertions.assertEquals("success", summary.toolCalls().get(0).status());
		Assertions.assertEquals(List.of("trace-r1"), summary.retrievalTraceIds());
		Assertions.assertEquals(1, summary.retrievalTraceRefs().size());
		Assertions.assertEquals("trace-r1", summary.retrievalTraceRefs().get(0).traceId());
		Assertions.assertEquals("call_1", summary.retrievalTraceRefs().get(0).toolCallId());
		Assertions.assertEquals("calculator", summary.retrievalTraceRefs().get(0).toolName());
		Assertions.assertNotNull(summary.latencyMs());
		Assertions.assertNotNull(summary.completedAt());
	}

	@Test
	void savesTurnExecutionSummaryOnAgentError() {
		ObjectMapper objectMapper = new ObjectMapper();
		BenchmarkTurnExecutionSummaryService summaryService = Mockito.mock(BenchmarkTurnExecutionSummaryService.class);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenThrow(new RuntimeException("boom"));

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
			new ToolArgumentValidator(),
			summaryService
		);

		List<LlmStreamEvent> events = service.stream(
			LlmProvider.OPENAI,
			"user-1",
			"s1",
			"question",
			false,
			LlmToolChoiceMode.NONE,
			"trace-err",
			AgentExecutionControl.defaults(null)
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		Assertions.assertTrue(events.stream().anyMatch(event -> "error".equals(event.getType())));

		ArgumentCaptor<BenchmarkTurnExecutionSummaryWriteModel> captor = ArgumentCaptor.forClass(BenchmarkTurnExecutionSummaryWriteModel.class);
		Mockito.verify(summaryService).saveSummary(captor.capture());
		BenchmarkTurnExecutionSummaryWriteModel summary = captor.getValue();

		Assertions.assertEquals("error", summary.finishReason());
		Assertions.assertNull(summary.finalAnswer());
		Assertions.assertTrue(summary.toolCalls().isEmpty());
		Assertions.assertTrue(summary.retrievalTraceIds().isEmpty());
		Assertions.assertNotNull(summary.errorMessage());
		Assertions.assertTrue(summary.errorMessage().contains("RuntimeException: boom"));
	}

	@Test
	void savesTurnExecutionSummaryOnClientCancellation() {
		ObjectMapper objectMapper = new ObjectMapper();
		BenchmarkTurnExecutionSummaryService summaryService = Mockito.mock(BenchmarkTurnExecutionSummaryService.class);

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenAnswer(invocation -> {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
				return new FakeStreamResponse(Stream.of(textChunk("late")));
			});

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

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			contextManager,
			Mockito.mock(PersistentMessageStore.class),
			new LocalExecutionContextRecorder(),
			new AgentProperties(),
			new ToolArgumentValidator(),
			summaryService
		);

		List<LlmStreamEvent> firstEvent = service.stream(
			LlmProvider.OPENAI,
			"user-1",
			"s1",
			"question",
			false,
			LlmToolChoiceMode.NONE,
			"trace-cancel",
			AgentExecutionControl.defaults(null)
		).take(1).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(firstEvent);
		Assertions.assertEquals(1, firstEvent.size());
		Assertions.assertEquals("turn_start", firstEvent.get(0).getType());

		ArgumentCaptor<BenchmarkTurnExecutionSummaryWriteModel> captor = ArgumentCaptor.forClass(BenchmarkTurnExecutionSummaryWriteModel.class);
		Mockito.verify(summaryService, Mockito.timeout(3000)).saveSummary(captor.capture());
		BenchmarkTurnExecutionSummaryWriteModel summary = captor.getValue();

		Assertions.assertEquals("cancelled", summary.finishReason());
		Assertions.assertNull(summary.finalAnswer());
		Assertions.assertTrue(summary.toolCalls().isEmpty());
		Assertions.assertTrue(summary.retrievalTraceIds().isEmpty());
	}

	private static ChatCompletionChunk toolCallsChunk() {
		return toolCallsChunk("calculator", "{\"op\":\"mul\",\"a\":6,\"b\":7}");
	}

	private static ChatCompletionChunk toolCallsChunk(String toolName, String jsonArgs) {
		ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
			.name(toolName)
			.arguments(jsonArgs)
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

	private AgentStreamingService newServiceWithPreflight(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		InMemorySessionContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		LocalExecutionContextRecorder recorder,
		AgentProperties agentProperties,
		ToolArgumentValidator validator,
		SessionContextProperties ctxProps,
		TokenCounter tokenCounter,
		DailyDurableFlushService dailyDurableFlushService
	) {
		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");
		return new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistentMessageStore,
			null,
			recorder,
			agentProperties,
			validator,
			null,
			null,
			null,
			new SessionContextProjector(),
			new SessionContextPreflightCompactor(
				contextManager,
				new SessionContextBudgetEvaluator(ctxProps, tokenCounter),
				dailyDurableFlushService
			),
			new TurnContextAutoCompactor()
		);
	}

	private AgentStreamingService newServiceWithCompaction(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		InMemorySessionContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		LocalExecutionContextRecorder recorder,
		AgentProperties agentProperties,
		ToolArgumentValidator validator,
		SessionContextProperties ctxProps,
		TokenCounter tokenCounter,
		DailyDurableFlushService dailyDurableFlushService,
		AgentTurnContextProperties turnProps
	) {
		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");
		return new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistentMessageStore,
			null,
			recorder,
			agentProperties,
			validator,
			null,
			null,
			null,
			new SessionContextProjector(),
			new SessionContextPreflightCompactor(
				contextManager,
				new SessionContextBudgetEvaluator(ctxProps, tokenCounter),
				dailyDurableFlushService
			),
			new TurnContextAutoCompactor(turnProps, tokenCounter)
		);
	}

	private static String messageContent(ChatMessage message) {
		return message != null && message.getContent() != null ? message.getContent() : "";
	}

	private static String repeat(String value, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++) {
			sb.append(value);
		}
		return sb.toString();
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

	private static final class RetrievalSidecarTool implements Tool {
		private final ObjectMapper objectMapper;
		private final String knowledgeBaseId;

		private RetrievalSidecarTool(ObjectMapper objectMapper, String knowledgeBaseId) {
			this.objectMapper = objectMapper;
			this.knowledgeBaseId = knowledgeBaseId;
		}

		@Override
		public String name() {
			return "calculator";
		}

		@Override
		public String description() {
			return "returns retrieval sidecar";
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
			com.fasterxml.jackson.databind.node.ObjectNode sidecar = objectMapper.createObjectNode();
			sidecar.put("type", "retrieval_context_v1");
			sidecar.put("traceId", "trace-r1");
			sidecar.put("toolCallId", context.getToolCallId());
			sidecar.put("toolName", name());
			sidecar.put("knowledgeBaseId", knowledgeBaseId);
			sidecar.putArray("items");
			return Mono.just(ToolResult.ok("retrieved", sidecar));
		}
	}

	private static final class SequencedLargeResultTool implements Tool {
		private final String name;
		private final ObjectMapper objectMapper;
		private final List<String> outputs;
		private int cursor = 0;

		private SequencedLargeResultTool(String name, ObjectMapper objectMapper, String... outputs) {
			this.name = name;
			this.objectMapper = objectMapper;
			this.outputs = List.of(outputs);
		}

		@Override
		public String name() {
			return name;
		}

		@Override
		public String description() {
			return "returns large staged outputs";
		}

		@Override
		public JsonNode parametersSchema() {
			com.fasterxml.jackson.databind.node.ObjectNode root = objectMapper.createObjectNode();
			root.put("type", "object");
			root.putObject("properties").putObject("q").put("type", "string");
			root.putArray("required").add("q");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			int index = Math.min(cursor, outputs.size() - 1);
			String output = outputs.get(index);
			cursor++;
			return Mono.just(ToolResult.ok(output));
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
