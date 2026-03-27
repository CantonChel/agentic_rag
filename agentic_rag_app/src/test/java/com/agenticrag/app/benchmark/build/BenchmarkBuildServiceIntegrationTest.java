package com.agenticrag.app.benchmark.build;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkBuildServiceIntegrationTest {
	@Autowired
	private BenchmarkBuildService benchmarkBuildService;

	@Autowired
	private BenchmarkBuildRepository benchmarkBuildRepository;

	@BeforeEach
	void setUp() {
		benchmarkBuildRepository.deleteAll();
	}

	@Test
	void createPendingBuildStoresStableKnowledgeBaseMapping() {
		BenchmarkBuildEntity entity = benchmarkBuildService.createPendingBuild(
			"/tmp/package",
			"api_docs",
			"base_v1",
			"snapshot-1",
			"evidence_unit_identity_v1",
			"text-embedding-3-small",
			12,
			4
		);

		Assertions.assertNotNull(entity.getBuildId());
		Assertions.assertEquals("bm-" + entity.getBuildId(), entity.getKnowledgeBaseId());
		Assertions.assertEquals(BenchmarkBuildStatus.PENDING, entity.getStatus());
		Assertions.assertEquals(12, entity.getEvidenceCount());
		Assertions.assertEquals(4, entity.getSampleCount());
	}

	@Test
	void transitionsBuildStatusesAndPreservesMapping() {
		BenchmarkBuildEntity created = benchmarkBuildService.createPendingBuild(
			"/tmp/package",
			"api_docs",
			"base_v1",
			"snapshot-1",
			"evidence_unit_identity_v1",
			"text-embedding-3-small",
			5,
			2
		);

		BenchmarkBuildEntity building = benchmarkBuildService.markBuilding(created.getBuildId());
		Assertions.assertEquals(BenchmarkBuildStatus.BUILDING, building.getStatus());
		Assertions.assertNull(building.getFinishedAt());

		BenchmarkBuildEntity failed = benchmarkBuildService.markFailed(created.getBuildId(), "broken package");
		Assertions.assertEquals(BenchmarkBuildStatus.FAILED, failed.getStatus());
		Assertions.assertEquals("broken package", failed.getErrorMessage());
		Assertions.assertNotNull(failed.getFinishedAt());

		BenchmarkBuildEntity ready = benchmarkBuildService.markReady(created.getBuildId());
		Assertions.assertEquals(BenchmarkBuildStatus.READY, ready.getStatus());
		Assertions.assertNull(ready.getErrorMessage());
		Assertions.assertNotNull(ready.getFinishedAt());
		Assertions.assertEquals(created.getKnowledgeBaseId(), ready.getKnowledgeBaseId());
	}
}
