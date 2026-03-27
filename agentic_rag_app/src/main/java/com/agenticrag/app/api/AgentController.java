package com.agenticrag.app.api;

import com.agenticrag.app.agent.AgentStreamingService;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.trace.TraceIdUtil;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
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
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "true") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice,
		@RequestHeader(value = TraceIdUtil.HEADER_NAME, required = false) String traceIdHeader,
		ServerHttpResponse response
	) {
		String traceId = TraceIdUtil.normalizeOrGenerate(traceIdHeader);
		response.getHeaders().set(TraceIdUtil.HEADER_NAME, traceId);
		return agentStreamingService.stream(LlmProvider.OPENAI, userId, sessionId, prompt, tools, toolChoice, traceId, knowledgeBaseId)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}

	@GetMapping(value = "/minimax/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<LlmStreamEvent>> streamMinimax(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "true") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice,
		@RequestHeader(value = TraceIdUtil.HEADER_NAME, required = false) String traceIdHeader,
		ServerHttpResponse response
	) {
		String traceId = TraceIdUtil.normalizeOrGenerate(traceIdHeader);
		response.getHeaders().set(TraceIdUtil.HEADER_NAME, traceId);
		return agentStreamingService.stream(LlmProvider.MINIMAX, userId, sessionId, prompt, tools, toolChoice, traceId, knowledgeBaseId)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}
}
