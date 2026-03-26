package com.agenticrag.app.prompt;

import com.agenticrag.app.llm.LlmProvider;

public class SystemPromptContext {
	private final LlmProvider provider;
	private final boolean includeTools;
	private final SystemPromptMode mode;

	public SystemPromptContext(LlmProvider provider, boolean includeTools) {
		this(provider, includeTools, SystemPromptMode.LLM);
	}

	public SystemPromptContext(LlmProvider provider, boolean includeTools, SystemPromptMode mode) {
		this.provider = provider;
		this.includeTools = includeTools;
		this.mode = mode != null ? mode : SystemPromptMode.LLM;
	}

	public LlmProvider getProvider() {
		return provider;
	}

	public boolean isIncludeTools() {
		return includeTools;
	}

	public SystemPromptMode getMode() {
		return mode;
	}

	public boolean isAgentMode() {
		return mode == SystemPromptMode.AGENT;
	}
}
