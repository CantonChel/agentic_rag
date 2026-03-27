package com.agenticrag.app.api;

import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import reactor.core.publisher.Mono;

class ToolControllerTest {
	@Test
	void executePassesKnowledgeBaseIdIntoToolContext() {
		ObjectMapper objectMapper = new ObjectMapper();
		AtomicReference<ToolExecutionContext> capturedContext = new AtomicReference<>();

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new Tool() {
			@Override
			public String name() {
				return "capture_scope";
			}

			@Override
			public String description() {
				return "capture scope";
			}

			@Override
			public JsonNode parametersSchema() {
				ObjectNode schema = objectMapper.createObjectNode();
				schema.put("type", "object");
				return schema;
			}

			@Override
			public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
				capturedContext.set(context);
				return Mono.just(ToolResult.ok("ok"));
			}
		});

		ContextManager contextManager = Mockito.mock(ContextManager.class);
		PersistentMessageStore persistentMessageStore = Mockito.mock(PersistentMessageStore.class);
		ToolController controller = new ToolController(
			toolRouter,
			contextManager,
			persistentMessageStore,
			new ToolArgumentValidator()
		);

		ToolController.ExecuteToolRequest request = new ToolController.ExecuteToolRequest();
		request.setName("capture_scope");
		request.setArguments(objectMapper.createObjectNode());
		request.setToolCallId("call-1");

		ToolResult result = controller.execute(
			"u1",
			"s1",
			"kb-1",
			request,
			null,
			new MockServerHttpResponse()
		).block();

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.isSuccess());
		Assertions.assertNotNull(capturedContext.get());
		Assertions.assertEquals("kb-1", capturedContext.get().getKnowledgeBaseId());
		Assertions.assertEquals("u1", capturedContext.get().getUserId());
		Assertions.assertEquals("s1", capturedContext.get().getSessionId());
	}
}
