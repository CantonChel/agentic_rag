package com.agenticrag.app.api;

import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.llm.StreamingChatService;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

class LlmControllerTest {
	@Test
	void streamOpenAiForwardsKnowledgeBaseId() {
		StreamingChatService streamingChatService = Mockito.mock(StreamingChatService.class);
		Mockito.when(streamingChatService.stream(
			LlmProvider.OPENAI,
			"u1",
			"s1",
			"hello",
			false,
			LlmToolChoiceMode.NONE,
			"kb-1"
		)).thenReturn(Flux.empty());

		LlmController controller = new LlmController(streamingChatService);

		List<ServerSentEvent<LlmStreamEvent>> events = controller.streamOpenAi(
			"u1",
			"s1",
			"kb-1",
			"hello",
			false,
			LlmToolChoiceMode.NONE
		).collectList().block();

		Assertions.assertNotNull(events);
		Mockito.verify(streamingChatService).stream(
			LlmProvider.OPENAI,
			"u1",
			"s1",
			"hello",
			false,
			LlmToolChoiceMode.NONE,
			"kb-1"
		);
	}
}
