package com.agenticrag.app.api;

import com.agenticrag.app.agent.AgentStreamingService;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
	private final AgentStreamingService agentStreamingService;

	public AgentController(AgentStreamingService agentStreamingService) {
		this.agentStreamingService = agentStreamingService;
	}

	@GetMapping(value = "/openai/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<LlmStreamEvent>> streamOpenAi(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "true") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice
	) {
		return agentStreamingService.stream(LlmProvider.OPENAI, userId, sessionId, prompt, tools, toolChoice)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}

	@GetMapping(value = "/minimax/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<LlmStreamEvent>> streamMinimax(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "true") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice
	) {
		return agentStreamingService.stream(LlmProvider.MINIMAX, userId, sessionId, prompt, tools, toolChoice)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}
}
