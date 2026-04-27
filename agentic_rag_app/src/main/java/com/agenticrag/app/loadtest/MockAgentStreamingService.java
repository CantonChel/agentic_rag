package com.agenticrag.app.loadtest;

import com.agenticrag.app.agent.AgentStreamingService;
import com.agenticrag.app.agent.execution.AgentEvalMode;
import com.agenticrag.app.agent.execution.AgentExecutionControl;
import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.session.SessionScope;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mock AgentStreamingService for load testing.
 * <p>
 * Simulates the agent loop with configurable delay.
 * When LOADTEST_TOOL_MODE=real, actually calls tools via ToolRouter
 * instead of just emitting fake tool events.
 * <p>
 * Environment variables:
 * - LOADTEST_LLM_DELAY_MS: simulated LLM latency per round (default 2000 = 2s)
 * - LOADTEST_LLM_STUCK: if true, LLM never returns (default false)
 * - LOADTEST_AGENT_TOOL_ROUNDS: number of mock tool rounds before answering (default 3)
 * - LOADTEST_TOOL_MODE: "real" to call real tools, "fake" to just emit events (default "fake")
 * - LOADTEST_STREAM_MODE: "final_answer_only" or "early_streaming" (default "final_answer_only")
 */
public class MockAgentStreamingService extends AgentStreamingService {

	private static final Logger log = LoggerFactory.getLogger(MockAgentStreamingService.class);
	private static final long DELAY_MS = Long.parseLong(
		System.getenv().getOrDefault("LOADTEST_LLM_DELAY_MS", "2000"));
	private static final boolean STUCK = Boolean.parseBoolean(
		System.getenv().getOrDefault("LOADTEST_LLM_STUCK", "false"));
	private static final int TOOL_ROUNDS = Integer.parseInt(
		System.getenv().getOrDefault("LOADTEST_AGENT_TOOL_ROUNDS", "3"));
	private static final String TOOL_MODE = System.getenv().getOrDefault("LOADTEST_TOOL_MODE", "fake");
	private static final String STREAM_MODE = System.getenv().getOrDefault("LOADTEST_STREAM_MODE", "final_answer_only");
	private static final boolean USE_REAL_TOOLS = "real".equalsIgnoreCase(TOOL_MODE);
	private static final boolean EARLY_STREAMING = "early_streaming".equalsIgnoreCase(STREAM_MODE);
	private static final String MOCK_ANSWER = "根据知识库中的信息，这是模拟的Agent回复。";

	private final ExecutorService mockStreamExecutor;
	private final SystemPromptManager systemPromptManager;
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final SessionReplayStore sessionReplayStore;
	private final ToolRouter toolRouter;
	private final ToolArgumentValidator toolArgumentValidator;
	private final ObjectMapper objectMapper;
	private final LoadTestMetricsCollector metricsCollector;

	public MockAgentStreamingService(
		com.openai.client.OpenAIClient openAiClient,
		com.openai.client.OpenAIClient minimaxClient,
		com.agenticrag.app.config.OpenAiClientProperties openAiProperties,
		com.agenticrag.app.config.MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		SessionReplayStore sessionReplayStore,
		com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder,
		com.agenticrag.app.agent.AgentProperties agentProperties,
		ToolArgumentValidator toolArgumentValidator,
		com.agenticrag.app.session.SessionManager sessionManager,
		com.agenticrag.app.memory.MemoryLifecycleOrchestrator memoryLifecycleOrchestrator,
		com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService,
		com.agenticrag.app.chat.context.SessionContextProjector sessionContextProjector,
		com.agenticrag.app.chat.context.SessionContextPreflightCompactor sessionContextPreflightCompactor,
		com.agenticrag.app.chat.context.TurnContextAutoCompactor turnContextAutoCompactor,
		ExecutorService mockStreamExecutor,
		LoadTestMetricsCollector metricsCollector
	) {
		super(openAiClient, minimaxClient, openAiProperties, minimaxProperties,
			toolRouter, objectMapper, systemPromptManager, contextManager,
			persistentMessageStore, sessionReplayStore, localExecutionContextRecorder,
			agentProperties, toolArgumentValidator, sessionManager,
			memoryLifecycleOrchestrator, benchmarkTurnExecutionSummaryService,
			sessionContextProjector, sessionContextPreflightCompactor,
			turnContextAutoCompactor);
		this.systemPromptManager = systemPromptManager;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.sessionReplayStore = sessionReplayStore;
		this.toolRouter = toolRouter;
		this.toolArgumentValidator = toolArgumentValidator;
		this.objectMapper = objectMapper;
		this.mockStreamExecutor = mockStreamExecutor;
		this.metricsCollector = metricsCollector;
	}

