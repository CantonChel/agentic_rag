package com.agenticrag.app.api;

import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.pipeline.RagPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/rag")
public class RagController {
	private final RagPipeline ragPipeline;

	public RagController(RagPipeline ragPipeline) {
		this.ragPipeline = ragPipeline;
	}

	@PostMapping(value = "/ingest", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<RagPipeline.IngestResult> ingest() {
		return Mono.fromCallable(() -> ragPipeline.ingest())
			.subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/ingest/text", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<RagPipeline.IngestResult> ingestText(@RequestBody IngestTextRequest req) {
		return Mono.fromCallable(() -> {
			String content = req != null ? req.getContent() : null;
			if (content == null) {
				content = "";
			}
			Map<String, Object> metadata = new HashMap<>();
			if (req != null && req.getMetadata() != null) {
				metadata.putAll(req.getMetadata());
			}
			if (!metadata.containsKey("source")) {
				metadata.put("source", req != null && req.getSource() != null ? req.getSource() : "upload");
			}
			String id = req != null ? req.getId() : null;
			if (id == null || id.trim().isEmpty()) {
				id = UUID.randomUUID().toString();
			}
			return ragPipeline.ingestDocuments(java.util.Collections.singletonList(new Document(id, content, metadata)));
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@PostMapping(value = "/ingest/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<RagPipeline.IngestResult> ingestFile(@RequestParam("file") FilePart file) {
		if (file == null) {
			return Mono.just(new RagPipeline.IngestResult(0, 0));
		}
		return DataBufferUtils.join(file.content())
			.map(buf -> {
				byte[] bytes = new byte[buf.readableByteCount()];
				buf.read(bytes);
				DataBufferUtils.release(buf);
				return new String(bytes, StandardCharsets.UTF_8);
			})
			.flatMap(content -> Mono.fromCallable(() -> {
				Map<String, Object> metadata = new HashMap<>();
				metadata.put("source", file.filename());
				String id = UUID.randomUUID().toString();
				return ragPipeline.ingestDocuments(java.util.Collections.singletonList(new Document(id, content, metadata)));
			}).subscribeOn(Schedulers.boundedElastic()));
	}

	@PostMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<TextChunkView>> search(@RequestBody SearchRequest req) {
		return Mono.fromCallable(() -> {
			String query = req != null ? req.getQuery() : null;
			int topK = req != null ? req.getTopK() : 5;
			return ragPipeline.search(query, topK).stream()
				.map(TextChunkView::from)
				.collect(Collectors.toList());
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/search", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<TextChunkView>> searchGet(
		@RequestParam("q") String query,
		@RequestParam(value = "topK", defaultValue = "5") int topK
	) {
		return Mono.fromCallable(() -> ragPipeline.search(query, topK).stream()
			.map(TextChunkView::from)
			.collect(Collectors.toList()))
			.subscribeOn(Schedulers.boundedElastic());
	}

	public static class SearchRequest {
		private String query;
		private int topK = 5;

		public String getQuery() {
			return query;
		}

		public void setQuery(String query) {
			this.query = query;
		}

		public int getTopK() {
			return topK;
		}

		public void setTopK(int topK) {
			this.topK = topK;
		}
	}

	public static class IngestTextRequest {
		private String id;
		private String source;
		private String content;
		private Map<String, Object> metadata;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getSource() {
			return source;
		}

		public void setSource(String source) {
			this.source = source;
		}

		public String getContent() {
			return content;
		}

		public void setContent(String content) {
			this.content = content;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}

		public void setMetadata(Map<String, Object> metadata) {
			this.metadata = metadata;
		}
	}

	public static class TextChunkView {
		private final String chunkId;
		private final String documentId;
		private final String text;
		private final Map<String, Object> metadata;

		public TextChunkView(String chunkId, String documentId, String text, Map<String, Object> metadata) {
			this.chunkId = chunkId;
			this.documentId = documentId;
			this.text = text;
			this.metadata = metadata;
		}

		public static TextChunkView from(TextChunk c) {
			return new TextChunkView(c.getChunkId(), c.getDocumentId(), c.getText(), c.getMetadata());
		}

		public String getChunkId() {
			return chunkId;
		}

		public String getDocumentId() {
			return documentId;
		}

		public String getText() {
			return text;
		}

		public Map<String, Object> getMetadata() {
			return metadata;
		}
	}
}
