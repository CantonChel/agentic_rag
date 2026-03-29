package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import java.util.ArrayList;
import java.util.List;

public class TurnExecutionContext {
	private final String systemPrompt;
	private final List<ChatMessage> historicalMessages = new ArrayList<>();
	private final List<ChatMessage> turnMessages = new ArrayList<>();

	public TurnExecutionContext(String systemPrompt, List<ChatMessage> sessionContextMessages) {
		this.systemPrompt = systemPrompt != null ? systemPrompt : "";
		if (sessionContextMessages != null) {
			for (ChatMessage message : sessionContextMessages) {
				if (message == null || message.getType() == ChatMessageType.SYSTEM) {
					continue;
				}
				historicalMessages.add(message);
			}
		}
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public void addTurnMessage(ChatMessage message) {
		if (message == null || message.getType() == ChatMessageType.SYSTEM) {
			return;
		}
		turnMessages.add(message);
	}

	public List<ChatMessage> getMessagesForModel() {
		List<ChatMessage> out = new ArrayList<>(historicalMessages.size() + turnMessages.size());
		out.addAll(historicalMessages);
		out.addAll(turnMessages);
		return out;
	}

	public List<ChatMessage> getTurnMessages() {
		return new ArrayList<>(turnMessages);
	}
}
