package com.agenticrag.app.agent;

import com.agenticrag.app.agent.execution.AgentEvalMode;
import com.agenticrag.app.agent.execution.AgentExecutionControl;
import com.agenticrag.app.agent.execution.AgentThinkingProfile;
import com.agenticrag.app.agent.execution.AgentTurnExecutionAccumulator;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService;
import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.llm.MinimaxReasoningSupport;
import com.agenticrag.app.memory.MemoryFlushService;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.session.ChatSession;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.session.SessionScope;
import com.agenticrag.app.trace.TraceIdUtil;
import com.agenticrag.app.tool.Tool;
import com.agenticrag.app.tool.ToolDefinition;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

@Service
public class AgentStreamingService {
	private static final Logger log = LoggerFactory.getLogger(AgentStreamingService.class);
	private final OpenAIClient openAiClient;
	private final OpenAIClient minimaxClient;
	private final OpenAiClientProperties openAiProperties;
	private final MinimaxClientProperties minimaxProperties;
	private final ToolRouter toolRouter;
	private final ObjectMapper objectMapper;
	private final ExecutorService streamExecutor;
	private final SystemPromptManager systemPromptManager;
	private final ContextManager contextManager;
	private final PersistentMessageStore persistentMessageStore;
	private final SessionReplayStore sessionReplayStore;
	private final com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder;
	private final AgentProperties agentProperties;
	private final ToolArgumentValidator toolArgumentValidator;
	private final SessionManager sessionManager;
	private final MemoryFlushService memoryFlushService;
	private final BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService;
	private static final Pattern STEP_PATTERN = Pattern.compile("(?m)^(\\s*(步骤\\s*\\d+\\.?|Step\\s*\\d+\\.?|\\d+\\.)\\s+)");
	private static final String THINK_OPEN = "<think>";
	private static final String THINK_CLOSE = "</think>";

