package com.agenticrag.app.prompt.contrib;

import com.agenticrag.app.prompt.SystemPromptContext;
import com.agenticrag.app.prompt.SystemPromptContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class MemoryPromptContributor implements SystemPromptContributor {
	@Override
	public String id() {
		return "memory";
	}

	@Override
	public String contribute(SystemPromptContext context) {
		return "";
	}
}

