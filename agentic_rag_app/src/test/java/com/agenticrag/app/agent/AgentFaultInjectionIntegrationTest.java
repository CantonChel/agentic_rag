package com.agenticrag.app.agent;

import com.agenticrag.app.chat.context.InMemorySessionContextManager;
import com.agenticrag.app.chat.context.LocalExecutionContextRecorder;
import com.agenticrag.app.chat.context.SessionContextBudgetEvaluator;
import com.agenticrag.app.chat.context.SessionContextPreflightCompactor;
import com.agenticrag.app.chat.context.SessionContextProperties;
import com.agenticrag.app.chat.context.SessionContextProjector;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.memory.MemoryFlushService;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.rag.splitter.TokenCounter;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/**
 * 故障注入式（Fault Injection）的行为/集成测试：
 *
 * <p>目的不是验证“模型是否聪明”，而是验证当模型不可控（乱传参、死循环、输出巨长文本）时，
 * Agent Loop 的鲁棒性机制是否能被稳定触发，并且保证系统不崩溃、能自我修复或兜底落地。</p>
 *
 * <p>测试方法：不打真实 OpenAI API（避免费用 + flakiness），而是 mock OpenAI SDK 的 streaming 响应，
 * 人工构造 ChatCompletionChunk 流，精确控制每一轮返回的是 tool_calls 还是最终文本。</p>
 *
 * <p>核心验证点：</p>
 * <ul>
 *   <li>JSON Schema 校验失败时不会 500/中断，而是把错误作为 tool 返回给“下一轮模型”</li>
 *   <li>达到 maxIterations 时触发强制交卷（最后一轮禁用工具调用）</li>
 *   <li>Session Memory 不被 ToolCall/ToolResult 污染（清理战场）</li>
 * </ul>
 */
