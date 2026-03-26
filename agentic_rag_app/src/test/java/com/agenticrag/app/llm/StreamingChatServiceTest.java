package com.agenticrag.app.llm;

import com.agenticrag.app.chat.context.InMemorySessionContextManager;
import com.agenticrag.app.chat.context.SessionContextProperties;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.rag.splitter.TokenCounter;
import com.agenticrag.app.tool.ToolRouter;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class StreamingChatServiceTest {
	@Test
	void llmPromptUsesLlmModeContext() {
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
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);

		StreamingChatService service = new StreamingChatService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			new ToolRouter(),
			objectMapper,
			systemPromptManager,
			contextManager,
			persistentMessageStore
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "u1", "s1", "hello", false, LlmToolChoiceMode.NONE)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		ArgumentCaptor<SystemPromptContext> contextCaptor = ArgumentCaptor.forClass(SystemPromptContext.class);
		Mockito.verify(systemPromptManager).build(contextCaptor.capture());
		SystemPromptContext promptContext = contextCaptor.getValue();
		Assertions.assertEquals(SystemPromptMode.LLM, promptContext.getMode());
		Assertions.assertFalse(promptContext.isIncludeTools());
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
			.id("c_text")
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
