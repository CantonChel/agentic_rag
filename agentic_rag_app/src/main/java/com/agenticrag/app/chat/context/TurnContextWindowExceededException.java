package com.agenticrag.app.chat.context;

public class TurnContextWindowExceededException extends RuntimeException {
	private final int minimalTokenCount;
	private final int contextWindowTokens;

	public TurnContextWindowExceededException(int minimalTokenCount, int contextWindowTokens) {
		super("context_window_exceeded");
		this.minimalTokenCount = minimalTokenCount;
		this.contextWindowTokens = contextWindowTokens;
	}

	public int getMinimalTokenCount() {
		return minimalTokenCount;
	}

	public int getContextWindowTokens() {
		return contextWindowTokens;
	}
}
