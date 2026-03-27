package com.agenticrag.app.tool;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ToolRouter {
	private final Map<String, Tool> toolsByName = new LinkedHashMap<>();

	public void register(Tool tool) {
		toolsByName.put(tool.name(), tool);
	}

	public Optional<Tool> getTool(String name) {
		return Optional.ofNullable(toolsByName.get(name));
	}

	public Collection<Tool> getTools() {
		return toolsByName.values();
	}

	public Collection<ToolDefinition> getToolDefinitions() {
		return getToolDefinitions(null);
	}

	public Collection<ToolDefinition> getToolDefinitions(Collection<String> allowedToolNames) {
		final LinkedHashSet<String> allowed = allowedToolNames == null
			? null
			: allowedToolNames.stream()
				.filter(name -> name != null && !name.trim().isEmpty())
				.map(String::trim)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		return toolsByName.values().stream()
			.filter(t -> allowed == null || allowed.contains(t.name()))
			.map(t -> new ToolDefinition(t.name(), t.description(), t.parametersSchema()))
			.collect(Collectors.toList());
	}
}
