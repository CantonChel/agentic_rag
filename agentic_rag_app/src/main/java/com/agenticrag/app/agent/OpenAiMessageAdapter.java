package com.agenticrag.app.agent;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.ToolCallMessage;
import com.agenticrag.app.chat.message.ToolResultMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.llm.LlmToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OpenAiMessageAdapter {
	private final ObjectMapper objectMapper;

	public OpenAiMessageAdapter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public List<ChatCompletionMessageParam> toMessageParams(List<ChatMessage> messages) {
		List<ChatCompletionMessageParam> out = new ArrayList<>();
		if (messages == null) {
			return out;
		}
		for (ChatMessage msg : messages) {
			if (msg == null) {
				continue;
			}
			if (msg.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			ChatCompletionMessageParam mapped = toMessageParam(msg);
			if (mapped != null) {
				out.add(mapped);
			}
		}
		return out;
	}

	private ChatCompletionMessageParam toMessageParam(ChatMessage msg) {
		if (msg instanceof UserMessage) {
			return ChatCompletionMessageParam.ofUser(
				ChatCompletionUserMessageParam.builder().content(msg.getContent()).build()
			);
		}

		if (msg instanceof AssistantMessage) {
			return ChatCompletionMessageParam.ofAssistant(
				ChatCompletionAssistantMessageParam.builder().content(msg.getContent()).build()
			);
		}

		if (msg instanceof ToolCallMessage) {
			ChatCompletionAssistantMessageParam.Builder assistant = ChatCompletionAssistantMessageParam.builder();
			List<LlmToolCall> calls = ((ToolCallMessage) msg).getToolCalls();
			if (calls != null) {
				for (LlmToolCall call : calls) {
					if (call == null) {
						continue;
					}
					String callId = call.getId();
					if (callId == null || callId.trim().isEmpty()) {
						callId = UUID.randomUUID().toString();
					}
					String args = "{}";
					if (call.getArguments() != null) {
						try {
							args = objectMapper.writeValueAsString(call.getArguments());
						} catch (Exception ignored) {
							args = String.valueOf(call.getArguments());
						}
					}
					ChatCompletionMessageFunctionToolCall.Function fn = ChatCompletionMessageFunctionToolCall.Function.builder()
						.name(call.getName())
						.arguments(args)
						.build();
					ChatCompletionMessageFunctionToolCall fnCall = ChatCompletionMessageFunctionToolCall.builder()
						.id(callId)
						.type(JsonValue.from("function"))
						.function(fn)
						.build();
					assistant.addToolCall(ChatCompletionMessageToolCall.ofFunction(fnCall));
				}
			}
			return ChatCompletionMessageParam.ofAssistant(assistant.build());
		}

		if (msg instanceof ToolResultMessage) {
			ToolResultMessage tr = (ToolResultMessage) msg;
			String toolCallId = tr.getToolCallId();
			if (toolCallId == null || toolCallId.trim().isEmpty()) {
				toolCallId = "unknown";
			}
			return ChatCompletionMessageParam.ofTool(
				ChatCompletionToolMessageParam.builder()
					.toolCallId(toolCallId)
					.content(tr.getContent())
					.build()
			);
		}

		if (msg.getType() == ChatMessageType.USER) {
			return ChatCompletionMessageParam.ofUser(
				ChatCompletionUserMessageParam.builder().content(msg.getContent()).build()
			);
		}

		if (msg.getType() == ChatMessageType.ASSISTANT) {
			return ChatCompletionMessageParam.ofAssistant(
				ChatCompletionAssistantMessageParam.builder().content(msg.getContent()).build()
			);
		}
		return null;
	}
}

