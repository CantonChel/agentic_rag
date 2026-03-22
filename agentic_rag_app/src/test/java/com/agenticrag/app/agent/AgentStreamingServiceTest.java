package com.agenticrag.app.agent;

import com.agenticrag.app.chat.context.InMemorySessionContextManager;
import com.agenticrag.app.chat.context.LocalExecutionContextRecorder;
import com.agenticrag.app.chat.context.SessionContextProperties;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.rag.splitter.TokenCounter;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolRouter;
import com.agenticrag.app.tool.impl.CalculatorTool;
import com.agenticrag.app.tool.impl.ThinkingTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

class AgentStreamingServiceTest {
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
		Assertions.assertTrue(events.size() >= 2);
		Assertions.assertEquals("delta", events.get(0).getType());
		Assertions.assertEquals("done", events.get(events.size() - 1).getType());

		boolean hasFinalAnswer = false;
		for (ChatMessage msg : contextManager.getContext("s1")) {
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
		Assertions.assertEquals("done", events.get(events.size() - 1).getType());
		Assertions.assertEquals("max_iterations_fallback", events.get(events.size() - 1).getFinishReason());
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
}
