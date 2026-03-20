package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.model.KnowledgeUploadResult;
import com.agenticrag.app.ingest.service.KnowledgeIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeIngestController {
	private final KnowledgeIngestService knowledgeIngestService;
	private final ObjectMapper objectMapper;

	public KnowledgeIngestController(KnowledgeIngestService knowledgeIngestService, ObjectMapper objectMapper) {
		this.knowledgeIngestService = knowledgeIngestService;
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/{kbId}/knowledge/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<UploadResponse> upload(
		@PathVariable("kbId") String knowledgeBaseId,
		@RequestPart("file") FilePart file,
		@RequestPart(value = "metadata", required = false) String metadataJson
	) {
		if (file == null) {
			return Mono.just(new UploadResponse(null, null, "invalid_request"));
		}
		return DataBufferUtils.join(file.content())
			.map(buffer -> {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				DataBufferUtils.release(buffer);
				return bytes;
			})
			.flatMap(bytes -> Mono.fromCallable(() -> {
				Map<String, Object> metadata = parseMetadata(metadataJson);
				if (!metadata.containsKey("source")) {
					metadata.put("source", file.filename());
				}
				KnowledgeUploadResult result = knowledgeIngestService.createAndEnqueue(knowledgeBaseId, file.filename(), bytes, metadata);
				return new UploadResponse(result.getKnowledgeId(), result.getJobId(), result.getStatus().name().toLowerCase());
			}).subscribeOn(Schedulers.boundedElastic()));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseMetadata(String json) {
		if (json == null || json.trim().isEmpty()) {
			return new HashMap<>();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), Map.class);
			return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
		} catch (Exception e) {
			return new HashMap<>();
		}
	}

	public static class UploadResponse {
		private final String knowledgeId;
		private final String jobId;
		private final String status;

		public UploadResponse(String knowledgeId, String jobId, String status) {
			this.knowledgeId = knowledgeId;
			this.jobId = jobId;
			this.status = status;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getJobId() {
			return jobId;
		}

		public String getStatus() {
			return status;
		}
	}
}
