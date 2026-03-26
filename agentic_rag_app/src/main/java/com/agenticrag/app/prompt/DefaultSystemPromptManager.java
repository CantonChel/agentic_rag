package com.agenticrag.app.prompt;

import java.util.Collections;
import java.util.List;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

@Service
public class DefaultSystemPromptManager implements SystemPromptManager {
	private final SystemPromptProperties properties;
	private final List<SystemPromptContributor> contributors;

	public DefaultSystemPromptManager(SystemPromptProperties properties, List<SystemPromptContributor> contributors) {
		this.properties = properties;
		this.contributors = contributors;
		Collections.sort(this.contributors, AnnotationAwareOrderComparator.INSTANCE);
	}

	@Override
	public String build(SystemPromptContext context) {
		String base = resolveBasePrompt(context);
		if (base == null) {
			base = "";
		}

		StringBuilder out = new StringBuilder();
		if (!base.trim().isEmpty()) {
			out.append(base.trim());
		}

		for (SystemPromptContributor contributor : contributors) {
			String chunk = contributor.contribute(context);
			if (chunk == null || chunk.trim().isEmpty()) {
				continue;
			}
			if (out.length() > 0) {
				out.append("\n\n");
			}
			out.append(chunk.trim());
		}
		return out.toString();
	}

	private String resolveBasePrompt(SystemPromptContext context) {
		if (context != null && context.isAgentMode()) {
			String agentBase = properties.getAgentBase();
			if (agentBase != null && !agentBase.trim().isEmpty()) {
				return agentBase;
			}
		}
		return properties.getBase();
	}
}
