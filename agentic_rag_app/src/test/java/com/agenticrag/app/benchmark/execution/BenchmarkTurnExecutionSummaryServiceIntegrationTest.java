package com.agenticrag.app.benchmark.execution;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildRepository;
import com.agenticrag.app.benchmark.build.BenchmarkBuildStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkTurnExecutionSummaryServiceIntegrationTest {
	@Autowired
	private BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService;

	@Autowired
	private BenchmarkTurnExecutionSummaryRepository benchmarkTurnExecutionSummaryRepository;

	@Autowired
	private BenchmarkBuildRepository benchmarkBuildRepository;

	@BeforeEach
	void setUp() {
		benchmarkTurnExecutionSummaryRepository.deleteAll();
		benchmarkBuildRepository.deleteAll();
	}

	@Test
	void persistsAndReadsSummaryView() {
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		build.setKnowledgeBaseId("kb-1");
		build.setPackagePath("/tmp/package");
		build.setProjectKey("api_docs");
		build.setSuiteVersion("v1");
		build.setSourceSnapshotId("snapshot-1");
		build.setChunkStrategyVersion("strategy-1");
		build.setEmbeddingModel("text-embedding-3-small");
		build.setEvidenceCount(3);
		build.setSampleCount(1);
		build.setStatus(BenchmarkBuildStatus.READY);
		build.setCreatedAt(Instant.parse("2026-03-28T00:00:00Z"));
		build.setUpdatedAt(Instant.parse("2026-03-28T00:00:00Z"));
		build.setFinishedAt(Instant.parse("2026-03-28T00:00:00Z"));
		benchmarkBuildRepository.save(build);

		benchmarkTurnExecutionSummaryService.saveSummary(
			new BenchmarkTurnExecutionSummaryWriteModel(
				"turn-1",
				"session-1",
				"user-1",
				"trace-1",
				"OPENAI",
				"gpt-test",
				null,
				"kb-1",
				"benchmark_build",
				"single_turn",
				"hide",
				false,
				"question",
				"answer",
				"stop",
				321L,
				List.of(new BenchmarkTurnExecutionToolCall("call-1", "search_knowledge_base", "success", 44L, null)),
				List.of("trace-1"),
				List.of(new BenchmarkTurnRetrievalTraceRef("trace-1", "call-1", "search_knowledge_base")),
				null,
				Instant.parse("2026-03-28T00:00:00Z"),
				Instant.parse("2026-03-28T00:00:02Z")
			)
		);

		BenchmarkTurnExecutionSummaryView view = benchmarkTurnExecutionSummaryService.findViewByTurnId("turn-1").orElseThrow();
		Assertions.assertEquals("build-1", view.getBuildId());
		Assertions.assertEquals("kb-1", view.getKnowledgeBaseId());
		Assertions.assertEquals("question", view.getUserQuestion());
		Assertions.assertEquals("answer", view.getFinalAnswer());
		Assertions.assertEquals(1, view.getToolCalls().size());
		Assertions.assertEquals("call-1", view.getToolCalls().get(0).toolCallId());
		Assertions.assertEquals(List.of("trace-1"), view.getRetrievalTraceIds());
		Assertions.assertEquals(1, benchmarkTurnExecutionSummaryRepository.count());
	}
}
