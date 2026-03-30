package com.agenticrag.app.chat.context;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.memory.DailyDurableFlushService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SessionContextPreflightCompactor {
	private final ContextManager contextManager;
	private final SessionContextBudgetEvaluator budgetEvaluator;
	private final DailyDurableFlushService dailyDurableFlushService;

	public SessionContextPreflightCompactor(ContextManager contextManager) {
		this(contextManager, null, null);
	}

	public SessionContextPreflightCompactor(
		ContextManager contextManager,
		SessionContextBudgetEvaluator budgetEvaluator,
		DailyDurableFlushService dailyDurableFlushService
	) {
		this.contextManager = contextManager;
		this.budgetEvaluator = budgetEvaluator;
		this.dailyDurableFlushService = dailyDurableFlushService;
	}

	@Autowired
	public SessionContextPreflightCompactor(
		ContextManager contextManager,
		SessionContextProperties props,
		com.agenticrag.app.rag.splitter.TokenCounter tokenCounter,
		DailyDurableFlushService dailyDurableFlushService
	) {
		this(contextManager, new SessionContextBudgetEvaluator(props, tokenCounter), dailyDurableFlushService);
	}

	public List<ChatMessage> prepareForTurn(String sessionId, SessionContextAppendOptions options) {
		if (contextManager == null) {
			return Collections.emptyList();
		}
		List<ChatMessage> currentContext = contextManager.getContext(sessionId);
		if (budgetEvaluator == null || !budgetEvaluator.exceedsPreflightBudget(currentContext)) {
			return currentContext;
		}

		SessionContextAppendOptions effectiveOptions = options != null
			? options
			: SessionContextAppendOptions.defaults();
		if (dailyDurableFlushService != null && effectiveOptions.isAllowPreCompactionFlush()) {
			dailyDurableFlushService.flush(sessionId, currentContext);
		}

		List<ChatMessage> compacted = compact(currentContext, sessionId);
		if (!sameSequence(currentContext, compacted)) {
			rewriteSessionContext(sessionId, compacted);
			return contextManager.getContext(sessionId);
		}
		return compacted;
	}

	private List<ChatMessage> compact(List<ChatMessage> currentContext, String sessionId) {
		ChatMessage systemMessage = extractSystemMessage(currentContext, sessionId);
		List<List<ChatMessage>> completeTurns = extractCompleteTurns(currentContext);
		List<List<ChatMessage>> selectedTurns = new LinkedList<>();

		for (int i = completeTurns.size() - 1; i >= 0; i--) {
			selectedTurns.add(0, completeTurns.get(i));
			List<ChatMessage> candidate = buildContext(systemMessage, selectedTurns);
			if (budgetEvaluator.fitsWithinPreflightBudget(candidate) || selectedTurns.size() == 1) {
				continue;
			}
			selectedTurns.remove(0);
			break;
		}

		return buildContext(systemMessage, selectedTurns);
	}

	private List<ChatMessage> buildContext(ChatMessage systemMessage, List<List<ChatMessage>> turns) {
		List<ChatMessage> out = new ArrayList<>();
		if (systemMessage != null) {
			out.add(systemMessage);
		}
		for (List<ChatMessage> turn : turns) {
			out.addAll(turn);
		}
		return out;
	}

	private List<List<ChatMessage>> extractCompleteTurns(List<ChatMessage> currentContext) {
		List<List<ChatMessage>> turns = new ArrayList<>();
		ChatMessage pendingUser = null;
		for (ChatMessage message : currentContext) {
			if (message == null || message.getType() == null || message.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			if (message.getType() == ChatMessageType.USER) {
				pendingUser = message;
				continue;
			}
			if (message.getType() == ChatMessageType.ASSISTANT && pendingUser != null) {
				List<ChatMessage> turn = new ArrayList<>(2);
				turn.add(pendingUser);
				turn.add(message);
				turns.add(turn);
				pendingUser = null;
			}
		}
		return turns;
	}

	private ChatMessage extractSystemMessage(List<ChatMessage> currentContext, String sessionId) {
		for (ChatMessage message : currentContext) {
			if (message != null && message.getType() == ChatMessageType.SYSTEM) {
				return message;
			}
		}
		return new SystemMessage(contextManager.getSystemPrompt(sessionId));
	}

	private void rewriteSessionContext(String sessionId, List<ChatMessage> compacted) {
		contextManager.clear(sessionId);
		String systemPrompt = "";
		for (ChatMessage message : compacted) {
			if (message != null && message.getType() == ChatMessageType.SYSTEM) {
				systemPrompt = message.getContent() != null ? message.getContent() : "";
				break;
			}
		}
		contextManager.ensureSystemPrompt(sessionId, systemPrompt);
		for (ChatMessage message : compacted) {
			if (message == null || message.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			contextManager.addMessage(sessionId, message, SessionContextAppendOptions.withoutPreCompactionFlush());
		}
	}

	private boolean sameSequence(List<ChatMessage> left, List<ChatMessage> right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.size() != right.size()) {
			return false;
		}
		for (int i = 0; i < left.size(); i++) {
			ChatMessage leftMessage = left.get(i);
			ChatMessage rightMessage = right.get(i);
			ChatMessageType leftType = leftMessage != null ? leftMessage.getType() : null;
			ChatMessageType rightType = rightMessage != null ? rightMessage.getType() : null;
			if (leftType != rightType) {
				return false;
			}
			String leftContent = leftMessage != null ? leftMessage.getContent() : null;
			String rightContent = rightMessage != null ? rightMessage.getContent() : null;
			if (leftContent == null ? rightContent != null : !leftContent.equals(rightContent)) {
				return false;
			}
		}
		return true;
	}
}
