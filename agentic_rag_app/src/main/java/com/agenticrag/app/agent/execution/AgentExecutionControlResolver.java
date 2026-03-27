package com.agenticrag.app.agent.execution;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentExecutionControlResolver {
	private final BenchmarkBuildService benchmarkBuildService;

	public AgentExecutionControlResolver(BenchmarkBuildService benchmarkBuildService) {
		this.benchmarkBuildService = benchmarkBuildService;
	}

	public AgentExecutionControl resolve(
		String buildId,
		String knowledgeBaseId,
		AgentKbScope kbScope,
		AgentEvalMode evalMode,
		AgentThinkingProfile thinkingProfile,
		Boolean memoryEnabled
	) {
		String normalizedBuildId = normalizeNullable(buildId);
		String normalizedKnowledgeBaseId = normalizeNullable(knowledgeBaseId);
		AgentKbScope effectiveKbScope = kbScope != null ? kbScope : AgentKbScope.AUTO;
		AgentEvalMode effectiveEvalMode = evalMode != null ? evalMode : AgentEvalMode.DEFAULT;
		AgentThinkingProfile effectiveThinkingProfile = thinkingProfile != null ? thinkingProfile : AgentThinkingProfile.DEFAULT;
		boolean effectiveMemoryEnabled = memoryEnabled == null || memoryEnabled;

		if (effectiveKbScope == AgentKbScope.GLOBAL) {
			if (normalizedBuildId != null || normalizedKnowledgeBaseId != null) {
				throw badRequest("kbScope=GLOBAL does not allow buildId or knowledgeBaseId");
			}
			return new AgentExecutionControl(null, null, effectiveKbScope, effectiveEvalMode, effectiveThinkingProfile, effectiveMemoryEnabled);
		}

		String resolvedFromBuild = null;
		if (normalizedBuildId != null) {
			resolvedFromBuild = resolveKnowledgeBaseIdFromBuild(normalizedBuildId);
		}

		if (effectiveKbScope == AgentKbScope.KNOWLEDGE_BASE) {
			if (normalizedKnowledgeBaseId == null) {
				throw badRequest("knowledgeBaseId is required when kbScope=KNOWLEDGE_BASE");
			}
			assertNoMismatch(normalizedBuildId, resolvedFromBuild, normalizedKnowledgeBaseId);
			return new AgentExecutionControl(
				normalizedBuildId,
				normalizedKnowledgeBaseId,
				effectiveKbScope,
				effectiveEvalMode,
				effectiveThinkingProfile,
				effectiveMemoryEnabled
			);
		}

		if (effectiveKbScope == AgentKbScope.BENCHMARK_BUILD) {
			if (normalizedBuildId == null) {
				throw badRequest("buildId is required when kbScope=BENCHMARK_BUILD");
			}
			assertNoMismatch(normalizedBuildId, resolvedFromBuild, normalizedKnowledgeBaseId);
			return new AgentExecutionControl(
				normalizedBuildId,
				resolvedFromBuild,
				effectiveKbScope,
				effectiveEvalMode,
				effectiveThinkingProfile,
				effectiveMemoryEnabled
			);
		}

		String resolvedKnowledgeBaseId = resolvedFromBuild != null ? resolvedFromBuild : normalizedKnowledgeBaseId;
		assertNoMismatch(normalizedBuildId, resolvedFromBuild, normalizedKnowledgeBaseId);
		return new AgentExecutionControl(
			normalizedBuildId,
			resolvedKnowledgeBaseId,
			effectiveKbScope,
			effectiveEvalMode,
			effectiveThinkingProfile,
			effectiveMemoryEnabled
		);
	}

	private String resolveKnowledgeBaseIdFromBuild(String buildId) {
		Optional<BenchmarkBuildEntity> build = benchmarkBuildService.findBuild(buildId);
		BenchmarkBuildEntity entity = build.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "build not found"));
		String knowledgeBaseId = normalizeNullable(entity.getKnowledgeBaseId());
		if (knowledgeBaseId == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "build knowledge base not ready");
		}
		return knowledgeBaseId;
	}

	private void assertNoMismatch(String buildId, String resolvedFromBuild, String knowledgeBaseId) {
		if (buildId == null || resolvedFromBuild == null || knowledgeBaseId == null) {
			return;
		}
		if (!resolvedFromBuild.equals(knowledgeBaseId)) {
			throw badRequest("buildId and knowledgeBaseId do not match");
		}
	}

	private ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
