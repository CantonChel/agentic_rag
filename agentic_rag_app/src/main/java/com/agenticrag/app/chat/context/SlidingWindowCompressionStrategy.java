package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;

public class SlidingWindowCompressionStrategy {
	public List<ChatMessage> compress(List<ChatMessage> messages, int keepLastMessages) {
		if (messages == null || messages.isEmpty()) {
			return new ArrayList<>();
		}
		int keep = keepLastMessages > 0 ? keepLastMessages : 20;
		if (messages.size() <= keep + 1) {
			return new ArrayList<>(messages);
		}
		List<ChatMessage> out = new ArrayList<>();
		out.add(messages.get(0));
		out.addAll(messages.subList(messages.size() - keep, messages.size()));
		return out;
	}
}
