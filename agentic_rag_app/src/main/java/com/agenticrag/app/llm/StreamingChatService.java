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
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
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
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import com.openai.errors.UnauthorizedException;

@Service
public class StreamingChatService {
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
	}

	public Flux<LlmStreamEvent> stream(LlmProvider provider, String prompt, boolean includeTools) {
		return stream(provider, "default", prompt, includeTools, LlmToolChoiceMode.AUTO);
	}

	public Flux<LlmStreamEvent> stream(LlmProvider provider, String prompt, boolean includeTools, LlmToolChoiceMode toolChoiceMode) {
		return stream(provider, "default", prompt, includeTools, toolChoiceMode);
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		return Flux.create(sink -> {
			Future<?> future = streamExecutor.submit(() -> {
				ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator(objectMapper);
				StringBuilder assistantContent = new StringBuilder();
				StringBuilder reasoningBuffer = new StringBuilder();
				StringBuilder inlineThinkBuffer = new StringBuilder();
				ThinkTagParser thinkTagParser = new ThinkTagParser();
				boolean inlineThinkingEmitted = false;

				OpenAIClient client = provider == LlmProvider.MINIMAX ? minimaxClient : openAiClient;
				String model = provider == LlmProvider.MINIMAX ? minimaxProperties.getModel() : openAiProperties.getModel();
				String originModel = model;

				String sid = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
				String configuredSystemPrompt = systemPromptManager.build(new SystemPromptContext(provider, true));
				contextManager.ensureSystemPrompt(sid, configuredSystemPrompt);
				String systemPrompt = contextManager.getSystemPrompt(sid);
				persistentMessageStore.ensureSystemPrompt(sid, systemPrompt);
				OpenAiMessageAdapter adapter = new OpenAiMessageAdapter(objectMapper);

				List<com.agenticrag.app.chat.message.ChatMessage> local = new ArrayList<>();
				List<com.agenticrag.app.chat.message.ChatMessage> sessionContext = contextManager.getContext(sid);
				if (sessionContext != null && !sessionContext.isEmpty()) {
					for (int i = 1; i < sessionContext.size(); i++) {
						local.add(sessionContext.get(i));
					}
				}
				UserMessage user = new UserMessage(prompt);
				local.add(user);
				persistentMessageStore.append(sid, user);
				contextManager.addMessage(sid, user);

				ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
					.model(model)
					.addSystemMessage(systemPrompt);

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

				try (StreamResponse<ChatCompletionChunk> streamResponse = client.chat().completions().createStreaming(paramsBuilder.build())) {
					for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) streamResponse.stream()::iterator) {
						if (chunk == null || chunk.choices() == null) {
							continue;
						}
						if (chunk.model() != null && !chunk.model().isEmpty()) {
							originModel = chunk.model();
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
											sink.next(LlmStreamEvent.delta(answerPart));
										},
										thinkPart -> {
											inlineThinkBuffer.append(thinkPart);
											inlineThinkingEmitted = true;
											sink.next(LlmStreamEvent.thinking(thinkPart, "assistant_content", originModel, 1));
										}
									);
								}
							}

							if (delta.toolCalls().isPresent() && delta.toolCalls().get() != null) {
								toolCallAccumulator.append(delta.toolCalls().get());
							}
							appendReasoningFromDelta(delta, reasoningBuffer);

							if (choice.finishReason().isPresent() && choice.finishReason().get() != null) {
								String finishReason = choice.finishReason().get().toString();
								List<LlmToolCall> toolCalls = toolCallAccumulator.buildToolCalls();
								String assistantFinal = assistantContent.toString().trim();
								if (!assistantFinal.isEmpty()) {
									AssistantMessage am = new AssistantMessage(assistantFinal);
									persistentMessageStore.append(sid, am);
									contextManager.addMessage(sid, am);
								}
								if (toolCalls != null && !toolCalls.isEmpty()) {
									ToolCallMessage tcm = new ToolCallMessage(toolCalls);
									persistentMessageStore.append(sid, tcm);
									contextManager.addMessage(sid, tcm);
								}
								String reasoning = reasoningBuffer.toString().trim();
								if (!reasoning.isEmpty()) {
									sink.next(LlmStreamEvent.thinking(reasoning, "reasoning_field", originModel, 1));
									recordThinkingMessage(sid, reasoning);
								} else {
									String inlineThinking = inlineThinkBuffer.toString().trim();
									if (!inlineThinking.isEmpty()) {
										if (!inlineThinkingEmitted) {
											sink.next(LlmStreamEvent.thinking(inlineThinking, "assistant_content", originModel, 1));
										}
										recordThinkingMessage(sid, inlineThinking);
									} else if (looksLikeStepByStep(assistantFinal)) {
										sink.next(LlmStreamEvent.thinking(assistantFinal, "assistant_content", originModel, 1));
										recordThinkingMessage(sid, assistantFinal);
									}
								}
								sink.next(LlmStreamEvent.done(finishReason, toolCalls));
							}
						}
					}
				} catch (UnauthorizedException e) {
					sink.next(LlmStreamEvent.error("Unauthorized: check API key for provider=" + provider + " (401)"));
				} catch (Exception e) {
					sink.next(LlmStreamEvent.error("LLM stream failed: " + e.getMessage()));
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

	private void appendReasoningFromDelta(ChatCompletionChunk.Choice.Delta delta, StringBuilder reasoningBuffer) {
		if (delta == null || reasoningBuffer == null) {
			return;
		}
		Map<String, JsonValue> extras = delta._additionalProperties();
		if (extras == null || extras.isEmpty()) {
			return;
		}
		String text = extractReasoningText(extras, "reasoning_content", "thinking", "reasoning");
		if (text != null && !text.isEmpty()) {
			reasoningBuffer.append(text);
		}
	}

	private String extractReasoningText(Map<String, JsonValue> extras, String... keys) {
		if (extras == null || keys == null) {
			return null;
		}
		for (String key : keys) {
			JsonValue value = extras.get(key);
			if (value == null) {
				continue;
			}
			try {
				JsonNode node = value.convert(JsonNode.class);
				if (node == null) {
					continue;
				}
				if (node.isTextual()) {
					return node.asText();
				}
				if (node.isArray()) {
					StringBuilder sb = new StringBuilder();
					for (JsonNode item : node) {
						if (item != null && item.isTextual()) {
							sb.append(item.asText());
						}
					}
					if (sb.length() > 0) {
						return sb.toString();
					}
				}
			} catch (Exception ignored) {
				// ignore malformed reasoning payloads
			}
		}
		return null;
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
