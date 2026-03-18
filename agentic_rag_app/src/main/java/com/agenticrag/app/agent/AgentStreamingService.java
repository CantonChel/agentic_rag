package com.agenticrag.app.agent;

import com.agenticrag.app.chat.memory.ChatMemory;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.llm.LlmStreamEvent;
import com.agenticrag.app.llm.LlmToolCall;
import com.agenticrag.app.llm.LlmToolChoiceMode;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.tool.ToolDefinition;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AgentStreamingService {
	private final OpenAIClient openAiClient;
	private final OpenAIClient minimaxClient;
	private final OpenAiClientProperties openAiProperties;
	private final MinimaxClientProperties minimaxProperties;
	private final ToolRouter toolRouter;
	private final ObjectMapper objectMapper;
	private final ExecutorService streamExecutor;
	private final SystemPromptManager systemPromptManager;
	private final ChatMemory chatMemory;
	private final AgentProperties agentProperties;

	public AgentStreamingService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ChatMemory chatMemory,
		AgentProperties agentProperties
	) {
		this.openAiClient = openAiClient;
		this.minimaxClient = minimaxClient;
		this.openAiProperties = openAiProperties;
		this.minimaxProperties = minimaxProperties;
		this.toolRouter = toolRouter;
		this.objectMapper = objectMapper;
		this.streamExecutor = Executors.newCachedThreadPool();
		this.systemPromptManager = systemPromptManager;
		this.chatMemory = chatMemory;
		this.agentProperties = agentProperties;
	}

	public Flux<LlmStreamEvent> stream(
		LlmProvider provider,
		String sessionId,
		String prompt,
		boolean includeTools,
		LlmToolChoiceMode toolChoiceMode
	) {
		String sid = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
		int maxIterations = agentProperties.getMaxIterations() > 0 ? agentProperties.getMaxIterations() : 6;
		long toolTimeoutSeconds = agentProperties.getToolTimeoutSeconds() > 0 ? agentProperties.getToolTimeoutSeconds() : 30;

		return Flux.create(sink -> {
			Future<?> future = streamExecutor.submit(() -> {
				try {
					OpenAIClient client = provider == LlmProvider.MINIMAX ? minimaxClient : openAiClient;
					String model = provider == LlmProvider.MINIMAX ? minimaxProperties.getModel() : openAiProperties.getModel();
					String systemPrompt = systemPromptManager.build(new SystemPromptContext(provider, includeTools));
					OpenAiMessageAdapter adapter = new OpenAiMessageAdapter(objectMapper);

					List<ChatMessage> working = new ArrayList<>();
					List<ChatMessage> existing = chatMemory.getMessages(sid);
					if (existing != null) {
						working.addAll(existing);
					}

					ChatMessage userMsg = new UserMessage(prompt);
					working.add(userMsg);
					chatMemory.append(sid, userMsg);

					boolean finished = false;
					int iteration = 0;

					while (!finished && iteration < maxIterations) {
						iteration++;

						ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
							.model(model)
							.addSystemMessage(systemPrompt);

						List<com.openai.models.chat.completions.ChatCompletionMessageParam> messageParams = adapter.toMessageParams(working);
						for (com.openai.models.chat.completions.ChatCompletionMessageParam mp : messageParams) {
							paramsBuilder.addMessage(mp);
						}

						if (includeTools) {
							paramsBuilder.tools(buildChatCompletionTools(toolRouter.getToolDefinitions()));
							paramsBuilder.toolChoice(toToolChoice(toolChoiceMode));
						} else {
							paramsBuilder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE);
						}

						ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator(objectMapper);
						StringBuilder assistantContent = new StringBuilder();
						String finishReason = null;

						try (StreamResponse<ChatCompletionChunk> streamResponse = client.chat().completions().createStreaming(paramsBuilder.build())) {
							outer:
							for (ChatCompletionChunk chunk : (Iterable<ChatCompletionChunk>) streamResponse.stream()::iterator) {
								if (chunk == null || chunk.choices() == null) {
									continue;
								}
								for (ChatCompletionChunk.Choice choice : chunk.choices()) {
									if (choice == null || choice.delta() == null) {
										continue;
									}
									ChatCompletionChunk.Choice.Delta delta = choice.delta();

									if (delta.content().isPresent()) {
										String content = delta.content().get();
										if (content != null && !content.isEmpty()) {
											assistantContent.append(content);
											sink.next(LlmStreamEvent.delta(content));
										}
									}

									if (delta.toolCalls().isPresent() && delta.toolCalls().get() != null) {
										toolCallAccumulator.append(delta.toolCalls().get());
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

						if (!assistantFinal.isEmpty()) {
							AssistantMessage am = new AssistantMessage(assistantFinal);
							working.add(am);
							chatMemory.append(sid, am);
						}

						if (toolCalls == null || toolCalls.isEmpty()) {
							sink.next(LlmStreamEvent.done(finishReason != null ? finishReason : "stop", null));
							finished = true;
							break;
						}

						ToolCallMessage tcm = new ToolCallMessage(toolCalls);
						working.add(tcm);
						chatMemory.append(sid, tcm);

						for (LlmToolCall call : toolCalls) {
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

							ToolResult toolResult = toolRouter.getTool(toolName)
								.map(t -> t.execute(toolArgs, new ToolExecutionContext(toolCallId)).block(Duration.ofSeconds(toolTimeoutSeconds)))
								.orElse(ToolResult.error("Tool not found: " + toolName));

							if (toolResult == null) {
								toolResult = ToolResult.error("Tool execution returned null: " + toolName);
							}

							ToolResultMessage trm = new ToolResultMessage(
								toolName,
								toolCallId,
								toolResult.isSuccess(),
								toolResult.getOutput(),
								toolResult.getError()
							);
							working.add(trm);
							chatMemory.append(sid, trm);
						}
					}

					if (!finished) {
						sink.next(LlmStreamEvent.done("max_iterations", null));
					}
				} catch (UnauthorizedException e) {
					sink.next(LlmStreamEvent.error("Unauthorized: check API key for provider=" + provider + " (401)"));
				} catch (OpenAIException e) {
					sink.next(LlmStreamEvent.error("OpenAI SDK error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
				} catch (Exception e) {
					sink.next(LlmStreamEvent.error("Agent loop failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
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
}
