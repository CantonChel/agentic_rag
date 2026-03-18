package com.agenticrag.app.prompt;

import com.agenticrag.app.llm.LlmProvider;

public class SystemPromptContext {
	private final LlmProvider provider;
	private final boolean includeTools;

	public SystemPromptContext(LlmProvider provider, boolean includeTools) {
		this.provider = provider;
		this.includeTools = includeTools;
	}

	public LlmProvider getProvider() {
		return provider;
	}

	public boolean isIncludeTools() {
		return includeTools;
	}
}

