package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolDefinition;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.agenticrag.app.session.SessionScope;
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
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final ToolArgumentValidator toolArgumentValidator;

	public ToolController(
		ToolRouter toolRouter,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		ToolArgumentValidator toolArgumentValidator
	) {
		this.toolRouter = toolRouter;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.toolArgumentValidator = toolArgumentValidator;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public List<ToolDefinition> list() {
		return toolRouter.getToolDefinitions().stream().collect(Collectors.toList());
	}

	@PostMapping(value = "/execute", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ToolResult> execute(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
		@RequestBody ExecuteToolRequest request
	) {
		String uid = SessionScope.normalizeUserId(userId);
		String sid = SessionScope.normalizeSessionId(sessionId);
		String scopedSid = SessionScope.scopedSessionId(uid, sid);
		ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID().toString(), uid, sid);
		return toolRouter.getTool(request.getName())
			.map(t -> {
				ToolArgumentValidator.ValidationResult vr = toolArgumentValidator.validate(t.parametersSchema(), request.getArguments());
				if (!vr.isOk()) {
					String err = "Error: 参数解析失败。请检查并重新调用工具。细节: " + String.join("; ", vr.getErrors());
					ToolResult tr = ToolResult.error(err);
					ToolResultMessage msg = new ToolResultMessage(request.getName(), request.getToolCallId(), false, null, tr.getError());
					persistentMessageStore.append(scopedSid, msg);
					contextManager.addMessage(scopedSid, msg);
					return Mono.just(tr);
				}

				return t.execute(request.getArguments(), context).doOnNext(result -> {
					ToolResultMessage msg = new ToolResultMessage(
						request.getName(),
						request.getToolCallId(),
						result.isSuccess(),
						result.getOutput(),
						result.getError()
					);
					persistentMessageStore.append(scopedSid, msg);
					contextManager.addMessage(scopedSid, msg);
				});
			})
			.orElseGet(() -> Mono.just(ToolResult.error("Tool not found: " + request.getName())))
			;
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