class AgentFaultInjectionIntegrationTest {
	@Test
	void selfCorrectionOnInvalidToolArgsDoesNotCrashAndFeedsErrorBackToModel() {
		// 场景：模型第一次调用工具故意传错类型（a="123"），第二次再修正为 a=123。
		// 期望：第一轮工具执行前被 JSON Schema 拦截，系统把错误作为 tool message 喂回模型；
		//      第二轮模型修正参数后，工具执行成功，第三轮产出最终答案。
		ObjectMapper om = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		// 捕获每一轮 Agent 发给 OpenAI SDK 的请求（用于断言“错误是否被喂回模型”）
		ArgumentCaptor<ChatCompletionCreateParams> captor = ArgumentCaptor.forClass(ChatCompletionCreateParams.class);

		// 轮次 1：返回 tool_calls（strict_calculator），参数 a 传 string，触发 schema 校验失败
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk("strict_calculator", "{\"a\":\"123\",\"b\":456,\"operator\":\"add\"}")));
		// 轮次 2：再次返回 tool_calls（strict_calculator），这次参数正确
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(toolCallsChunk("strict_calculator", "{\"a\":123,\"b\":456,\"operator\":\"add\"}")));
		// 轮次 3：返回最终回答文本
		StreamResponse<ChatCompletionChunk> r3 = new FakeStreamResponse(Stream.of(textChunk("579")));

		Mockito.when(openAiClient.chat().completions().createStreaming(captor.capture()))
			.thenReturn(r1, r2, r3);

		// 注册一个严格 schema 的工具：要求 a/b 为 integer，operator 只能是 add
		ToolRouter toolRouter = new ToolRouter();
		StrictCalculatorTool strictTool = new StrictCalculatorTool(om);
		toolRouter.register(strictTool);

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);

		AgentStreamingService service = newService(openAiClient, minimaxClient, toolRouter, om, contextManager, persistent, 6);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "calc", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		// 只要流能走到 done，就说明“没有崩溃/没有中断”
		Assertions.assertNotNull(events);
		Assertions.assertTrue(events.stream().anyMatch(event -> "done".equals(event.getType())));
		Assertions.assertEquals("turn_end", events.get(events.size() - 1).getType());

		// 关键断言：第二轮请求的 messages 里应该带有“参数解析失败”的 tool 错误提示
		//          证明第一轮错误没有抛异常终止，而是被包装成 tool message 反馈给模型
		List<ChatCompletionCreateParams> calls = captor.getAllValues();
		Assertions.assertTrue(calls.size() >= 2);
		String secondCallPayload = String.valueOf(calls.get(1));
		Assertions.assertTrue(secondCallPayload.contains("参数解析失败"));
		Assertions.assertTrue(secondCallPayload.contains("a"));
	}

	@Test
	void maxIterationsFallbackPreventsFurtherToolCallsAndReturnsDone() {
		// 场景：模型一上来就想调用工具，但 maxIterations=1（只有“最后一轮”）
		// 期望：触发强制交卷，最后一轮禁用工具调用并直接 done(max_iterations_fallback)
		ObjectMapper om = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		// 模型返回 tool_calls（loop_tool），但由于只有最后一轮，Agent 会直接 fallback 结束
		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk("loop_tool", "{\"q\":\"x\"}")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1);

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new LoopTool(om));

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);

		AgentStreamingService service = newService(openAiClient, minimaxClient, toolRouter, om, contextManager, persistent, 1);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "loop", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);
		LlmStreamEvent doneEvent = events.stream()
			.filter(event -> "done".equals(event.getType()))
			.findFirst()
			.orElse(null);
		Assertions.assertNotNull(doneEvent);
		Assertions.assertEquals("max_iterations_fallback", doneEvent.getFinishReason());
		Assertions.assertEquals("turn_end", events.get(events.size() - 1).getType());
	}

	@Test
	void sessionMemoryStaysCleanAfterToolHeavyTurn() {
		// 场景：一次对话中包含工具调用（且工具输出很长），然后模型给出总结。
		// 期望：Session Memory “清理战场”，最终只保存：
		//      - UserMessage（用户提问）
		//      - AssistantMessage（最终回答）
		//      不保存 ToolCallMessage / ToolResultMessage（避免长期记忆被污染和爆 token）。
		ObjectMapper om = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(toolCallsChunk("read_huge_document", "{\"n\":10000}")));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("总结：这是关于 Java 内存泄漏 的资料")));

		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2);

		ToolRouter toolRouter = new ToolRouter();
		toolRouter.register(new HugeDocTool(om));

		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(100000);
		ctxProps.setKeepLastMessages(100);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(4);
		agentProps.setToolTimeoutSeconds(5);

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		ToolArgumentValidator validator = new ToolArgumentValidator();

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			om,
			systemPromptManager,
			contextManager,
			persistent,
			new LocalExecutionContextRecorder(),
			agentProps,
			validator
		);

		List<LlmStreamEvent> events = service.stream(LlmProvider.OPENAI, "s1", "帮我搜一下关于 Java 内存泄漏 的资料，然后总结", true, LlmToolChoiceMode.AUTO)
			.collectList()
			.block(Duration.ofSeconds(5));

		Assertions.assertNotNull(events);

		// 检查 session context：SYSTEM 固定在第 0 条，且本轮 user/assistant 能进入会话上下文
		List<ChatMessage> msgs = contextManager.getContext("anonymous::s1");
		Assertions.assertFalse(msgs.isEmpty());
		Assertions.assertEquals("SYSTEM", msgs.get(0).getType().name());
		boolean hasUser = false;
		boolean hasAssistant = false;
		for (ChatMessage m : msgs) {
			if (m != null && m.getType() != null) {
				if ("USER".equals(m.getType().name())) {
					hasUser = true;
				}
				if ("ASSISTANT".equals(m.getType().name())) {
					hasAssistant = true;
				}
			}
		}
		Assertions.assertTrue(hasUser);
		Assertions.assertTrue(hasAssistant);
		Assertions.assertTrue(msgs.stream().noneMatch(m -> m != null && "TOOL_CALL".equals(m.getType().name())));
		Assertions.assertTrue(msgs.stream().noneMatch(m -> m != null && "TOOL_RESULT".equals(m.getType().name())));
	}

	@Test
	void preflightCompactsProjectedSessionHistoryBeforeThirdTurn() {
		ObjectMapper om = new ObjectMapper();

		OpenAIClient openAiClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);
		OpenAIClient minimaxClient = Mockito.mock(OpenAIClient.class, Answers.RETURNS_DEEP_STUBS);

		StreamResponse<ChatCompletionChunk> r1 = new FakeStreamResponse(Stream.of(textChunk("answer-1-" + repeat("甲", 100))));
		StreamResponse<ChatCompletionChunk> r2 = new FakeStreamResponse(Stream.of(textChunk("answer-2-" + repeat("乙", 100))));
		StreamResponse<ChatCompletionChunk> r3 = new FakeStreamResponse(Stream.of(textChunk("answer-3")));
		Mockito.when(openAiClient.chat().completions().createStreaming(Mockito.any(ChatCompletionCreateParams.class)))
			.thenReturn(r1, r2, r3);

		ToolRouter toolRouter = new ToolRouter();
		SessionContextProperties ctxProps = new SessionContextProperties();
		ctxProps.setMaxTokens(600);
		ctxProps.setPreflightReserveTokens(180);
		ctxProps.setKeepLastMessages(20);
		TokenCounter tokenCounter = text -> text != null ? text.length() : 0;
		MemoryFlushService memoryFlushService = Mockito.mock(MemoryFlushService.class);
		InMemorySessionContextManager contextManager = new InMemorySessionContextManager(ctxProps, tokenCounter, memoryFlushService);
		PersistentMessageStore persistent = Mockito.mock(PersistentMessageStore.class);
		LocalExecutionContextRecorder recorder = new LocalExecutionContextRecorder();

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(2);
		agentProps.setToolTimeoutSeconds(5);

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		ToolArgumentValidator validator = new ToolArgumentValidator();

		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		AgentStreamingService service = new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			om,
			systemPromptManager,
			contextManager,
			persistent,
			null,
			recorder,
			agentProps,
			validator,
			null,
			memoryFlushService,
			null,
			new SessionContextProjector(),
			new SessionContextPreflightCompactor(
				contextManager,
				new SessionContextBudgetEvaluator(ctxProps, tokenCounter),
				memoryFlushService
			)
		);

		List<LlmStreamEvent> turn1 = service.stream(
			LlmProvider.OPENAI,
			"s1",
			"question-1-" + repeat("甲", 100),
			false,
			LlmToolChoiceMode.NONE
		).collectList().block(Duration.ofSeconds(5));
		List<LlmStreamEvent> turn2 = service.stream(
			LlmProvider.OPENAI,
			"s1",
			"question-2-" + repeat("乙", 100),
			false,
			LlmToolChoiceMode.NONE
		).collectList().block(Duration.ofSeconds(5));
		List<LlmStreamEvent> turn3 = service.stream(
			LlmProvider.OPENAI,
			"s1",
			"question-3",
			false,
			LlmToolChoiceMode.NONE
		).collectList().block(Duration.ofSeconds(5));

		Assertions.assertNotNull(turn1);
		Assertions.assertNotNull(turn2);
		Assertions.assertNotNull(turn3);

		LocalExecutionContextRecorder.LocalExecutionContextSnapshot snapshot = recorder.getLatest("anonymous::s1");
		Assertions.assertNotNull(snapshot);
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith("question-2-")));
		Assertions.assertTrue(snapshot.getMessages().stream().anyMatch(msg -> messageContent(msg).startsWith("answer-2-")));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("question-1-")));
		Assertions.assertTrue(snapshot.getMessages().stream().noneMatch(msg -> messageContent(msg).startsWith("answer-1-")));
		Mockito.verify(memoryFlushService, Mockito.times(1))
			.flushPreCompaction(Mockito.eq("anonymous::s1"), Mockito.anyList());
	}

	private AgentStreamingService newService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		ToolRouter toolRouter,
		ObjectMapper om,
		InMemorySessionContextManager contextManager,
		PersistentMessageStore persistent,
		int maxIterations
	) {
		// 工厂方法：用一套统一配置创建 AgentStreamingService，便于在不同用例里快速调参（maxIterations）
		OpenAiClientProperties openAiProps = new OpenAiClientProperties();
		openAiProps.setModel("gpt-test");
		MinimaxClientProperties minimaxProps = new MinimaxClientProperties();
		minimaxProps.setModel("minimax-test");

		SystemPromptManager systemPromptManager = Mockito.mock(SystemPromptManager.class);
		Mockito.when(systemPromptManager.build(Mockito.any())).thenReturn("system");

		AgentProperties agentProps = new AgentProperties();
		agentProps.setMaxIterations(maxIterations);
		agentProps.setToolTimeoutSeconds(5);

		ToolArgumentValidator validator = new ToolArgumentValidator();

		return new AgentStreamingService(
			openAiClient,
			minimaxClient,
			openAiProps,
			minimaxProps,
			toolRouter,
			om,
			systemPromptManager,
			contextManager,
			persistent,
			new LocalExecutionContextRecorder(),
			agentProps,
			validator
		);
	}

	private static ChatCompletionChunk toolCallsChunk(String toolName, String jsonArgs) {
		// 构造一个“模型返回工具调用”的 chunk：finishReason=TOOL_CALLS
		ChatCompletionChunk.Choice.Delta.ToolCall.Function fn = ChatCompletionChunk.Choice.Delta.ToolCall.Function.builder()
			.name(toolName)
			.arguments(jsonArgs)
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
		// 构造一个“模型返回最终文本”的 chunk：finishReason=STOP
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

	private static String messageContent(ChatMessage message) {
		return message != null && message.getContent() != null ? message.getContent() : "";
	}

	private static String repeat(String value, int times) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < times; i++) {
			sb.append(value);
		}
		return sb.toString();
	}

	private static final class FakeStreamResponse implements StreamResponse<ChatCompletionChunk> {
		// 用最小实现模拟 OpenAI SDK 的 StreamResponse，让 AgentStreamingService 的 streaming 循环可控可测
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

	private static final class StrictCalculatorTool implements Tool {
		// 故障注入工具：严格 schema 的计算器，用来逼迫模型第一次“故意犯错”，触发 schema 校验 -> 自我修正
		private final ObjectMapper om;
		private final AtomicInteger execCount = new AtomicInteger(0);

		private StrictCalculatorTool(ObjectMapper om) {
			this.om = om;
		}

		@Override
		public String name() {
			return "strict_calculator";
		}

		@Override
		public String description() {
			return "Strict calculator for testing.";
		}

		@Override
		public JsonNode parametersSchema() {
			ObjectNode root = om.createObjectNode();
			root.put("type", "object");
			ObjectNode props = root.putObject("properties");
			props.putObject("a").put("type", "integer");
			props.putObject("b").put("type", "integer");
			ObjectNode op = props.putObject("operator");
			op.put("type", "string");
			op.putArray("enum").add("add");
			root.putArray("required").add("a").add("b").add("operator");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			// 注意：当 schema 校验失败时，execute 根本不会被调用，这里只用于“修正后”的成功路径
			execCount.incrementAndGet();
			int a = arguments.get("a").asInt();
			int b = arguments.get("b").asInt();
			return Mono.just(ToolResult.ok(String.valueOf(a + b)));
		}
	}

	private static final class LoopTool implements Tool {
		// 故障注入工具：永远返回“找不到”，用于测试死循环/兜底收敛策略
		private final ObjectMapper om;

		private LoopTool(ObjectMapper om) {
			this.om = om;
		}

		@Override
		public String name() {
			return "loop_tool";
		}

		@Override
		public String description() {
			return "Always fails.";
		}

		@Override
		public JsonNode parametersSchema() {
			ObjectNode root = om.createObjectNode();
			root.put("type", "object");
			ObjectNode props = root.putObject("properties");
			props.putObject("q").put("type", "string");
			root.putArray("required").add("q");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			return Mono.just(ToolResult.ok("未找到机密信息，请尝试其他关键词"));
		}
	}

	private static final class HugeDocTool implements Tool {
		// 故障注入工具：返回超长文本，用于测试“会话上下文不保留工具结果”等策略
		private final ObjectMapper om;

		private HugeDocTool(ObjectMapper om) {
			this.om = om;
		}

		@Override
		public String name() {
			return "read_huge_document";
		}

		@Override
		public String description() {
			return "Returns a huge string.";
		}

		@Override
		public JsonNode parametersSchema() {
			ObjectNode root = om.createObjectNode();
			root.put("type", "object");
			ObjectNode props = root.putObject("properties");
			props.putObject("n").put("type", "integer");
			root.putArray("required").add("n");
			return root;
		}

		@Override
		public Mono<ToolResult> execute(JsonNode arguments, ToolExecutionContext context) {
			int n = arguments != null && arguments.has("n") ? arguments.get("n").asInt(50000) : 50000;
			StringBuilder sb = new StringBuilder();
			sb.append("第一句话。");
			for (int i = 0; i < n; i++) {
				sb.append("中");
			}
			sb.append("最后一句话。");
			return Mono.just(ToolResult.ok(sb.toString()));
		}
	}
}
