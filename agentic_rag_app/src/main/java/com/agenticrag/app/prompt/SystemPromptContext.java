package com.agenticrag.app.prompt;

import com.agenticrag.app.llm.LlmProvider;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class SystemPromptContext {
	private final LlmProvider provider;
	private final boolean includeTools;
	private final SystemPromptMode mode;
	private final boolean memoryEnabled;
	private final Set<String> allowedToolNames;

	public SystemPromptContext(LlmProvider provider, boolean includeTools) {
		this(provider, includeTools, SystemPromptMode.LLM);
	}

	public SystemPromptContext(LlmProvider provider, boolean includeTools, SystemPromptMode mode) {
		this(provider, includeTools, mode, true, null);
	}

	public SystemPromptContext(
		LlmProvider provider,
		boolean includeTools,
		SystemPromptMode mode,
		boolean memoryEnabled,
		Collection<String> allowedToolNames
	) {
		this.provider = provider;
		this.includeTools = includeTools;
		this.mode = mode != null ? mode : SystemPromptMode.LLM;
		this.memoryEnabled = memoryEnabled;
		this.allowedToolNames = toAllowedToolNames(allowedToolNames);
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

	public boolean isMemoryEnabled() {
		return memoryEnabled;
	}

	public Set<String> getAllowedToolNames() {
		return allowedToolNames;
	}

	private Set<String> toAllowedToolNames(Collection<String> names) {
		if (names == null) {
			return Collections.emptySet();
		}
		Set<String> out = new LinkedHashSet<>();
		for (String name : names) {
			if (name == null) {
				continue;
			}
			String normalized = name.trim();
			if (!normalized.isEmpty()) {
				out.add(normalized);
			}
		}
		return Collections.unmodifiableSet(out);
	}
}
