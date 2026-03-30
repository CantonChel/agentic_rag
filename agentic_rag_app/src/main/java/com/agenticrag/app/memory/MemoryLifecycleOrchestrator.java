package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MemoryLifecycleOrchestrator {
	private final MemoryProperties properties;
	private final DailyDurableFlushService dailyDurableFlushService;
	private final SessionArchiveService sessionArchiveService;

	public MemoryLifecycleOrchestrator(
		MemoryProperties properties,
		DailyDurableFlushService dailyDurableFlushService,
		SessionArchiveService sessionArchiveService
	) {
		this.properties = properties;
		this.dailyDurableFlushService = dailyDurableFlushService;
		this.sessionArchiveService = sessionArchiveService;
	}

	public void flushDailyDurable(String scopedSessionId, List<ChatMessage> messages, boolean memoryEnabled) {
		if (!properties.isEnabled() || !memoryEnabled) {
			return;
		}
		dailyDurableFlushService.flush(scopedSessionId, messages);
	}

	public void archiveSession(
		String scopedSessionId,
		String reason,
		List<ChatMessage> contextMessages,
		List<StoredMessageEntity> persistedMessages,
		boolean memoryEnabled
	) {
		if (!properties.isEnabled() || !memoryEnabled) {
			return;
		}
		sessionArchiveService.archive(scopedSessionId, reason, contextMessages, persistedMessages);
	}
}
