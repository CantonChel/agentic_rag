package com.agenticrag.app.benchmark.api;

import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/benchmark/turn-summaries")
public class BenchmarkTurnExecutionSummaryController {
	private final BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService;

	public BenchmarkTurnExecutionSummaryController(
		BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService
	) {
		this.benchmarkTurnExecutionSummaryService = benchmarkTurnExecutionSummaryService;
	}

	@GetMapping(value = "/{turnId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<BenchmarkTurnExecutionSummaryView> getTurnSummary(
		@PathVariable("turnId") String turnId
	) {
		return Mono.fromCallable(() -> benchmarkTurnExecutionSummaryService.findViewByTurnId(turnId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "turn summary not found")))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<BenchmarkTurnExecutionSummaryView>> listTurnSummaries(
		@RequestParam(value = "sessionId", required = false) String sessionId,
		@RequestParam(value = "buildId", required = false) String buildId,
		@RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
		@RequestParam(value = "traceId", required = false) String traceId,
		@RequestParam(value = "provider", required = false) String provider,
		@RequestParam(value = "evalMode", required = false) String evalMode,
		@RequestParam(value = "finishReason", required = false) String finishReason
	) {
		return Mono.fromCallable(() -> benchmarkTurnExecutionSummaryService.listViews(
			sessionId,
			buildId,
			knowledgeBaseId,
			traceId,
			provider,
			evalMode,
			finishReason
		)).subscribeOn(Schedulers.boundedElastic());
	}
}