	@Override
	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode,
		String traceId,
		AgentExecutionControl executionControl
	) {
		return Flux.create(sink -> {
			long submittedAt = System.currentTimeMillis();
			metricsCollector.recordSubmission();
			Future<?> future = mockStreamExecutor.submit(() -> {
				long executionStartAt = System.currentTimeMillis();
				metricsCollector.recordStart(executionStartAt - submittedAt);
				String uid = SessionScope.normalizeUserId(userId);
				String sid = SessionScope.normalizeSessionId(sessionId);
				String scopedSid = SessionScope.scopedSessionId(uid, sid);
				String turnId = UUID.randomUUID().toString();
				AtomicLong sequence = new AtomicLong(0L);
				AtomicLong firstEventTs = new AtomicLong(-1L);
				AtomicLong firstDeltaTs = new AtomicLong(-1L);
				AtomicLong emittedEventCount = new AtomicLong(0L);
				AtomicLong toolCallCount = new AtomicLong(0L);
				AtomicLong maxRound = new AtomicLong(0L);
				java.util.function.LongSupplier nextSeq = () -> sequence.incrementAndGet();
				java.util.function.Consumer<LlmStreamEvent> emit = event -> {
					if (sink != null && event != null) {
						emittedEventCount.incrementAndGet();
						long eventTs = event.getTs() != null ? event.getTs() : System.currentTimeMillis();
						firstEventTs.compareAndSet(-1L, eventTs);
						if ("delta".equals(event.getType())) {
							firstDeltaTs.compareAndSet(-1L, eventTs);
						}
						if (event.getRoundId() != null) {
							maxRound.accumulateAndGet(event.getRoundId(), Math::max);
						}
						sink.next(event);
						if (sessionReplayStore != null) {
							try {
								sessionReplayStore.append(scopedSid, event);
							} catch (Exception ignored) {
							}
						}
					}
				};

				boolean singleTurn = executionControl != null
					&& executionControl.getEvalMode() == AgentEvalMode.SINGLE_TURN;
				Set<String> allowedToolNames = (executionControl != null && !executionControl.isMemoryEnabled())
					? Collections.emptySet() : null;

				// Setup system prompt
				String configuredSystemPrompt = systemPromptManager.build(
					new SystemPromptContext(provider, includeTools, SystemPromptMode.AGENT,
						executionControl != null && executionControl.isMemoryEnabled(), allowedToolNames));
				if (!singleTurn) {
					contextManager.ensureSystemPrompt(scopedSid, configuredSystemPrompt);
				}
				String systemPrompt = singleTurn ? configuredSystemPrompt : contextManager.getSystemPrompt(scopedSid);
				persistentMessageStore.ensureSystemPrompt(scopedSid, systemPrompt);

				UserMessage user = new UserMessage(prompt);
				persistentMessageStore.append(scopedSid, user);
				if (!singleTurn) {
					contextManager.addMessage(scopedSid, user);
				}

				emit.accept(LlmStreamEvent.turnStart(turnId, nextSeq.getAsLong(), System.currentTimeMillis(), sid));

				boolean success = false;
				try {
				if (STUCK) {
					log.info("event=mock_agent_stuck scopedSid={}", scopedSid);
					try {
						Thread.sleep(Long.MAX_VALUE);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				} else {
					for (int round = 1; round <= TOOL_ROUNDS; round++) {
						// Simulate LLM thinking delay
						sleep(DELAY_MS);
						metricsCollector.recordThinkingDuration(DELAY_MS);

						// Emit thinking event
						emit.accept(LlmStreamEvent.thinking(
							"模拟思考: 第" + round + "轮推理",
							"mock_reasoning",
							"mock-model",
							round,
							turnId,
							nextSeq.getAsLong(),
							System.currentTimeMillis()
						));

						// Simulate tool call if not last round and tools enabled
						if (round < TOOL_ROUNDS && includeTools) {
							toolCallCount.incrementAndGet();
							if (USE_REAL_TOOLS) {
								executeRealToolCall(scopedSid, uid, sid, turnId, round, emit, nextSeq);
							} else {
								emitFakeToolCall(turnId, round, emit, nextSeq);
							}
						}
					}

					emitAnswerDeltas(turnId, nextSeq, emit);
				}

				AssistantMessage am = new AssistantMessage(MOCK_ANSWER);
				persistentMessageStore.append(scopedSid, am);
				if (!singleTurn) {
					contextManager.addMessage(scopedSid, am);
				}

				emit.accept(LlmStreamEvent.done("stop", null, turnId, nextSeq.getAsLong(), System.currentTimeMillis(), TOOL_ROUNDS));
				emit.accept(LlmStreamEvent.turnEnd(turnId, nextSeq.getAsLong(), System.currentTimeMillis(), "stop", TOOL_ROUNDS));
				success = true;
				sink.complete();
				} finally {
					long executionMs = System.currentTimeMillis() - executionStartAt;
					metricsCollector.recordCompletion(
						executionMs,
						firstEventTs.get() >= 0 ? firstEventTs.get() - executionStartAt : -1L,
						firstDeltaTs.get() >= 0 ? firstDeltaTs.get() - executionStartAt : -1L,
						(int) maxRound.get(),
						(int) toolCallCount.get(),
						(int) emittedEventCount.get(),
						success
					);
				}
			});
			sink.onCancel(() -> future.cancel(true));
		});
	}

	/**
	 * Execute a real tool call via ToolRouter.
	 * This tests the full tool dispatch path: ToolRouter → Tool.execute → ToolResult
	 */
	private void executeRealToolCall(
		String scopedSid,
		String uid,
		String sid,
		String turnId,
		int round,
		java.util.function.Consumer<LlmStreamEvent> emit,
		java.util.function.LongSupplier nextSeq
	) {
		// Pick "thinking" tool — it's always available and lightweight
		String toolName = "thinking";
		String toolCallId = "call-mock-" + round;

		// Build tool arguments
		ObjectNode args = objectMapper.createObjectNode();
		args.put("thought", "模拟第" + round + "轮思考过程");
		args.put("round", round);

		emit.accept(LlmStreamEvent.toolStart(
			turnId, nextSeq.getAsLong(), System.currentTimeMillis(),
			round, toolCallId, toolName, truncateJson(args, 400)));

		// Record tool call message
		LlmToolCall llmToolCall = new LlmToolCall(toolCallId, toolName, args);
		List<LlmToolCall> toolCalls = new ArrayList<>();
		toolCalls.add(llmToolCall);
		ToolCallMessage tcm = new ToolCallMessage(toolCalls);
		persistentMessageStore.append(scopedSid, tcm);
		contextManager.addMessage(scopedSid, tcm);

		// Execute the real tool
		long toolStartNs = System.nanoTime();
		ToolResult toolResult = null;
		Exception toolFailure = null;

		try {
			Tool tool = toolRouter.getTool(toolName).orElse(null);
			if (tool == null) {
				toolResult = ToolResult.error("Tool not found: " + toolName);
			} else {
				ToolArgumentValidator.ValidationResult vr = toolArgumentValidator.validate(tool.parametersSchema(), args);
				if (!vr.isOk()) {
					toolResult = ToolResult.error("参数校验失败: " + String.join("; ", vr.getErrors()));
				} else {
					toolResult = tool.execute(args, new ToolExecutionContext(
						toolCallId, uid, sid, "loadtest", null, toolCallId
					)).block(Duration.ofSeconds(10));
				}
			}
		} catch (Exception e) {
			toolFailure = e;
			toolResult = ToolResult.error("Tool execution failed: " + e.getMessage());
		}

		if (toolResult == null) {
			toolResult = ToolResult.error("Tool returned null");
		}

		long durationMs = (System.nanoTime() - toolStartNs) / 1_000_000;
		metricsCollector.recordToolDuration(durationMs);
		String status = toolResult.isSuccess() ? "success" : "error";

		// Emit thinking event for thinking tool result
		if (toolResult.isSuccess() && toolResult.getOutput() != null) {
			String thought = toolResult.getOutput();
			if (!thought.trim().isEmpty()) {
				emit.accept(LlmStreamEvent.thinking(
					thought, "thinking_tool", "mock-model",
					round, turnId, nextSeq.getAsLong(), System.currentTimeMillis()));
				persistentMessageStore.append(scopedSid, new ThinkingMessage(thought));
			}
		}

		emit.accept(LlmStreamEvent.toolEnd(
			turnId, nextSeq.getAsLong(), System.currentTimeMillis(),
			round, toolCallId, toolName, status, durationMs,
			truncateResult(toolResult), toolResult.getError()));

		// Record tool result message
		ToolResultMessage trm = new ToolResultMessage(
			toolName, toolCallId, toolResult.isSuccess(),
			toolResult.getOutput(), toolResult.getError());
		persistentMessageStore.append(scopedSid, trm);
		contextManager.addMessage(scopedSid, trm);

		log.debug("event=mock_tool_executed tool={} status={} durationMs={}", toolName, status, durationMs);
	}

	/**
	 * Emit fake tool call events (no real tool execution).
	 */
	private void emitFakeToolCall(
		String turnId,
		int round,
		java.util.function.Consumer<LlmStreamEvent> emit,
		java.util.function.LongSupplier nextSeq
	) {
		String toolCallId = "call-mock-" + round;
		String toolName = "thinking";

		emit.accept(LlmStreamEvent.toolStart(
			turnId, nextSeq.getAsLong(), System.currentTimeMillis(),
			round, toolCallId, toolName, null));

		sleep(Math.max(50, DELAY_MS / 4));
		metricsCollector.recordToolDuration(50L);

		emit.accept(LlmStreamEvent.toolEnd(
			turnId, nextSeq.getAsLong(), System.currentTimeMillis(),
			round, toolCallId, toolName, "success", 50L, null, null));
	}

	private void emitAnswerDeltas(
		String turnId,
		java.util.function.LongSupplier nextSeq,
		java.util.function.Consumer<LlmStreamEvent> emit
	) {
		String[] chunks = MOCK_ANSWER.split("，");
		if (!EARLY_STREAMING) {
			sleep(DELAY_MS / 2);
			emitChunks(chunks, turnId, nextSeq, emit, 0L);
			return;
		}

		long firstChunkDelayMs = Math.max(100L, DELAY_MS / 10);
		long interChunkDelayMs = Math.max(120L, DELAY_MS / 8);
		sleep(firstChunkDelayMs);
		emitChunks(chunks, turnId, nextSeq, emit, interChunkDelayMs);
		sleep(Math.max(80L, DELAY_MS / 10));
	}

	private void emitChunks(
		String[] chunks,
		String turnId,
		java.util.function.LongSupplier nextSeq,
		java.util.function.Consumer<LlmStreamEvent> emit,
		long betweenChunkDelayMs
	) {
		for (int i = 0; i < chunks.length; i++) {
			String chunk = chunks[i];
			if (i < chunks.length - 1) {
				chunk += "，";
			}
			emit.accept(LlmStreamEvent.delta(chunk, turnId, nextSeq.getAsLong(), System.currentTimeMillis(), TOOL_ROUNDS));
			if (betweenChunkDelayMs > 0 && i < chunks.length - 1) {
				sleep(betweenChunkDelayMs);
			}
		}
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private JsonNode truncateJson(JsonNode node, int maxChars) {
		if (node == null) return null;
		String raw = node.toString();
		if (raw.length() <= maxChars) return node;
		return objectMapper.createObjectNode().put("_preview", raw.substring(0, maxChars) + "...");
	}

	private JsonNode truncateResult(ToolResult result) {
		if (result == null || !result.isSuccess()) return null;
		String output = result.getOutput();
		if (output == null || output.trim().isEmpty()) return null;
		try {
			return objectMapper.createObjectNode().put("text", output.substring(0, Math.min(output.length(), 500)));
		} catch (Exception e) {
			return null;
		}
	}
}
