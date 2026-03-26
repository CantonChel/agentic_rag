package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.service.KnowledgeBrowseService;
import com.agenticrag.app.ingest.service.KnowledgeImageService;
import com.agenticrag.app.ingest.storage.StoredBinary;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class KnowledgeBrowseController {
	private final KnowledgeBrowseService knowledgeBrowseService;
	private final KnowledgeImageService knowledgeImageService;

	public KnowledgeBrowseController(KnowledgeBrowseService knowledgeBrowseService, KnowledgeImageService knowledgeImageService) {
		this.knowledgeBrowseService = knowledgeBrowseService;
		this.knowledgeImageService = knowledgeImageService;
	}

	@GetMapping(value = "/knowledge-bases", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<KnowledgeBrowseService.KnowledgeBaseSummary>> listKnowledgeBases() {
		return Mono.fromCallable(knowledgeBrowseService::listKnowledgeBases)
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/knowledge-bases/{kbId}/knowledge", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<KnowledgeBrowseService.KnowledgeDocumentView>> listDocuments(
		@PathVariable("kbId") String knowledgeBaseId
	) {
		return Mono.fromCallable(() -> knowledgeBrowseService.listDocuments(knowledgeBaseId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/knowledge/{knowledgeId}/chunks", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<KnowledgeBrowseService.ChunkDetailView>> listChunks(
		@PathVariable("knowledgeId") String knowledgeId
	) {
		return Mono.fromCallable(() -> knowledgeBrowseService.listChunks(knowledgeId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/knowledge/images")
	public Mono<ResponseEntity<byte[]>> fetchImage(
		@RequestParam("filePath") String filePath
	) {
		return Mono.fromCallable(() -> {
			StoredBinary obj = knowledgeImageService.loadImage(filePath);
			return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_TYPE, obj.getContentType())
				.body(obj.getBytes());
		}).subscribeOn(Schedulers.boundedElastic());
	}
}
