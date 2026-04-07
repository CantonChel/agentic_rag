package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.api.ApiExceptionHandler;
import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportRequest;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildStatus;
import com.agenticrag.app.benchmark.build.BenchmarkBuildView;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(controllers = BenchmarkBuildController.class)
@Import(ApiExceptionHandler.class)
class BenchmarkBuildControllerTest {
	@Autowired
	private WebTestClient webTestClient;

	@MockBean
	private BenchmarkBuildImportService benchmarkBuildImportService;

	@MockBean
	private BenchmarkBuildService benchmarkBuildService;

	@Test
	void importPackageShouldReturnImportedBuildView() {
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		BenchmarkBuildView view = buildView("build-1", "bm-build-1", "demo", "v1", "ready");

		Mockito.when(benchmarkBuildImportService.importPackage(Mockito.any(Path.class))).thenReturn(build);
		Mockito.when(benchmarkBuildService.getBuildView("build-1")).thenReturn(view);

		webTestClient.post()
			.uri("/api/benchmark/builds/import-package")
			.bodyValue(importRequest("/tmp/pkg"))
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$.buildId").isEqualTo("build-1")
			.jsonPath("$.knowledgeBaseId").isEqualTo("bm-build-1")
			.jsonPath("$.projectKey").isEqualTo("demo");

		ArgumentCaptor<Path> pathCaptor = ArgumentCaptor.forClass(Path.class);
		Mockito.verify(benchmarkBuildImportService).importPackage(pathCaptor.capture());
		org.junit.jupiter.api.Assertions.assertEquals(Path.of("/tmp/pkg"), pathCaptor.getValue());
	}

	@Test
	void importPackageShouldRejectMissingPackagePath() {
		webTestClient.post()
			.uri("/api/benchmark/builds/import-package")
			.bodyValue(new BenchmarkBuildImportRequest())
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.message").isEqualTo("packagePath is required");
	}

	@Test
	void listBuildsShouldSupportProjectKeyAndStatusFilter() {
		BenchmarkBuildView view = buildView("build-2", "bm-build-2", "demo", "v2", "ready");
		Mockito.when(benchmarkBuildService.listBuilds("demo", BenchmarkBuildStatus.READY)).thenReturn(List.of(view));

		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/builds")
				.queryParam("projectKey", "demo")
				.queryParam("status", "ready")
				.build())
			.exchange()
			.expectStatus().isOk()
			.expectBody()
			.jsonPath("$[0].buildId").isEqualTo("build-2")
			.jsonPath("$[0].status").isEqualTo("ready");
	}

	@Test
	void listBuildsShouldRejectInvalidStatus() {
		webTestClient.get()
			.uri(uriBuilder -> uriBuilder.path("/api/benchmark/builds")
				.queryParam("status", "broken")
				.build())
			.exchange()
			.expectStatus().isBadRequest()
			.expectBody()
			.jsonPath("$.message").isEqualTo("invalid build status");
	}

	@Test
	void getBuildShouldReturnNotFoundWhenMissing() {
		Mockito.when(benchmarkBuildService.findBuildView("missing")).thenReturn(Optional.empty());

		webTestClient.get()
			.uri("/api/benchmark/builds/missing")
			.exchange()
			.expectStatus().isNotFound()
			.expectBody()
			.jsonPath("$.message").isEqualTo("build not found");
	}

	private BenchmarkBuildImportRequest importRequest(String packagePath) {
		BenchmarkBuildImportRequest request = new BenchmarkBuildImportRequest();
		request.setPackagePath(packagePath);
		return request;
	}

	private BenchmarkBuildView buildView(
		String buildId,
		String knowledgeBaseId,
		String projectKey,
		String suiteVersion,
		String status
	) {
		return new BenchmarkBuildView(
			buildId,
			knowledgeBaseId,
			"/tmp/pkg",
			projectKey,
			suiteVersion,
			"snapshot-1",
			"runtime-config-1",
			"text-embedding-3-large",
			3,
			"source-set-1",
			"v1",
			"gold_stage1_v1",
			2,
			2,
			3,
			2,
			status,
			null,
			"2026-03-27T00:00:00Z",
			"2026-03-27T00:00:00Z",
			"2026-03-27T00:00:01Z"
		);
	}
}
