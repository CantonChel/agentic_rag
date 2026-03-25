package com.agenticrag.app.ingest.api;

import com.agenticrag.app.ingest.service.KnowledgeCrudService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api")
public class KnowledgeCrudController {
	private final KnowledgeCrudService knowledgeCrudService;

	public KnowledgeCrudController(KnowledgeCrudService knowledgeCrudService) {
		this.knowledgeCrudService = knowledgeCrudService;
	}

	@PostMapping(value = "/knowledge-bases", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeBaseView> createKnowledgeBase(
		@RequestBody(required = false) KnowledgeCrudService.CreateKnowledgeBaseRequest request
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.createKnowledgeBase(request))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/knowledge-bases/{kbId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeBaseView> getKnowledgeBase(
		@PathVariable("kbId") String knowledgeBaseId
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.getKnowledgeBase(knowledgeBaseId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PatchMapping(value = "/knowledge-bases/{kbId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeBaseView> updateKnowledgeBase(
		@PathVariable("kbId") String knowledgeBaseId,
		@RequestBody(required = false) KnowledgeCrudService.UpdateKnowledgeBaseRequest request
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.updateKnowledgeBase(knowledgeBaseId, request))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@DeleteMapping(value = "/knowledge-bases/{kbId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeBaseDeleteResult> deleteKnowledgeBase(
		@PathVariable("kbId") String knowledgeBaseId
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.deleteKnowledgeBase(knowledgeBaseId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/knowledge/{knowledgeId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeDocumentView> getKnowledgeDocument(
		@PathVariable("knowledgeId") String knowledgeId
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.getKnowledgeDocument(knowledgeId))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PatchMapping(value = "/knowledge/{knowledgeId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeDocumentView> updateKnowledgeDocument(
		@PathVariable("knowledgeId") String knowledgeId,
		@RequestBody(required = false) KnowledgeCrudService.UpdateKnowledgeDocumentRequest request
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.updateKnowledgeDocument(knowledgeId, request))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@DeleteMapping(value = "/knowledge/{knowledgeId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<KnowledgeCrudService.KnowledgeDocumentDeleteResult> deleteKnowledgeDocument(
		@PathVariable("knowledgeId") String knowledgeId
	) {
		return Mono.fromCallable(() -> knowledgeCrudService.deleteKnowledgeDocument(knowledgeId))
			.subscribeOn(Schedulers.boundedElastic());
	}
}
