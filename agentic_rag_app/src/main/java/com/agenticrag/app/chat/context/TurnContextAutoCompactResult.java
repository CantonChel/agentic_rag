package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TurnContextAutoCompactResult {
	private final List<ChatMessage> messagesForModel;
	private final boolean compacted;
	private final boolean forcedFinalResponse;
	private final int rawTokenCount;
	private final int modelTokenCount;
	private final int triggerTokenLimit;

	public TurnContextAutoCompactResult(
		List<ChatMessage> messagesForModel,
		boolean compacted,
		boolean forcedFinalResponse,
		int rawTokenCount,
		int modelTokenCount,
		int triggerTokenLimit
	) {
		this.messagesForModel = messagesForModel == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(messagesForModel));
		this.compacted = compacted;
		this.forcedFinalResponse = forcedFinalResponse;
		this.rawTokenCount = rawTokenCount;
		this.modelTokenCount = modelTokenCount;
		this.triggerTokenLimit = triggerTokenLimit;
	}

	public List<ChatMessage> getMessagesForModel() {
		return messagesForModel;
	}

	public boolean isCompacted() {
		return compacted;
	}

	public boolean isForcedFinalResponse() {
		return forcedFinalResponse;
	}

	public int getRawTokenCount() {
		return rawTokenCount;
	}

	public int getModelTokenCount() {
		return modelTokenCount;
	}

	public int getTriggerTokenLimit() {
		return triggerTokenLimit;
	}
}