	public AgentStreamingService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder,
		AgentProperties agentProperties,
		ToolArgumentValidator toolArgumentValidator
	) {
		this(
			openAiClient,
			minimaxClient,
			openAiProperties,
			minimaxProperties,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistentMessageStore,
			null,
			localExecutionContextRecorder,
			agentProperties,
			toolArgumentValidator,
			null,
			null,
			null
		);
	}

	public AgentStreamingService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder,
		AgentProperties agentProperties,
		ToolArgumentValidator toolArgumentValidator,
		BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService
	) {
		this(
			openAiClient,
			minimaxClient,
			openAiProperties,
			minimaxProperties,
			toolRouter,
			objectMapper,
			systemPromptManager,
			contextManager,
			persistentMessageStore,
			null,
			localExecutionContextRecorder,
			agentProperties,
			toolArgumentValidator,
			null,
			null,
			benchmarkTurnExecutionSummaryService
		);
	}

	@Autowired
	public AgentStreamingService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		SessionReplayStore sessionReplayStore,
		com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder,
		AgentProperties agentProperties,
		ToolArgumentValidator toolArgumentValidator,
		SessionManager sessionManager,
		MemoryFlushService memoryFlushService,
		BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService
	) {
		this.openAiClient = openAiClient;
		this.minimaxClient = minimaxClient;
		this.openAiProperties = openAiProperties;
		this.minimaxProperties = minimaxProperties;
		this.toolRouter = toolRouter;
		this.objectMapper = objectMapper;
		this.streamExecutor = Executors.newCachedThreadPool();
		this.systemPromptManager = systemPromptManager;
		this.contextManager = contextManager;
		this.persistentMessageStore = persistentMessageStore;
		this.sessionReplayStore = sessionReplayStore;
		this.localExecutionContextRecorder = localExecutionContextRecorder;
		this.agentProperties = agentProperties;
		this.toolArgumentValidator = toolArgumentValidator;
		this.sessionManager = sessionManager;
		this.memoryFlushService = memoryFlushService;
		this.benchmarkTurnExecutionSummaryService = benchmarkTurnExecutionSummaryService;
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		return stream(
			provider,
			"anonymous",
			sessionId,
			prompt,
			includeTools,
			toolChoiceMode,
			null,
			AgentExecutionControl.defaults(null)
		);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		return stream(
			provider,
			userId,
			sessionId,
			prompt,
			includeTools,
			toolChoiceMode,
			null,
			AgentExecutionControl.defaults(null)
		);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode,
		String traceId
	) {
		return stream(
			provider,
			userId,
			sessionId,
			prompt,
			includeTools,
			toolChoiceMode,
			traceId,
			AgentExecutionControl.defaults(null)
		);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode,
		String traceId,
		String knowledgeBaseId
	) {
		return stream(provider, userId, sessionId, prompt, includeTools, toolChoiceMode, traceId, AgentExecutionControl.defaults(knowledgeBaseId));
	}

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
		String uid = SessionScope.normalizeUserId(userId);
		String sid = SessionScope.normalizeSessionId(sessionId);
		String scopedSid = SessionScope.scopedSessionId(uid, sid);
		String effectiveTraceId = TraceIdUtil.normalizeOrGenerate(traceId);
		AgentExecutionControl control = executionControl != null ? executionControl : AgentExecutionControl.defaults(null);
		String scopedKnowledgeBaseId = normalizeKnowledgeBaseId(control.getKnowledgeBaseId());
		boolean singleTurn = control.getEvalMode() == AgentEvalMode.SINGLE_TURN;
		boolean thinkingVisible = control.getThinkingProfile() != AgentThinkingProfile.HIDE;
		Set<String> allowedToolNames = resolveAllowedToolNames(includeTools, control.isMemoryEnabled());
		if (singleTurn && isSessionSwitchCommand(prompt)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "session switch command is not allowed in single_turn mode");
		}
		int maxIterations = agentProperties.getMaxIterations() > 0 ? agentProperties.getMaxIterations() : 6;
		long toolTimeoutSeconds = agentProperties.getToolTimeoutSeconds() > 0 ? agentProperties.getToolTimeoutSeconds() : 30;
		log.info(
			"event=agent_stream_start traceId={} provider={} userId={} sessionId={} knowledgeBaseId={} buildId={} kbScope={} evalMode={} thinkingProfile={} memoryEnabled={} includeTools={} toolChoice={} promptChars={}",
			effectiveTraceId,
			provider,
			uid,
			sid,
			scopedKnowledgeBaseId,
			control.getBuildId(),
			control.getKbScope(),
			control.getEvalMode(),
			control.getThinkingProfile(),
			control.isMemoryEnabled(),
			includeTools,
			toolChoiceMode,
			prompt != null ? prompt.length() : 0
		);

		return Flux.create(sink -> {
			AtomicBoolean cancelled = new AtomicBoolean(false);
			Future<?> future = streamExecutor.submit(() -> {
				String turnId = UUID.randomUUID().toString();
				Instant turnCreatedAt = Instant.now();
				long turnStartNs = System.nanoTime();
				String initialModel = provider == LlmProvider.MINIMAX ? minimaxProperties.getModel() : openAiProperties.getModel();
				AgentTurnExecutionAccumulator executionAccumulator = new AgentTurnExecutionAccumulator(
					turnId,
					sid,
					uid,
					effectiveTraceId,
					provider != null ? provider.name() : null,
					initialModel,
					control,
					prompt,
					turnCreatedAt
				);
				AtomicLong sequence = new AtomicLong(0L);
				LongSupplier nextSequence = () -> sequence.incrementAndGet();
				java.util.function.Consumer<LlmStreamEvent> emit = event -> emitStreamEvent(sink, scopedSid, event);
				AtomicBoolean turnEnded = new AtomicBoolean(false);
				AtomicReference<String> turnFinishReasonRef = new AtomicReference<>("completed");
				AtomicReference<Integer> turnRoundIdRef = new AtomicReference<>(null);
				AtomicReference<String> turnErrorMessageRef = new AtomicReference<>(null);
				java.util.function.BiConsumer<String, Integer> emitTurnEnd = (reason, roundId) -> {
					if (turnEnded.compareAndSet(false, true)) {
						emit.accept(LlmStreamEvent.turnEnd(
							turnId,
							nextSequence.getAsLong(),
							System.currentTimeMillis(),
							reason,
							roundId
						));
					}
				};

				emit.accept(LlmStreamEvent.turnStart(turnId, nextSequence.getAsLong(), System.currentTimeMillis(), sid));
				try {
					throwIfCancelled(sink, cancelled);
					OpenAIClient client = provider == LlmProvider.MINIMAX ? minimaxClient : openAiClient;
					String model = initialModel;
					String configuredSystemPrompt = systemPromptManager.build(
						new SystemPromptContext(provider, includeTools, SystemPromptMode.AGENT, control.isMemoryEnabled(), allowedToolNames)
					);
					OpenAiMessageAdapter adapter = new OpenAiMessageAdapter(objectMapper);

					if (isSessionSwitchCommand(prompt)) {
						log.info(
							"event=agent_session_switch traceId={} provider={} userId={} sessionId={} command={}",
							effectiveTraceId,
							provider,
							uid,
							sid,
							normalizeSwitchReason(prompt)
						);
						if (control.isMemoryEnabled() && memoryFlushService != null) {
							memoryFlushService.flushOnSessionSwitchCommand(
								scopedSid,
								normalizeSwitchReason(prompt),
								contextManager.getContext(scopedSid),
								persistentMessageStore.list(scopedSid)
							);
						}
						String newSessionId = createNextSessionId(uid);
						emit.accept(LlmStreamEvent.sessionSwitched(newSessionId, turnId, nextSequence.getAsLong(), System.currentTimeMillis()));
						emit.accept(LlmStreamEvent.done("session_switched", null, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), null));
						turnFinishReasonRef.set("session_switched");
						emitTurnEnd.accept("session_switched", null);
						executionAccumulator.markCompleted(
							"session_switched",
							(System.nanoTime() - turnStartNs) / 1_000_000,
							null,
							Instant.now()
						);
						persistTurnExecutionSummary(executionAccumulator);
						sink.complete();
						return;
					}

					String systemPrompt = configuredSystemPrompt != null ? configuredSystemPrompt : "";
					if (!singleTurn) {
						contextManager.ensureSystemPrompt(scopedSid, configuredSystemPrompt);
						systemPrompt = contextManager.getSystemPrompt(scopedSid);
					}
					persistentMessageStore.ensureSystemPrompt(scopedSid, systemPrompt);

					List<ChatMessage> localContext = new ArrayList<>();
					if (!singleTurn) {
						List<ChatMessage> sessionContext = contextManager.getContext(scopedSid);
						if (sessionContext != null && !sessionContext.isEmpty()) {
							for (int i = 1; i < sessionContext.size(); i++) {
								localContext.add(sessionContext.get(i));
							}
						}
					}

					ChatMessage userMsg = new UserMessage(prompt);
					localContext.add(userMsg);
					persistentMessageStore.append(scopedSid, userMsg);
					if (!singleTurn) {
						contextManager.addMessage(scopedSid, userMsg);
					}

					boolean finished = false;
					int iteration = 0;

					while (!finished && iteration < maxIterations) {
						throwIfCancelled(sink, cancelled);
						iteration++;
						final int iterationFinal = iteration;
						turnRoundIdRef.set(iterationFinal);
						boolean isFinalIteration = iteration >= maxIterations;

						localExecutionContextRecorder.record(scopedSid, systemPrompt, localContext, iteration);

						ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
							.model(model)
							.addSystemMessage(systemPrompt);
						if (thinkingVisible) {
							MinimaxReasoningSupport.applyReasoningSplit(paramsBuilder, provider, minimaxProperties);
						}

						if (isFinalIteration) {
							paramsBuilder.addSystemMessage("系统警告：你已达到最大思考步数。请立即停止调用新工具，基于目前已有的观察结果，向用户输出最终总结回复。");
						}

						List<com.openai.models.chat.completions.ChatCompletionMessageParam> messageParams = adapter.toMessageParams(localContext);
						for (com.openai.models.chat.completions.ChatCompletionMessageParam mp : messageParams) {
							paramsBuilder.addMessage(mp);
						}

						boolean allowTools = includeTools && !isFinalIteration;
						if (allowTools) {
							paramsBuilder.tools(buildChatCompletionTools(toolRouter.getToolDefinitions(allowedToolNames)));
							paramsBuilder.toolChoice(toToolChoice(toolChoiceMode));
						} else {
							paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
						}

						ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator(objectMapper);
						StringBuilder assistantContent = new StringBuilder();
						StringBuilder reasoningBuffer = new StringBuilder();
						StringBuilder inlineThinkBuffer = new StringBuilder();
						ThinkTagParser thinkTagParser = new ThinkTagParser();
						String finishReason = null;
						boolean hasToolThinking = false;
						AtomicBoolean inlineThinkingEmitted = new AtomicBoolean(false);
						AtomicBoolean structuredReasoningEmitted = new AtomicBoolean(false);
						AtomicReference<String> originModelRef = new AtomicReference<>(model);
						AtomicReference<String> reasoningSourceRef = new AtomicReference<>(MinimaxReasoningSupport.SOURCE_REASONING_FIELD);

						try (StreamResponse<ChatCompletionChunk> streamResponse = client.chat().completions().createStreaming(paramsBuilder.build())) {
							outer:
							for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) streamResponse.stream()::iterator) {
								throwIfCancelled(sink, cancelled);
								if (chunk == null || chunk.choices() == null) {
									continue;
								}
								if (chunk.model() != null && !chunk.model().isEmpty()) {
									originModelRef.set(chunk.model());
									executionAccumulator.recordOriginModel(chunk.model());
								}
								for (ChatCompletionChunk.Choice choice : chunk.choices()) {
									throwIfCancelled(sink, cancelled);
									if (choice == null || choice.delta() == null) {
										continue;
									}
									ChatCompletionChunk.Choice.Delta delta = choice.delta();

									if (delta.content().isPresent()) {
										String content = delta.content().get();
										if (content != null && !content.isEmpty()) {
											thinkTagParser.accept(
												content,
												answerPart -> {
													assistantContent.append(answerPart);
														emit.accept(LlmStreamEvent.delta(
															answerPart,
															turnId,
															nextSequence.getAsLong(),
															System.currentTimeMillis(),
															iterationFinal
													));
												},
												thinkPart -> {
													inlineThinkBuffer.append(thinkPart);
													inlineThinkingEmitted.set(true);
													if (!thinkingVisible) {
														return;
													}
														emit.accept(LlmStreamEvent.thinking(
															thinkPart,
															"assistant_content",
															originModelRef.get(),
														iterationFinal,
														turnId,
														nextSequence.getAsLong(),
														System.currentTimeMillis()
													));
												}
											);
										}
									}

									boolean hasToolCallsInDelta = delta.toolCalls().isPresent() && delta.toolCalls().get() != null;
									if (hasToolCallsInDelta) {
										toolCallAccumulator.append(delta.toolCalls().get());
									}
									String reasoningPart = appendReasoningFromDelta(delta, reasoningBuffer, reasoningSourceRef);
									if (reasoningPart != null && !reasoningPart.isEmpty() && !hasToolCallsInDelta) {
										structuredReasoningEmitted.set(true);
										if (!thinkingVisible) {
											continue;
										}
										emit.accept(LlmStreamEvent.thinking(
											reasoningPart,
											reasoningSourceRef.get(),
											originModelRef.get(),
											iterationFinal,
											turnId,
											nextSequence.getAsLong(),
											System.currentTimeMillis()
										));
									}

									if (choice.finishReason().isPresent() && choice.finishReason().get() != null) {
										finishReason = choice.finishReason().get().toString();
										break outer;
									}
								}
							}
						}

						List<LlmToolCall> toolCalls = toolCallAccumulator.buildToolCalls();
						String assistantFinal = assistantContent.toString().trim();
						executionAccumulator.recordOriginModel(originModelRef.get());

						if (!assistantFinal.isEmpty()) {
							executionAccumulator.recordFinalAnswer(assistantFinal);
							AssistantMessage am = new AssistantMessage(assistantFinal);
							localContext.add(am);
							persistentMessageStore.append(scopedSid, am);
							if (!singleTurn) {
								contextManager.addMessage(scopedSid, am);
							}
						}

						if (isFinalIteration) {
							emit.accept(LlmStreamEvent.done(
								"max_iterations_fallback",
								null,
								turnId,
								nextSequence.getAsLong(),
								System.currentTimeMillis(),
								iterationFinal
							));
							turnFinishReasonRef.set("max_iterations_fallback");
							emitTurnEnd.accept("max_iterations_fallback", iterationFinal);
							finished = true;
							break;
						}

						if (toolCalls == null || toolCalls.isEmpty()) {
							emitThinkingIfNeeded(
								false,
								structuredReasoningEmitted.get(),
								reasoningBuffer,
								reasoningSourceRef,
								inlineThinkBuffer,
								assistantFinal,
								originModelRef.get(),
								iterationFinal,
								sink,
								scopedSid,
								inlineThinkingEmitted.get(),
								turnId,
								nextSequence,
								thinkingVisible
							);
							String doneReason = finishReason != null ? finishReason : "stop";
							emit.accept(LlmStreamEvent.done(
								doneReason,
								null,
								turnId,
								nextSequence.getAsLong(),
								System.currentTimeMillis(),
								iterationFinal
							));
							turnFinishReasonRef.set(doneReason);
							emitTurnEnd.accept(doneReason, iterationFinal);
							finished = true;
							break;
						}

						ToolCallMessage tcm = new ToolCallMessage(toolCalls);
						localContext.add(tcm);
						persistentMessageStore.append(scopedSid, tcm);
						if (!singleTurn) {
							contextManager.addMessage(scopedSid, tcm);
						}

						for (LlmToolCall call : toolCalls) {
							throwIfCancelled(sink, cancelled);
							if (call == null) {
								continue;
							}
							String toolName = call.getName();
							String callId = call.getId();
							if (callId == null || callId.trim().isEmpty()) {
								callId = UUID.randomUUID().toString();
							}
							final String toolCallId = callId;
							final JsonNode toolArgs = call.getArguments();
							long toolStartNs = System.nanoTime();
							emit.accept(LlmStreamEvent.toolStart(
								turnId,
								nextSequence.getAsLong(),
								System.currentTimeMillis(),
								iterationFinal,
								toolCallId,
								toolName,
								toPreviewJson(toolArgs, 400)
							));
							log.info(
								"event=tool_call_start traceId={} provider={} userId={} sessionId={} iteration={} tool={} toolCallId={} argsChars={}",
								effectiveTraceId,
								provider,
								uid,
								sid,
								iterationFinal,
								toolName,
								toolCallId,
								toolArgs != null ? toolArgs.toString().length() : 0
							);

							ToolResult toolResult = null;
							Exception toolFailure = null;
							try {
								if (!isToolAllowed(allowedToolNames, toolName)) {
									toolResult = ToolResult.error("Tool disabled: " + toolName);
								} else {
									toolResult = toolRouter.getTool(toolName)
										.map(t -> {
											ToolArgumentValidator.ValidationResult vr = toolArgumentValidator.validate(t.parametersSchema(), toolArgs);
											if (!vr.isOk()) {
												String err = "Error: 参数解析失败。请检查并重新调用工具。细节: " + String.join("; ", vr.getErrors());
												return ToolResult.error(err);
											}
											return t.execute(toolArgs, new ToolExecutionContext(
												toolCallId,
												uid,
												sid,
												effectiveTraceId,
												scopedKnowledgeBaseId,
												toolCallId
											))
												.block(Duration.ofSeconds(toolTimeoutSeconds));
										})
										.orElse(ToolResult.error("Tool not found: " + toolName));
								}
							} catch (Exception e) {
								toolFailure = e;
								toolResult = ToolResult.error("Tool execution failed: " + toolName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
							}

							if (toolResult == null) {
								toolResult = ToolResult.error("Tool execution returned null: " + toolName);
							}
							long toolDurationMs = (System.nanoTime() - toolStartNs) / 1_000_000;
							log.info(
								"event=tool_call_end traceId={} provider={} userId={} sessionId={} iteration={} tool={} toolCallId={} success={} durationMs={} outputChars={} error={}",
								effectiveTraceId,
								provider,
								uid,
								sid,
								iterationFinal,
								toolName,
								toolCallId,
								toolResult.isSuccess(),
								toolDurationMs,
								toolResult.getOutput() != null ? toolResult.getOutput().length() : 0,
								toolResult.getError()
							);
							String toolStatus = resolveToolStatus(toolResult, toolFailure);
							executionAccumulator.recordToolResult(
								toolCallId,
								toolName,
								toolStatus,
								toolDurationMs,
								toolResult.getError()
							);
							executionAccumulator.recordRetrievalSidecar(toolResult.getSidecar(), toolCallId, toolName);
							emit.accept(LlmStreamEvent.toolEnd(
								turnId,
								nextSequence.getAsLong(),
								System.currentTimeMillis(),
								iterationFinal,
								toolCallId,
								toolName,
								toolStatus,
								toolDurationMs,
								toPreviewResult(toolResult),
								toolResult.getError()
							));

							if ("thinking".equals(toolName) && toolResult.isSuccess()) {
								String thought = toolResult.getOutput();
								if (thought != null && !thought.trim().isEmpty()) {
									if (thinkingVisible) {
										emit.accept(LlmStreamEvent.thinking(
											thought,
											"thinking_tool",
										originModelRef.get(),
										iterationFinal,
										turnId,
										nextSequence.getAsLong(),
										System.currentTimeMillis()
									));
									}
									recordThinkingMessage(scopedSid, thought, thinkingVisible);
									hasToolThinking = true;
								}
							}

							ToolResultMessage trm = new ToolResultMessage(
								toolName,
								toolCallId,
								toolResult.isSuccess(),
								toolResult.getOutput(),
								toolResult.getError()
							);
							localContext.add(trm);
							persistentMessageStore.append(scopedSid, trm);
							if (!singleTurn) {
								contextManager.addMessage(scopedSid, trm);
							}
						}

						if (!hasToolThinking) {
							emitThinkingIfNeeded(
								false,
								structuredReasoningEmitted.get(),
								reasoningBuffer,
								reasoningSourceRef,
								inlineThinkBuffer,
								assistantFinal,
								originModelRef.get(),
								iterationFinal,
								sink,
								scopedSid,
								inlineThinkingEmitted.get(),
								turnId,
								nextSequence,
								thinkingVisible
							);
						}
					}

					if (!finished) {
						emit.accept(LlmStreamEvent.done(
							"max_iterations",
							null,
							turnId,
							nextSequence.getAsLong(),
							System.currentTimeMillis(),
							turnRoundIdRef.get()
						));
						turnFinishReasonRef.set("max_iterations");
						emitTurnEnd.accept("max_iterations", turnRoundIdRef.get());
					}
					log.info(
						"event=agent_stream_complete traceId={} provider={} userId={} sessionId={} finished={} maxIterations={}",
						effectiveTraceId,
						provider,
						uid,
						sid,
						finished,
						maxIterations
					);
				} catch (CancellationException e) {
					log.info(
						"event=agent_stream_cancelled traceId={} provider={} userId={} sessionId={} message={}",
						effectiveTraceId,
						provider,
						uid,
						sid,
						e.getMessage()
					);
					turnFinishReasonRef.set("cancelled");
				} catch (UnauthorizedException e) {
					log.warn(
						"event=agent_stream_error traceId={} provider={} userId={} sessionId={} type=UnauthorizedException message={}",
						effectiveTraceId,
						provider,
						uid,
						sid,
						e.getMessage()
					);
					String err = "Unauthorized: check API key for provider=" + provider + " (401)";
					turnErrorMessageRef.set(err);
					emit.accept(LlmStreamEvent.error(err, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), turnRoundIdRef.get()));
					turnFinishReasonRef.set("error");
					emitTurnEnd.accept("error", turnRoundIdRef.get());
				} catch (OpenAIException e) {
					log.warn(
						"event=agent_stream_error traceId={} provider={} userId={} sessionId={} type={} message={}",
						effectiveTraceId,
						provider,
						uid,
						sid,
						e.getClass().getSimpleName(),
						e.getMessage()
					);
					String err = "OpenAI SDK error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
					turnErrorMessageRef.set(err);
					emit.accept(LlmStreamEvent.error(err, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), turnRoundIdRef.get()));
					turnFinishReasonRef.set("error");
					emitTurnEnd.accept("error", turnRoundIdRef.get());
				} catch (Exception e) {
					if (isCancellationException(cancelled, e)) {
						log.info(
							"event=agent_stream_cancelled traceId={} provider={} userId={} sessionId={} type={} message={}",
							effectiveTraceId,
							provider,
							uid,
							sid,
							e.getClass().getSimpleName(),
							e.getMessage()
						);
						turnFinishReasonRef.set("cancelled");
					} else {
						log.warn(
							"event=agent_stream_error traceId={} provider={} userId={} sessionId={} type={} message={}",
							effectiveTraceId,
							provider,
							uid,
							sid,
							e.getClass().getSimpleName(),
							e.getMessage()
						);
						String err = "Agent loop failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
						turnErrorMessageRef.set(err);
						emit.accept(LlmStreamEvent.error(err, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), turnRoundIdRef.get()));
						turnFinishReasonRef.set("error");
						emitTurnEnd.accept("error", turnRoundIdRef.get());
					}
				}

				String summaryFinishReason = resolveSummaryFinishReason(cancelled, turnEnded, turnFinishReasonRef.get());
				executionAccumulator.markCompleted(
					summaryFinishReason,
					(System.nanoTime() - turnStartNs) / 1_000_000,
					turnErrorMessageRef.get(),
					Instant.now()
				);
				persistTurnExecutionSummary(executionAccumulator);
				if (!isCancelled(sink, cancelled)) {
					emitTurnEnd.accept(turnFinishReasonRef.get(), turnRoundIdRef.get());
					sink.complete();
				}
			});

			sink.onCancel(() -> {
				cancelled.set(true);
				future.cancel(true);
			});
		});
	}

	private ChatCompletionToolChoiceOption.Auto toToolChoice(LlmToolChoiceMode mode) {
		if (mode == null) {
			return ChatCompletionToolChoiceOption.Auto.AUTO;
		}
		if (mode == LlmToolChoiceMode.REQUIRED) {
			return ChatCompletionToolChoiceOption.Auto.REQUIRED;
		}
		if (mode == LlmToolChoiceMode.NONE) {
			return ChatCompletionToolChoiceOption.Auto.NONE;
		}
		return ChatCompletionToolChoiceOption.Auto.AUTO;
	}

	private boolean isSessionSwitchCommand(String prompt) {
		if (prompt == null) {
			return false;
		}
		String normalized = prompt.trim().toLowerCase();
		return "/new".equals(normalized) || "/reset".equals(normalized);
	}

	private String normalizeSwitchReason(String prompt) {
		if (prompt == null) {
			return "command:/new";
		}
		String normalized = prompt.trim().toLowerCase();
		if ("/reset".equals(normalized)) {
			return "command:/reset";
		}
		return "command:/new";
	}

	private String normalizeKnowledgeBaseId(String knowledgeBaseId) {
		if (knowledgeBaseId == null) {
			return null;
		}
		String normalized = knowledgeBaseId.trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String createNextSessionId(String userId) {
		if (sessionManager != null) {
			ChatSession created = sessionManager.create(userId);
			if (created != null && created.getId() != null && !created.getId().trim().isEmpty()) {
				return created.getId().trim();
			}
		}
		return UUID.randomUUID().toString();
	}

	private List<ChatCompletionTool> buildChatCompletionTools(Iterable<ToolDefinition> toolDefinitions) {
		List<ChatCompletionTool> tools = new ArrayList<>();
		for (ToolDefinition definition : toolDefinitions) {
			FunctionParameters parameters = FunctionParameters.builder()
				.putAllAdditionalProperties(asJsonValueMap(definition.getParametersSchema()))
				.build();

			FunctionDefinition functionDefinition = FunctionDefinition.builder()
				.name(definition.getName())
				.description(definition.getDescription())
				.parameters(parameters)
				.build();

			ChatCompletionFunctionTool functionTool = ChatCompletionFunctionTool.builder()
				.type(JsonValue.from("function"))
				.function(functionDefinition)
				.build();

			tools.add(ChatCompletionTool.ofFunction(functionTool));
		}
		return tools;
	}

	private Map<String, JsonValue> asJsonValueMap(Object schema) {
		if (schema == null) {
			return Collections.emptyMap();
		}
		try {
			JsonNode node = objectMapper.valueToTree(schema);
			if (node == null || !node.isObject()) {
				return Collections.emptyMap();
			}
			Map<String, JsonValue> out = new HashMap<>();
			node.fields().forEachRemaining(e -> out.put(e.getKey(), JsonValue.from(e.getValue())));
			return out;
		} catch (Exception ignored) {
			return Collections.emptyMap();
		}
	}

	private static final class ToolCallAccumulator {
		private final ObjectMapper objectMapper;
		private final Map<Integer, ToolCallBuilder> buildersByIndex = new HashMap<>();

		private ToolCallAccumulator(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
		}

		private void append(List<ChatCompletionChunk.Choice.Delta.ToolCall> toolCalls) {
			for (ChatCompletionChunk.Choice.Delta.ToolCall toolCall : toolCalls) {
				if (toolCall == null) {
					continue;
				}
				int index = Math.toIntExact(toolCall.index());
				ToolCallBuilder builder = buildersByIndex.get(index);
				if (builder == null) {
					builder = new ToolCallBuilder();
					buildersByIndex.put(index, builder);
				}

				if (toolCall.id().isPresent() && toolCall.id().get() != null) {
					builder.id.append(toolCall.id().get());
				}
				if (toolCall.function().isPresent() && toolCall.function().get() != null) {
					ChatCompletionChunk.Choice.Delta.ToolCall.Function function = toolCall.function().get();
					if (function.name().isPresent() && function.name().get() != null) {
						builder.name.append(function.name().get());
					}
					if (function.arguments().isPresent() && function.arguments().get() != null) {
						builder.arguments.append(function.arguments().get());
					}
				}
			}
		}

		private List<LlmToolCall> buildToolCalls() {
			if (buildersByIndex.isEmpty()) {
				return null;
			}
			List<Integer> indices = new ArrayList<>(buildersByIndex.keySet());
			Collections.sort(indices);

			List<LlmToolCall> out = new ArrayList<>();
			for (Integer idx : indices) {
				ToolCallBuilder b = buildersByIndex.get(idx);
				if (b == null) {
					continue;
				}

				JsonNode args = null;
				String argsRaw = b.arguments.toString();
				if (argsRaw != null && !argsRaw.trim().isEmpty()) {
					try {
						args = objectMapper.readTree(argsRaw);
					} catch (Exception ignored) {
						args = objectMapper.createObjectNode().put("_raw", argsRaw);
					}
				}
				out.add(new LlmToolCall(b.id.toString(), b.name.toString(), args));
			}
			return out;
		}
	}

	private static final class ToolCallBuilder {
		private final StringBuilder id = new StringBuilder();
		private final StringBuilder name = new StringBuilder();
		private final StringBuilder arguments = new StringBuilder();
	}

	private String appendReasoningFromDelta(
		ChatCompletionChunk.Choice.Delta delta,
		StringBuilder reasoningBuffer,
		AtomicReference<String> reasoningSourceRef
	) {
		MinimaxReasoningSupport.ExtractedReasoning extracted = MinimaxReasoningSupport.extractReasoning(delta);
		if (extracted == null) {
			return null;
		}
		if (reasoningSourceRef != null && extracted.source() != null && !extracted.source().isEmpty()) {
			reasoningSourceRef.set(extracted.source());
		}
		return MinimaxReasoningSupport.mergeReasoning(reasoningBuffer, extracted);
	}

	private void emitThinkingIfNeeded(
		boolean hasToolThinking,
		boolean structuredReasoningEmitted,
		StringBuilder reasoningBuffer,
		AtomicReference<String> reasoningSourceRef,
		StringBuilder inlineThinkBuffer,
		String assistantFinal,
		String originModel,
		int iteration,
		reactor.core.publisher.FluxSink<LlmStreamEvent> sink,
		String sessionId,
		boolean inlineThinkingEmitted,
		String turnId,
		LongSupplier nextSequence,
		boolean thinkingVisible
	) {
		if (hasToolThinking || sink == null || !thinkingVisible) {
			return;
		}
		String reasoning = reasoningBuffer == null ? "" : reasoningBuffer.toString().trim();
		if (!reasoning.isEmpty()) {
			if (!structuredReasoningEmitted) {
				String source = reasoningSourceRef != null && reasoningSourceRef.get() != null
					? reasoningSourceRef.get()
					: MinimaxReasoningSupport.SOURCE_REASONING_FIELD;
					emitStreamEvent(sink, sessionId, LlmStreamEvent.thinking(
						reasoning,
						source,
						originModel,
					iteration,
					turnId,
					nextSequence.getAsLong(),
					System.currentTimeMillis()
					));
				}
			recordThinkingMessage(sessionId, reasoning, thinkingVisible);
			return;
		}
		String inlineThinking = inlineThinkBuffer == null ? "" : inlineThinkBuffer.toString().trim();
		if (!inlineThinking.isEmpty()) {
			if (!inlineThinkingEmitted) {
				emitStreamEvent(sink, sessionId, LlmStreamEvent.thinking(
					inlineThinking,
					"assistant_content",
					originModel,
					iteration,
					turnId,
					nextSequence.getAsLong(),
					System.currentTimeMillis()
				));
			}
			recordThinkingMessage(sessionId, inlineThinking, thinkingVisible);
			return;
		}
		if (looksLikeStepByStep(assistantFinal)) {
			emitStreamEvent(sink, sessionId, LlmStreamEvent.thinking(
				assistantFinal,
				"assistant_content",
				originModel,
				iteration,
				turnId,
				nextSequence.getAsLong(),
				System.currentTimeMillis()
			));
			recordThinkingMessage(sessionId, assistantFinal, thinkingVisible);
		}
	}

	private String resolveToolStatus(ToolResult toolResult, Exception toolFailure) {
		if (toolResult != null && toolResult.isSuccess()) {
			return "success";
		}
		String error = toolResult != null ? toolResult.getError() : null;
		if (isTimeoutError(toolFailure, error)) {
			return "timeout";
		}
		return "error";
	}

	private boolean isTimeoutError(Exception toolFailure, String errorMessage) {
		if (toolFailure instanceof java.util.concurrent.TimeoutException) {
			return true;
		}
		if (errorMessage == null) {
			return false;
		}
		String normalized = errorMessage.toLowerCase();
		return normalized.contains("timeout") || normalized.contains("timed out");
	}

	private JsonNode toPreviewResult(ToolResult toolResult) {
		if (toolResult == null) {
			return null;
		}
		if (!toolResult.isSuccess()) {
			return objectMapper.createObjectNode().put("error", truncate(toolResult.getError(), 300));
		}
		String output = toolResult.getOutput();
		if (output == null || output.trim().isEmpty()) {
			return null;
		}
		try {
			JsonNode asJson = objectMapper.readTree(output);
			return toPreviewJson(asJson, 500);
		} catch (Exception ignored) {
			return objectMapper.createObjectNode().put("text", truncate(output, 500));
		}
	}

	private void emitStreamEvent(reactor.core.publisher.FluxSink<LlmStreamEvent> sink, String sessionId, LlmStreamEvent event) {
		if (sink == null || event == null) {
			return;
		}
		sink.next(event);
		if (sessionReplayStore == null) {
			return;
		}
		try {
			sessionReplayStore.append(sessionId, event);
		} catch (Exception e) {
			log.warn("event=session_replay_append_failed sessionId={} type={} message={}", sessionId, event.getType(), e.getMessage());
		}
	}

	private JsonNode toPreviewJson(JsonNode node, int maxChars) {
		if (node == null) {
			return null;
		}
		String raw = node.toString();
		if (raw.length() <= maxChars) {
			return node;
		}
		return objectMapper.createObjectNode().put("_preview", truncate(raw, maxChars));
	}

	private String truncate(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		if (value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength) + "...";
	}

	private void persistTurnExecutionSummary(AgentTurnExecutionAccumulator executionAccumulator) {
		if (benchmarkTurnExecutionSummaryService == null || executionAccumulator == null) {
			return;
		}
		try {
			benchmarkTurnExecutionSummaryService.saveSummary(executionAccumulator.toWriteModel());
		} catch (Exception e) {
			log.warn(
				"event=benchmark_turn_summary_save_failed turnId={} message={}",
				executionAccumulator.toWriteModel().turnId(),
				e.getMessage()
			);
		}
	}

	private void throwIfCancelled(reactor.core.publisher.FluxSink<LlmStreamEvent> sink, AtomicBoolean cancelled) {
		if (isCancelled(sink, cancelled)) {
			throw new CancellationException("client cancelled");
		}
	}

	private boolean isCancelled(reactor.core.publisher.FluxSink<LlmStreamEvent> sink, AtomicBoolean cancelled) {
		return (cancelled != null && cancelled.get()) || (sink != null && sink.isCancelled()) || Thread.currentThread().isInterrupted();
	}

	private boolean isCancellationException(AtomicBoolean cancelled, Exception exception) {
		return cancelled != null && cancelled.get()
			|| exception instanceof java.lang.InterruptedException
			|| Thread.currentThread().isInterrupted();
	}

	private String resolveSummaryFinishReason(AtomicBoolean cancelled, AtomicBoolean turnEnded, String finishReason) {
		if ((cancelled != null && cancelled.get()) && (turnEnded == null || !turnEnded.get())) {
			return "cancelled";
		}
		String normalized = finishReason == null ? null : finishReason.trim();
		return normalized == null || normalized.isEmpty() ? "completed" : normalized;
	}

	private static final class ThinkTagParser {
		private final StringBuilder buffer = new StringBuilder();
		private boolean inThink = false;

		private void accept(String chunk, java.util.function.Consumer<String> onAnswer, java.util.function.Consumer<String> onThink) {
			if (chunk == null || chunk.isEmpty()) {
				return;
			}
			buffer.append(chunk);
			while (true) {
				if (!inThink) {
					int idx = buffer.indexOf(THINK_OPEN);
					if (idx < 0) {
						String out = buffer.toString();
						buffer.setLength(0);
						if (!out.isEmpty()) {
							onAnswer.accept(out);
						}
						return;
					}
					String before = buffer.substring(0, idx);
					if (!before.isEmpty()) {
						onAnswer.accept(before);
					}
					buffer.delete(0, idx + THINK_OPEN.length());
					inThink = true;
				} else {
					int idx = buffer.indexOf(THINK_CLOSE);
					if (idx < 0) {
						String out = buffer.toString();
						buffer.setLength(0);
						if (!out.isEmpty()) {
							onThink.accept(out);
						}
						return;
					}
					String thinkPart = buffer.substring(0, idx);
					if (!thinkPart.isEmpty()) {
						onThink.accept(thinkPart);
					}
					buffer.delete(0, idx + THINK_CLOSE.length());
					inThink = false;
				}
			}
		}
	}

	private void recordThinkingMessage(String sessionId, String content, boolean thinkingVisible) {
		if (!thinkingVisible || content == null || content.trim().isEmpty()) {
			return;
		}
		persistentMessageStore.append(sessionId, new ThinkingMessage(content));
	}

	private Set<String> resolveAllowedToolNames(boolean includeTools, boolean memoryEnabled) {
		Set<String> allowed = new LinkedHashSet<>();
		if (!includeTools) {
			return allowed;
		}
		for (Tool tool : toolRouter.getTools()) {
			if (tool == null || tool.name() == null || tool.name().trim().isEmpty()) {
				continue;
			}
			String toolName = tool.name().trim();
			if (!memoryEnabled && "memory_search".equals(toolName)) {
				continue;
			}
			allowed.add(toolName);
		}
		return allowed;
	}

	private boolean isToolAllowed(Set<String> allowedToolNames, String toolName) {
		if (toolName == null || toolName.trim().isEmpty()) {
			return false;
		}
		return allowedToolNames != null && allowedToolNames.contains(toolName.trim());
	}

	private boolean looksLikeStepByStep(String text) {
		if (text == null || text.trim().isEmpty()) {
			return false;
		}
		if (text.contains("<think>") || text.contains("</think>")) {
			return false;
		}
		return STEP_PATTERN.matcher(text).find();
	}
}
