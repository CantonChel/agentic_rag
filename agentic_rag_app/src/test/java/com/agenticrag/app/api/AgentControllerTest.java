package com.agenticrag.app.api;

import com.agenticrag.app.agent.AgentStreamingService;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.trace.TraceIdUtil;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Flux;

class AgentControllerTest {
	@Test
	void streamOpenAiForwardsKnowledgeBaseId() {
		AgentStreamingService agentStreamingService = Mockito.mock(AgentStreamingService.class);
		Mockito.when(agentStreamingService.stream(
			LlmProvider.OPENAI,
			"u1",
			"s1",
			"hello",
			true,
			LlmToolChoiceMode.AUTO,
			"trace-1",
			"kb-1"
		)).thenReturn(Flux.empty());

		AgentController controller = new AgentController(agentStreamingService);
		MockServerHttpResponse response = new MockServerHttpResponse();

		List<ServerSentEvent<LlmStreamEvent>> events = controller.streamOpenAi(
			"u1",
			"s1",
			"kb-1",
			"hello",
			true,
			LlmToolChoiceMode.AUTO,
			"trace-1",
			response
		).collectList().block();

		Assertions.assertNotNull(events);
		Assertions.assertEquals("trace-1", response.getHeaders().getFirst(TraceIdUtil.HEADER_NAME));
		Mockito.verify(agentStreamingService).stream(
			LlmProvider.OPENAI,
			"u1",
			"s1",
			"hello",
			true,
			LlmToolChoiceMode.AUTO,
			"trace-1",
			"kb-1"
		);
	}
}
