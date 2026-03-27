package com.agenticrag.app.agent.execution;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AgentExecutionControlResolverTest {
	@Test
	void resolvesBenchmarkBuildToKnowledgeBaseId() {
		BenchmarkBuildService benchmarkBuildService = Mockito.mock(BenchmarkBuildService.class);
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		build.setKnowledgeBaseId("kb-1");
		Mockito.when(benchmarkBuildService.findBuild("build-1")).thenReturn(Optional.of(build));

		AgentExecutionControlResolver resolver = new AgentExecutionControlResolver(benchmarkBuildService);
		AgentExecutionControl control = resolver.resolve(
			"build-1",
			null,
			AgentKbScope.BENCHMARK_BUILD,
			AgentEvalMode.DEFAULT,
			AgentThinkingProfile.DEFAULT,
			true
		);

		Assertions.assertEquals("build-1", control.getBuildId());
		Assertions.assertEquals("kb-1", control.getKnowledgeBaseId());
		Assertions.assertEquals(AgentKbScope.BENCHMARK_BUILD, control.getKbScope());
		Assertions.assertTrue(control.isMemoryEnabled());
	}

	@Test
	void rejectsGlobalScopeWithKnowledgeBaseInputs() {
		BenchmarkBuildService benchmarkBuildService = Mockito.mock(BenchmarkBuildService.class);
		AgentExecutionControlResolver resolver = new AgentExecutionControlResolver(benchmarkBuildService);

		ResponseStatusException exception = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> resolver.resolve(
				null,
				"kb-1",
				AgentKbScope.GLOBAL,
				AgentEvalMode.DEFAULT,
				AgentThinkingProfile.DEFAULT,
				true
			)
		);

		Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
	}

	@Test
	void rejectsMismatchedBuildAndKnowledgeBase() {
		BenchmarkBuildService benchmarkBuildService = Mockito.mock(BenchmarkBuildService.class);
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		build.setKnowledgeBaseId("kb-1");
		Mockito.when(benchmarkBuildService.findBuild("build-1")).thenReturn(Optional.of(build));

		AgentExecutionControlResolver resolver = new AgentExecutionControlResolver(benchmarkBuildService);

		ResponseStatusException exception = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> resolver.resolve(
				"build-1",
				"kb-2",
				AgentKbScope.AUTO,
				AgentEvalMode.DEFAULT,
				AgentThinkingProfile.DEFAULT,
				true
			)
		);

		Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
	}

	@Test
	void rejectsMissingKnowledgeBaseWhenScopeRequiresIt() {
		BenchmarkBuildService benchmarkBuildService = Mockito.mock(BenchmarkBuildService.class);
		AgentExecutionControlResolver resolver = new AgentExecutionControlResolver(benchmarkBuildService);

		ResponseStatusException exception = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> resolver.resolve(
				null,
				null,
				AgentKbScope.KNOWLEDGE_BASE,
				AgentEvalMode.SINGLE_TURN,
				AgentThinkingProfile.HIDE,
				false
			)
		);

		Assertions.assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
	}

	@Test
	void usesKnowledgeBaseIdWhenAutoScopeHasNoBuild() {
		BenchmarkBuildService benchmarkBuildService = Mockito.mock(BenchmarkBuildService.class);
		AgentExecutionControlResolver resolver = new AgentExecutionControlResolver(benchmarkBuildService);

		AgentExecutionControl control = resolver.resolve(
			null,
			"kb-1",
			AgentKbScope.AUTO,
			null,
			null,
			null
		);

		Assertions.assertEquals("kb-1", control.getKnowledgeBaseId());
		Assertions.assertEquals(AgentKbScope.AUTO, control.getKbScope());
		Assertions.assertEquals(AgentEvalMode.DEFAULT, control.getEvalMode());
		Assertions.assertEquals(AgentThinkingProfile.DEFAULT, control.getThinkingProfile());
		Assertions.assertTrue(control.isMemoryEnabled());
	}
}
