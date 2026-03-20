package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.config.DocreaderProperties;
import com.agenticrag.app.ingest.docreader.DocreaderCallbackRequest;
import com.agenticrag.app.ingest.service.CallbackSignatureVerifier;
import com.agenticrag.app.ingest.service.DocreaderCallbackService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/internal/docreader/jobs")
public class DocreaderCallbackController {
	private final ObjectMapper objectMapper;
	private final CallbackSignatureVerifier signatureVerifier;
	private final DocreaderCallbackService callbackService;
	private final DocreaderProperties docreaderProperties;

	public DocreaderCallbackController(
		ObjectMapper objectMapper,
		CallbackSignatureVerifier signatureVerifier,
		DocreaderCallbackService callbackService,
		DocreaderProperties docreaderProperties
	) {
		this.objectMapper = objectMapper;
		this.signatureVerifier = signatureVerifier;
		this.callbackService = callbackService;
		this.docreaderProperties = docreaderProperties;
	}

	@PostMapping(value = "/{jobId}/result", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> onResult(
		@PathVariable("jobId") String jobId,
		@RequestBody String rawBody,
		ServerHttpRequest request
	) {
		return Mono.fromCallable(() -> {
			String signature = request.getHeaders().getFirst(docreaderProperties.getSignatureHeader());
			String timestamp = request.getHeaders().getFirst(docreaderProperties.getTimestampHeader());
			boolean valid = signatureVerifier.verify(timestamp, signature, rawBody);
			if (!valid) {
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid callback signature");
			}

			DocreaderCallbackRequest callbackRequest = objectMapper.readValue(rawBody, DocreaderCallbackRequest.class);
			DocreaderCallbackService.CallbackProcessResult result = callbackService.process(jobId, callbackRequest, rawBody);

			if (result.isNotFound()) {
				Map<String, Object> notFound = new HashMap<>();
				notFound.put("ok", false);
				notFound.put("state", "not_found");
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFound);
			}

			Map<String, Object> body = new HashMap<>();
			body.put("ok", result.isSuccess());
			body.put("duplicate", result.isDuplicate());
			body.put("state", result.getState());
			body.put("chunks", result.getChunks());
			body.put("embeddings", result.getEmbeddings());
			return ResponseEntity.ok(body);
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
