package com.agenticrag.app.api;

import com.agenticrag.app.llm.LlmProvider;
import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.prompt.SystemPromptMode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/prompt")
public class SystemPromptController {
	private final SystemPromptManager systemPromptManager;

	public SystemPromptController(SystemPromptManager systemPromptManager) {
		this.systemPromptManager = systemPromptManager;
	}

	@GetMapping(value = "/system", produces = MediaType.TEXT_PLAIN_VALUE)
	public String systemPrompt(
		@RequestParam(value = "provider", defaultValue = "OPENAI") LlmProvider provider,
		@RequestParam(value = "tools", defaultValue = "true") boolean tools,
		@RequestParam(value = "mode", defaultValue = "LLM") SystemPromptMode mode
	) {
		return systemPromptManager.build(new SystemPromptContext(provider, tools, mode));
	}
}
