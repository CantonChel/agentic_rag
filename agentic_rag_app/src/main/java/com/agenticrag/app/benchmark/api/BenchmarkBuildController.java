package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportRequest;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildStatus;
import com.agenticrag.app.benchmark.build.BenchmarkBuildView;
import com.agenticrag.app.benchmark.mapping.BenchmarkBuildChunkMappingService;
import com.agenticrag.app.benchmark.mapping.BenchmarkBuildChunkMappingView;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/benchmark/builds")
public class BenchmarkBuildController {
	private final BenchmarkBuildImportService benchmarkBuildImportService;
	private final BenchmarkBuildService benchmarkBuildService;
	private final BenchmarkBuildChunkMappingService benchmarkBuildChunkMappingService;

	public BenchmarkBuildController(
		BenchmarkBuildImportService benchmarkBuildImportService,
		BenchmarkBuildService benchmarkBuildService,
		BenchmarkBuildChunkMappingService benchmarkBuildChunkMappingService
	) {
		this.benchmarkBuildImportService = benchmarkBuildImportService;
		this.benchmarkBuildService = benchmarkBuildService;
		this.benchmarkBuildChunkMappingService = benchmarkBuildChunkMappingService;
	}

	@PostMapping(value = "/import-package", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<BenchmarkBuildView> importPackage(
		@RequestBody(required = false) BenchmarkBuildImportRequest request
	) {
		String packagePath = request != null ? normalizeNullable(request.getPackagePath()) : null;
		if (packagePath == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packagePath is required");
		}
		return Mono.fromCallable(() -> {
			BenchmarkBuildEntity build = benchmarkBuildImportService.importPackage(Path.of(packagePath));
			return benchmarkBuildService.getBuildView(build.getBuildId());
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<BenchmarkBuildView>> listBuilds(
		@RequestParam(value = "projectKey", required = false) String projectKey,
		@RequestParam(value = "status", required = false) String status
	) {
		BenchmarkBuildStatus parsedStatus = parseStatus(status);
		return Mono.fromCallable(() -> benchmarkBuildService.listBuilds(projectKey, parsedStatus))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/{buildId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<BenchmarkBuildView> getBuild(
		@PathVariable("buildId") String buildId
	) {
		return Mono.fromCallable(() -> benchmarkBuildService.findBuildView(buildId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "build not found")))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/{buildId}/chunk-mappings", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<BenchmarkBuildChunkMappingView>> listChunkMappings(
		@PathVariable("buildId") String buildId,
		@RequestParam(value = "chunkId", required = false) String chunkId
	) {
		return Mono.fromCallable(() -> benchmarkBuildChunkMappingService.listMappings(buildId, chunkId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private BenchmarkBuildStatus parseStatus(String status) {
		String normalized = normalizeNullable(status);
		if (normalized == null) {
			return null;
		}
		try {
			return BenchmarkBuildStatus.valueOf(normalized.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid build status");
		}
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
