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
		if (context == null || !context.isMemoryEnabled()) {
			return "";
		}
		boolean hasSearch = context.getAllowedToolNames().contains("memory_search");
		boolean hasGet = context.getAllowedToolNames().contains("memory_get");
		if (!hasSearch && !hasGet) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		out.append("## Memory Recall\n");
		if (hasSearch && hasGet) {
			out.append("Before answering anything about prior work, decisions, dates, people, preferences, or todos: run memory_search on MEMORY.md + memory/*.md; then use memory_get to pull only the needed lines. If low confidence after search, say you checked.\n");
		} else if (hasSearch) {
			out.append("Before answering anything about prior work, decisions, dates, people, preferences, or todos: run memory_search on MEMORY.md + memory/*.md and answer from the matching results. If low confidence after search, say you checked.\n");
		} else {
			out.append("Before answering anything about prior work, decisions, dates, people, preferences, or todos that already point to a specific memory file or note: run memory_get to pull only the needed lines. If low confidence after reading them, say you checked.\n");
		}
		out.append("Treat MEMORY.md as read-only reference. Never edit or overwrite MEMORY.md in runtime.\n");
		out.append("Runtime memory writes must go to memory/users/<userId>/... files only.");
		return out.toString();
	}
}
