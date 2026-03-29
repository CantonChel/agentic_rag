package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.rag.splitter.TokenCounter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SessionContextBudgetEvaluator {
	private final SessionContextProperties props;
	private final TokenCounter tokenCounter;

	public SessionContextBudgetEvaluator(SessionContextProperties props, TokenCounter tokenCounter) {
		this.props = props;
		this.tokenCounter = tokenCounter;
	}

	public boolean exceedsStorageBudget(List<ChatMessage> messages) {
		return evaluate(messages, false).isExceeded();
	}

	public boolean exceedsPreflightBudget(List<ChatMessage> messages) {
		return evaluate(messages, true).isExceeded();
	}

	public boolean fitsWithinPreflightBudget(List<ChatMessage> messages) {
		return !evaluate(messages, true).isExceeded();
	}

	public BudgetSnapshot evaluateStorage(List<ChatMessage> messages) {
		return evaluate(messages, false);
	}

	public BudgetSnapshot evaluatePreflight(List<ChatMessage> messages) {
		return evaluate(messages, true);
	}

	public int countTokens(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int tokens = 0;
		for (ChatMessage message : messages) {
			if (message == null) {
				continue;
			}
			String content = message.getContent();
			if (content == null || content.isEmpty()) {
				continue;
			}
			tokens += tokenCounter != null ? tokenCounter.count(content) : content.length();
		}
		return tokens;
	}

	public int countBytes(List<ChatMessage> messages) {
		if (messages == null || messages.isEmpty()) {
			return 0;
		}
		int bytes = 0;
		for (ChatMessage message : messages) {
			if (message == null) {
				continue;
			}
			String content = message.getContent();
			if (content == null || content.isEmpty()) {
				continue;
			}
			bytes += content.getBytes(StandardCharsets.UTF_8).length;
		}
		return bytes;
	}

	private BudgetSnapshot evaluate(List<ChatMessage> messages, boolean preflight) {
		int tokens = countTokens(messages);
		int bytes = countBytes(messages);
		int tokenLimit = preflight ? resolvePreflightTokenLimit() : resolveStorageTokenLimit();
		int byteLimit = preflight ? resolvePreflightByteLimit() : resolveStorageByteLimit();
		boolean tokenExceeded = tokenLimit > 0 && tokens > tokenLimit;
		boolean byteExceeded = byteLimit > 0 && bytes > byteLimit;
		return new BudgetSnapshot(tokens, bytes, tokenLimit, byteLimit, tokenExceeded, byteExceeded);
	}

	private int resolveStorageTokenLimit() {
		return props != null && props.getMaxTokens() > 0 ? props.getMaxTokens() : 20000;
	}

	private int resolveStorageByteLimit() {
		return props != null && props.getMaxBytes() > 0 ? props.getMaxBytes() : 0;
	}

	private int resolvePreflightTokenLimit() {
		int maxTokens = resolveStorageTokenLimit();
		if (maxTokens <= 0) {
			return 0;
		}
		int reserve = props != null ? Math.max(0, props.getPreflightReserveTokens()) : 0;
		return Math.max(1, maxTokens - reserve);
	}

	private int resolvePreflightByteLimit() {
		int maxBytes = resolveStorageByteLimit();
		if (maxBytes <= 0) {
			return 0;
		}
		int reserve = props != null ? Math.max(0, props.getPreflightReserveBytes()) : 0;
		return Math.max(1, maxBytes - reserve);
	}

	public static class BudgetSnapshot {
		private final int tokens;
		private final int bytes;
		private final int tokenLimit;
		private final int byteLimit;
		private final boolean tokenExceeded;
		private final boolean byteExceeded;

		public BudgetSnapshot(
			int tokens,
			int bytes,
			int tokenLimit,
			int byteLimit,
			boolean tokenExceeded,
			boolean byteExceeded
		) {
			this.tokens = tokens;
			this.bytes = bytes;
			this.tokenLimit = tokenLimit;
			this.byteLimit = byteLimit;
			this.tokenExceeded = tokenExceeded;
			this.byteExceeded = byteExceeded;
		}

		public int getTokens() {
			return tokens;
		}

		public int getBytes() {
			return bytes;
		}

		public int getTokenLimit() {
			return tokenLimit;
		}

		public int getByteLimit() {
			return byteLimit;
		}

		public boolean isTokenExceeded() {
			return tokenExceeded;
		}

		public boolean isByteExceeded() {
			return byteExceeded;
		}

		public boolean isExceeded() {
			return tokenExceeded || byteExceeded;
		}
	}
}
