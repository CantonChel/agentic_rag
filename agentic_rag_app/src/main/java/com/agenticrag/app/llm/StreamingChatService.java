package com.agenticrag.app.llm;

import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.chat.memory.ChatMemory;
import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.UserMessage;
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
	private final ChatMemory chatMemory;

	public StreamingChatService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ChatMemory chatMemory
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

				OpenAIClient client = provider == LlmProvider.MINIMAX ? minimaxClient : openAiClient;
				String model = provider == LlmProvider.MINIMAX ? minimaxProperties.getModel() : openAiProperties.getModel();

				String sid = sessionId == null || sessionId.trim().isEmpty() ? "default" : sessionId.trim();
				String systemPrompt = systemPromptManager.build(new SystemPromptContext(provider, includeTools));
				chatMemory.append(sid, new SystemMessage(systemPrompt));
				chatMemory.append(sid, new UserMessage(prompt));

				ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
					.model(model)
					.addSystemMessage(systemPrompt)
					.addUserMessage(prompt);

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
								String finishReason = choice.finishReason().get().toString();
								List<LlmToolCall> toolCalls = toolCallAccumulator.buildToolCalls();
								String assistantFinal = assistantContent.toString().trim();
								if (!assistantFinal.isEmpty()) {
									chatMemory.append(sid, new AssistantMessage(assistantFinal));
								}
								if (toolCalls != null && !toolCalls.isEmpty()) {
									chatMemory.append(sid, new ToolCallMessage(toolCalls));
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
}
