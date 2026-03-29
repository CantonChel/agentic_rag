package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SessionContextProjector {
	public List<ChatMessage> project(TurnExecutionContext turnExecutionContext, String finishReason, boolean singleTurn) {
		if (singleTurn || turnExecutionContext == null || finishReason == null || finishReason.trim().isEmpty()) {
			return Collections.emptyList();
		}
		if (!"stop".equals(finishReason) && !"max_iterations_fallback".equals(finishReason)) {
			return Collections.emptyList();
		}

		ChatMessage userMessage = null;
		ChatMessage assistantMessage = null;
		List<ChatMessage> turnMessages = turnExecutionContext.getTurnMessages();
		for (ChatMessage message : turnMessages) {
			if (message == null || message.getType() == null) {
				continue;
			}
			if (userMessage == null && message.getType() == ChatMessageType.USER) {
				userMessage = message;
			}
			if (message.getType() == ChatMessageType.ASSISTANT) {
				assistantMessage = message;
			}
		}

		if (userMessage == null || assistantMessage == null) {
			return Collections.emptyList();
		}

		List<ChatMessage> projected = new ArrayList<>(2);
		projected.add(userMessage);
		projected.add(assistantMessage);
		return projected;
	}
}
