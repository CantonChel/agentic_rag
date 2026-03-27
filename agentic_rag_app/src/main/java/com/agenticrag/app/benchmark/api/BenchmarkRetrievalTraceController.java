package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceService;
import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceView;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceRecordType;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/benchmark/retrieval-traces")
public class BenchmarkRetrievalTraceController {
	private final BenchmarkRetrievalTraceService benchmarkRetrievalTraceService;

	public BenchmarkRetrievalTraceController(BenchmarkRetrievalTraceService benchmarkRetrievalTraceService) {
		this.benchmarkRetrievalTraceService = benchmarkRetrievalTraceService;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<BenchmarkRetrievalTraceView>> listRetrievalTraces(
		@RequestParam(value = "traceId", required = false) String traceId,
		@RequestParam(value = "toolCallId", required = false) String toolCallId,
		@RequestParam(value = "toolName", required = false) String toolName,
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam(value = "buildId", required = false) String buildId,
		@RequestParam(value = "stage", required = false) String stage,
		@RequestParam(value = "recordType", required = false) String recordType
	) {
		String normalizedTraceId = normalizeNullable(traceId);
		if (normalizedTraceId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "traceId is required");
		}
		RetrievalTraceStage parsedStage = parseStage(stage);
		RetrievalTraceRecordType parsedRecordType = parseRecordType(recordType);
		return Mono.fromCallable(() -> benchmarkRetrievalTraceService.listTraceViews(
			normalizedTraceId,
			toolCallId,
			toolName,
			knowledgeBaseId,
			buildId,
			parsedStage,
			parsedRecordType
		)).subscribeOn(Schedulers.boundedElastic());
	}

	private RetrievalTraceStage parseStage(String stage) {
		String normalized = normalizeNullable(stage);
		if (normalized == null) {
			return null;
		}
		RetrievalTraceStage parsed = RetrievalTraceStage.fromValue(normalized);
		if (parsed == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid retrieval stage");
		}
		return parsed;
	}

	private RetrievalTraceRecordType parseRecordType(String recordType) {
		String normalized = normalizeNullable(recordType);
		if (normalized == null) {
			return null;
		}
		RetrievalTraceRecordType parsed = RetrievalTraceRecordType.fromValue(normalized);
		if (parsed == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid retrieval record type");
		}
		return parsed;
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String normalized = value.trim();
		return normalized.isEmpty() ? null : normalized;
	}
}
