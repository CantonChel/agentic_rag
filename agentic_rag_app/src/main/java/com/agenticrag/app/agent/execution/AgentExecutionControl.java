package com.agenticrag.app.agent.execution;

public class AgentExecutionControl {
	private final String buildId;
	private final String knowledgeBaseId;
	private final AgentKbScope kbScope;
	private final AgentEvalMode evalMode;
	private final AgentThinkingProfile thinkingProfile;
	private final boolean memoryEnabled;

	public AgentExecutionControl(
		String buildId,
		String knowledgeBaseId,
		AgentKbScope kbScope,
		AgentEvalMode evalMode,
		AgentThinkingProfile thinkingProfile,
		boolean memoryEnabled
	) {
		this.buildId = normalizeNullable(buildId);
		this.knowledgeBaseId = normalizeNullable(knowledgeBaseId);
		this.kbScope = kbScope != null ? kbScope : AgentKbScope.AUTO;
		this.evalMode = evalMode != null ? evalMode : AgentEvalMode.DEFAULT;
		this.thinkingProfile = thinkingProfile != null ? thinkingProfile : AgentThinkingProfile.DEFAULT;
		this.memoryEnabled = memoryEnabled;
	}

	public static AgentExecutionControl defaults(String knowledgeBaseId) {
		return new AgentExecutionControl(
			null,
			knowledgeBaseId,
			AgentKbScope.AUTO,
			AgentEvalMode.DEFAULT,
			AgentThinkingProfile.DEFAULT,
			true
		);
	}

	public String getBuildId() {
		return buildId;
	}

	public String getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public AgentKbScope getKbScope() {
		return kbScope;
	}

	public AgentEvalMode getEvalMode() {
		return evalMode;
	}

	public AgentThinkingProfile getThinkingProfile() {
		return thinkingProfile;
	}

	public boolean isMemoryEnabled() {
		return memoryEnabled;
	}

	private static String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
