package com.agenticrag.app.benchmark.execution;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BenchmarkTurnExecutionSummaryServiceTest {
	@Test
	void resolvesBuildIdAndSerializesJsonFields() {
		BenchmarkTurnExecutionSummaryRepository repository = Mockito.mock(BenchmarkTurnExecutionSummaryRepository.class);
		BenchmarkBuildService buildService = Mockito.mock(BenchmarkBuildService.class);
		ObjectMapper objectMapper = new ObjectMapper();
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		Mockito.when(buildService.findBuildByKnowledgeBaseId("kb-1")).thenReturn(Optional.of(build));

		BenchmarkTurnExecutionSummaryService service = new BenchmarkTurnExecutionSummaryService(repository, buildService, objectMapper);
		service.saveSummary(
			new BenchmarkTurnExecutionSummaryWriteModel(
				"turn-1",
				"session-1",
				"user-1",
				"trace-1",
				"OPENAI",
				"gpt-test",
				null,
				"kb-1",
				"AUTO",
				"SINGLE_TURN",
				"HIDE",
				false,
				"question",
				"answer",
				"stop",
				123L,
				List.of(new BenchmarkTurnExecutionToolCall("call-1", "search_knowledge_base", "success", 12L, null)),
				List.of("trace-1"),
				List.of(new BenchmarkTurnRetrievalTraceRef("trace-1", "call-1", "search_knowledge_base")),
				null,
				Instant.parse("2026-03-28T00:00:00Z"),
				Instant.parse("2026-03-28T00:00:01Z")
			)
		);

		ArgumentCaptor<BenchmarkTurnExecutionSummaryEntity> captor = ArgumentCaptor.forClass(BenchmarkTurnExecutionSummaryEntity.class);
		Mockito.verify(repository).save(captor.capture());
		BenchmarkTurnExecutionSummaryEntity saved = captor.getValue();
		Assertions.assertEquals("build-1", saved.getBuildId());
		Assertions.assertEquals("openai", saved.getProvider());
		Assertions.assertTrue(saved.getToolCallsJson().contains("search_knowledge_base"));
		Assertions.assertTrue(saved.getRetrievalTraceIdsJson().contains("trace-1"));
		Assertions.assertTrue(saved.getRetrievalTraceRefsJson().contains("call-1"));
	}
}
