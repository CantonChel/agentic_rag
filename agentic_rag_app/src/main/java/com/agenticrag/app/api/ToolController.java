package com.agenticrag.app.api;

import com.agenticrag.app.chat.memory.ChatMemory;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolDefinition;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/tools")
public class ToolController {
	private final ToolRouter toolRouter;
	private final ChatMemory chatMemory;

	public ToolController(ToolRouter toolRouter, ChatMemory chatMemory) {
		this.toolRouter = toolRouter;
		this.chatMemory = chatMemory;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ToolDefinition> list() {
		return toolRouter.getToolDefinitions().stream().collect(Collectors.toList());
	}

	@PostMapping(value = "/execute", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ToolResult> execute(
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestBody ExecuteToolRequest request
	) {
		ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID().toString());
		String sid = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
		return toolRouter.getTool(request.getName())
			.map(t -> t.execute(request.getArguments(), context).doOnNext(result -> chatMemory.append(
				sid,
				new ToolResultMessage(
					request.getName(),
					request.getToolCallId(),
					result.isSuccess(),
					result.getOutput(),
					result.getError()
				)
			)))
			.orElseGet(() -> Mono.just(ToolResult.error("Tool not found: " + request.getName())));
	}

	public static class ExecuteToolRequest {
		private String name;
		private JsonNode arguments;
		private String toolCallId;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public JsonNode getArguments() {
			return arguments;
		}

		public void setArguments(JsonNode arguments) {
			this.arguments = arguments;
		}

		public String getToolCallId() {
			return toolCallId;
		}

		public void setToolCallId(String toolCallId) {
			this.toolCallId = toolCallId;
		}
	}
}
