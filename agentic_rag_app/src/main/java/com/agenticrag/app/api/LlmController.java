package com.agenticrag.app.api;

import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.llm.StreamingChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/llm")
public class LlmController {
	private final StreamingChatService streamingChatService;

	public LlmController(StreamingChatService streamingChatService) {
		this.streamingChatService = streamingChatService;
	}

	@GetMapping(value = "/openai/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<LlmStreamEvent>> streamOpenAi(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "false") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice
	) {
		return streamingChatService.stream(LlmProvider.OPENAI, userId, sessionId, prompt, tools, toolChoice, knowledgeBaseId)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}

	@GetMapping(value = "/minimax/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<LlmStreamEvent>> streamMinimax(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam("prompt") String prompt,
		@RequestParam(value = "tools", defaultValue = "false") boolean tools,
		@RequestParam(value = "toolChoice", defaultValue = "AUTO") LlmToolChoiceMode toolChoice
	) {
		return streamingChatService.stream(LlmProvider.MINIMAX, userId, sessionId, prompt, tools, toolChoice, knowledgeBaseId)
			.map(e -> ServerSentEvent.builder(e).event(e.getType()).build());
	}
}
