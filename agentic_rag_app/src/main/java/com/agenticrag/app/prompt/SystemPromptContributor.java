package com.agenticrag.app.prompt;

public interface SystemPromptContributor {
	String id();

	String contribute(SystemPromptContext context);
}

