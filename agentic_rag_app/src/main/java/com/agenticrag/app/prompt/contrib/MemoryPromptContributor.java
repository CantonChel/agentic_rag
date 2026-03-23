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
		return "### Memory Policy\n"
			+ "- For questions about past decisions, prior tasks, preferences, dates, or reminders, call memory_search first.\n"
			+ "- memory_search is user-scoped: only current user's memory can be recalled.\n"
			+ "- Treat MEMORY.md as read-only reference. Never edit or overwrite MEMORY.md in runtime.\n"
			+ "- Runtime memory writes must go to memory/users/<userId>/... files only.";
	}
}
