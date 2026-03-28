package com.agenticrag.app.llm;

import com.agenticrag.app.agent.OpenAiMessageAdapter;
import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.chat.message.ThinkingMessage;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.memory.MemoryFlushService;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import com.agenticrag.app.session.ChatSession;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.session.SessionScope;
import com.agenticrag.app.tool.ToolDefinition;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import com.openai.errors.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class StreamingChatService {
	private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);
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
	private final SessionManager sessionManager;
	private final MemoryFlushService memoryFlushService;
	private static final Pattern STEP_PATTERN = Pattern.compile("(?m)^(\\s*(步骤\\s*\\d+\\.?|Step\\s*\\d+\\.?|\\d+\\.)\\s+)");
	private static final String THINK_OPEN = "<think>";
	private static final String THINK_CLOSE = "</think>";

	public StreamingChatService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore
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
			null,
			null
		);
	}

	@Autowired
	public StreamingChatService(
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
		SessionManager sessionManager,
		MemoryFlushService memoryFlushService
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
		this.sessionManager = sessionManager;
		this.memoryFlushService = memoryFlushService;
	}

	public Flux<LlmStreamEvent> stream(LlmProvider provider, String prompt, boolean includeTools) {
		return stream(provider, "anonymous", "default", prompt, includeTools, LlmToolChoiceMode.AUTO, null);
	}

	public Flux<LlmStreamEvent> stream(LlmProvider provider, String prompt, boolean includeTools, LlmToolChoiceMode toolChoiceMode) {
		return stream(provider, "anonymous", "default", prompt, includeTools, toolChoiceMode, null);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		return stream(provider, "anonymous", sessionId, prompt, includeTools, toolChoiceMode, null);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		return stream(provider, userId, sessionId, prompt, includeTools, toolChoiceMode, null);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String userId,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode,
		String knowledgeBaseId
	) {
		return Flux.create(sink -> {
			Future<?> future = streamExecutor.submit(() -> {
				ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator(objectMapper);
				StringBuilder assistantContent = new StringBuilder();
				StringBuilder reasoningBuffer = new StringBuilder();
				StringBuilder inlineThinkBuffer = new StringBuilder();
				ThinkTagParser thinkTagParser = new ThinkTagParser();
				AtomicBoolean inlineThinkingEmitted = new AtomicBoolean(false);
				AtomicBoolean structuredReasoningEmitted = new AtomicBoolean(false);
				AtomicReference<String> reasoningSourceRef = new AtomicReference<>(MinimaxReasoningSupport.SOURCE_REASONING_FIELD);

				OpenAIClient client = provider == LlmProvider.MINIMAX ? minimaxClient : openAiClient;
				String model = provider == LlmProvider.MINIMAX ? minimaxProperties.getModel() : openAiProperties.getModel();
				AtomicReference<String> originModelRef = new AtomicReference<>(model);

				String uid = SessionScope.normalizeUserId(userId);
				String sid = SessionScope.normalizeSessionId(sessionId);
				String scopedSid = SessionScope.scopedSessionId(uid, sid);
				String scopedKnowledgeBaseId = normalizeKnowledgeBaseId(knowledgeBaseId);
				String turnId = java.util.UUID.randomUUID().toString();
				java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong(0L);
				java.util.function.LongSupplier nextSequence = () -> sequence.incrementAndGet();
				java.util.function.Consumer<LlmStreamEvent> emit = event -> emitStreamEvent(sink, scopedSid, event);

				if (isSessionSwitchCommand(prompt)) {
					if (memoryFlushService != null) {
						memoryFlushService.flushOnSessionSwitchCommand(
							scopedSid,
							normalizeSwitchReason(prompt),
							contextManager.getContext(scopedSid),
							persistentMessageStore.list(scopedSid)
						);
					}
					String newSessionId = createNextSessionId(uid);
					emit.accept(LlmStreamEvent.sessionSwitched(newSessionId));
					emit.accept(LlmStreamEvent.done("session_switched", null, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), null));
					sink.complete();
					return;
				}

				String configuredSystemPrompt = systemPromptManager.build(new SystemPromptContext(provider, includeTools, SystemPromptMode.LLM));
				log.info(
					"event=llm_stream_start provider={} userId={} sessionId={} knowledgeBaseId={} includeTools={} toolChoice={} promptChars={}",
					provider,
					uid,
					sid,
					scopedKnowledgeBaseId,
					includeTools,
					toolChoiceMode,
					prompt != null ? prompt.length() : 0
				);
				contextManager.ensureSystemPrompt(scopedSid, configuredSystemPrompt);
				String systemPrompt = contextManager.getSystemPrompt(scopedSid);
				persistentMessageStore.ensureSystemPrompt(scopedSid, systemPrompt);
				OpenAiMessageAdapter adapter = new OpenAiMessageAdapter(objectMapper);

				List<com.agenticrag.app.chat.message.ChatMessage> local = new ArrayList<>();
				List<com.agenticrag.app.chat.message.ChatMessage> sessionContext = contextManager.getContext(scopedSid);
				if (sessionContext != null && !sessionContext.isEmpty()) {
					for (int i = 1; i < sessionContext.size(); i++) {
						local.add(sessionContext.get(i));
					}
				}
				UserMessage user = new UserMessage(prompt);
				local.add(user);
				persistentMessageStore.append(scopedSid, user);
				contextManager.addMessage(scopedSid, user);

				ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
					.model(model)
					.addSystemMessage(systemPrompt);
				MinimaxReasoningSupport.applyReasoningSplit(paramsBuilder, provider, minimaxProperties);

				List<com.openai.models.chat.completions.ChatCompletionMessageParam> messageParams = adapter.toMessageParams(local);
				for (com.openai.models.chat.completions.ChatCompletionMessageParam mp : messageParams) {
					paramsBuilder.addMessage(mp);
				}

				if (includeTools) {
					paramsBuilder.tools(buildChatCompletionTools(toolRouter.getToolDefinitions()));
					paramsBuilder.toolChoice(toToolChoice(toolChoiceMode));
				} else {
					paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
				}

				emit.accept(LlmStreamEvent.turnStart(turnId, nextSequence.getAsLong(), System.currentTimeMillis(), sid));

				try (StreamResponse<ChatCompletionChunk> streamResponse = client.chat().completions().createStreaming(paramsBuilder.build())) {
					for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) streamResponse.stream()::iterator) {
						if (chunk == null || chunk.choices() == null) {
							continue;
						}
						if (chunk.model() != null && !chunk.model().isEmpty()) {
							originModelRef.set(chunk.model());
						}
						for (ChatCompletionChunk.Choice choice : chunk.choices()) {
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
											emit.accept(LlmStreamEvent.delta(answerPart, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), 1));
										},
										thinkPart -> {
											inlineThinkBuffer.append(thinkPart);
											inlineThinkingEmitted.set(true);
											emit.accept(LlmStreamEvent.thinking(
												thinkPart,
												"assistant_content",
												originModelRef.get(),
												1,
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
								emit.accept(LlmStreamEvent.thinking(
									reasoningPart,
									reasoningSourceRef.get(),
									originModelRef.get(),
									1,
									turnId,
									nextSequence.getAsLong(),
									System.currentTimeMillis()
								));
							}

							if (choice.finishReason().isPresent() && choice.finishReason().get() != null) {
								String finishReason = choice.finishReason().get().toString();
								List<LlmToolCall> toolCalls = toolCallAccumulator.buildToolCalls();
								String assistantFinal = assistantContent.toString().trim();
								if (!assistantFinal.isEmpty()) {
									AssistantMessage am = new AssistantMessage(assistantFinal);
									persistentMessageStore.append(scopedSid, am);
									contextManager.addMessage(scopedSid, am);
								}
								if (toolCalls != null && !toolCalls.isEmpty()) {
									ToolCallMessage tcm = new ToolCallMessage(toolCalls);
									persistentMessageStore.append(scopedSid, tcm);
									contextManager.addMessage(scopedSid, tcm);
								}
								String reasoning = reasoningBuffer.toString().trim();
								if (!reasoning.isEmpty()) {
									if (!structuredReasoningEmitted.get()) {
										emit.accept(LlmStreamEvent.thinking(
											reasoning,
											reasoningSourceRef.get(),
											originModelRef.get(),
											1,
											turnId,
											nextSequence.getAsLong(),
											System.currentTimeMillis()
										));
									}
									recordThinkingMessage(scopedSid, reasoning);
								} else {
									String inlineThinking = inlineThinkBuffer.toString().trim();
									if (!inlineThinking.isEmpty()) {
										if (!inlineThinkingEmitted.get()) {
											emit.accept(LlmStreamEvent.thinking(
												inlineThinking,
												"assistant_content",
												originModelRef.get(),
												1,
												turnId,
												nextSequence.getAsLong(),
												System.currentTimeMillis()
											));
										}
										recordThinkingMessage(scopedSid, inlineThinking);
									} else if (looksLikeStepByStep(assistantFinal)) {
										emit.accept(LlmStreamEvent.thinking(
											assistantFinal,
											"assistant_content",
											originModelRef.get(),
											1,
											turnId,
											nextSequence.getAsLong(),
											System.currentTimeMillis()
										));
										recordThinkingMessage(scopedSid, assistantFinal);
									}
								}
								emit.accept(LlmStreamEvent.done(finishReason, toolCalls, turnId, nextSequence.getAsLong(), System.currentTimeMillis(), 1));
								emit.accept(LlmStreamEvent.turnEnd(turnId, nextSequence.getAsLong(), System.currentTimeMillis(), finishReason, 1));
							}
						}
					}
				} catch (UnauthorizedException e) {
					emit.accept(LlmStreamEvent.error(
						"Unauthorized: check API key for provider=" + provider + " (401)",
						turnId,
						nextSequence.getAsLong(),
						System.currentTimeMillis(),
						1
					));
					emit.accept(LlmStreamEvent.turnEnd(turnId, nextSequence.getAsLong(), System.currentTimeMillis(), "error", 1));
				} catch (Exception e) {
					emit.accept(LlmStreamEvent.error(
						"LLM stream failed: " + e.getMessage(),
						turnId,
						nextSequence.getAsLong(),
						System.currentTimeMillis(),
						1
					));
					emit.accept(LlmStreamEvent.turnEnd(turnId, nextSequence.getAsLong(), System.currentTimeMillis(), "error", 1));
				}

				sink.complete();
			});

			sink.onCancel(() -> future.cancel(true));
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
		return java.util.UUID.randomUUID().toString();
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

	private Map<String, JsonValue> asJsonValueMap(JsonNode node) {
		if (node == null || !node.isObject()) {
			return Collections.emptyMap();
		}
		Map<String, JsonValue> out = new HashMap<>();
		node.fields().forEachRemaining(e -> out.put(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
		return out;
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

	private boolean looksLikeStepByStep(String text) {
		if (text == null || text.trim().isEmpty()) {
			return false;
		}
		if (text.contains("<think>") || text.contains("</think>")) {
			return false;
		}
		return STEP_PATTERN.matcher(text).find();
	}

	private void recordThinkingMessage(String sessionId, String content) {
		if (content == null || content.trim().isEmpty()) {
			return;
		}
		persistentMessageStore.append(sessionId, new ThinkingMessage(content));
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
}
