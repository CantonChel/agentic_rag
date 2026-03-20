package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.service.KnowledgeIngestService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/jobs")
public class ParseJobController {
	private final KnowledgeIngestService knowledgeIngestService;

	public ParseJobController(KnowledgeIngestService knowledgeIngestService) {
		this.knowledgeIngestService = knowledgeIngestService;
	}

	@GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<JobStatusResponse>> getJob(@PathVariable("jobId") String jobId) {
		return Mono.fromCallable(() -> {
			Optional<ParseJobEntity> opt = knowledgeIngestService.getJob(jobId);
			if (!opt.isPresent()) {
				return ResponseEntity.<JobStatusResponse>notFound().build();
			}
			ParseJobEntity job = opt.get();
			return ResponseEntity.ok(JobStatusResponse.from(job));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/{jobId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> retry(@PathVariable("jobId") String jobId) {
		return Mono.fromCallable(() -> {
			boolean ok = knowledgeIngestService.retryJob(jobId);
			Map<String, Object> body = new HashMap<>();
			body.put("job_id", jobId);
			body.put("accepted", ok);
			if (!ok) {
				body.put("message", "only failed/dead_letter jobs can retry");
				return ResponseEntity.badRequest().body(body);
			}
			return ResponseEntity.ok(body);
		}).subscribeOn(Schedulers.boundedElastic());
	}

	public static class JobStatusResponse {
		private String jobId;
		private String knowledgeId;
		private String status;
		private int retryCount;
		private int maxRetry;
		private String nextRetryAt;
		private String leaseUntil;
		private String lastErrorCode;
		private String lastErrorMessage;
		private String updatedAt;

		public static JobStatusResponse from(ParseJobEntity e) {
			JobStatusResponse out = new JobStatusResponse();
			out.jobId = e.getId();
			out.knowledgeId = e.getKnowledgeId();
			out.status = e.getStatus() != null ? e.getStatus().name().toLowerCase() : "unknown";
			out.retryCount = e.getRetryCount();
			out.maxRetry = e.getMaxRetry();
			out.nextRetryAt = e.getNextRetryAt() != null ? e.getNextRetryAt().toString() : null;
			out.leaseUntil = e.getLeaseUntil() != null ? e.getLeaseUntil().toString() : null;
			out.lastErrorCode = e.getLastErrorCode();
			out.lastErrorMessage = e.getLastErrorMessage();
			out.updatedAt = e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : null;
			return out;
		}

		public String getJobId() {
			return jobId;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getStatus() {
			return status;
		}

		public int getRetryCount() {
			return retryCount;
		}

		public int getMaxRetry() {
			return maxRetry;
		}

		public String getNextRetryAt() {
			return nextRetryAt;
		}

		public String getLeaseUntil() {
			return leaseUntil;
		}

		public String getLastErrorCode() {
			return lastErrorCode;
		}

		public String getLastErrorMessage() {
			return lastErrorMessage;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}
}
