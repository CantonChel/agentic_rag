package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.docreader.DocreaderCallbackRequest;
import com.agenticrag.app.ingest.entity.CallbackEventEntity;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.agenticrag.app.rag.model.TextChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class DocreaderCallbackService {
	private static final Logger log = LoggerFactory.getLogger(DocreaderCallbackService.class);

	private final CallbackEventRepository callbackEventRepository;
	private final ParseJobService parseJobService;
	private final DocumentParseQueue documentParseQueue;
	private final FailureClassifier failureClassifier;
	private final EmbeddingModel embeddingModel;
	private final KnowledgeChunkPersistenceService chunkPersistenceService;
	private final ObjectMapper objectMapper;
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiEmbeddingProperties openAiEmbeddingProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;

	public DocreaderCallbackService(
		CallbackEventRepository callbackEventRepository,
		ParseJobService parseJobService,
		DocumentParseQueue documentParseQueue,
		FailureClassifier failureClassifier,
		EmbeddingModel embeddingModel,
		KnowledgeChunkPersistenceService chunkPersistenceService,
		ObjectMapper objectMapper,
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiEmbeddingProperties openAiEmbeddingProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties
	) {
		this.callbackEventRepository = callbackEventRepository;
		this.parseJobService = parseJobService;
		this.documentParseQueue = documentParseQueue;
		this.failureClassifier = failureClassifier;
		this.embeddingModel = embeddingModel;
		this.chunkPersistenceService = chunkPersistenceService;
		this.objectMapper = objectMapper;
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiEmbeddingProperties = openAiEmbeddingProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
	}

	public CallbackProcessResult process(String jobId, DocreaderCallbackRequest request, String rawPayload) {
		if (jobId == null || jobId.trim().isEmpty()) {
			throw new IllegalArgumentException("jobId is required");
		}
		if (request == null) {
			throw new IllegalArgumentException("callback payload is required");
		}
		if (request.getEventId() == null || request.getEventId().trim().isEmpty()) {
			throw new IllegalArgumentException("event_id is required");
		}

		if (!recordEvent(request.getEventId().trim(), jobId.trim(), rawPayload)) {
			return CallbackProcessResult.duplicate();
		}

		Optional<ParseJobEntity> jobOpt = parseJobService.findById(jobId.trim());
		if (!jobOpt.isPresent()) {
			return CallbackProcessResult.notFound();
		}
		ParseJobEntity job = jobOpt.get();

		String normalizedStatus = normalizeStatus(request.getStatus());
		if (!"success".equals(normalizedStatus) && !"completed".equals(normalizedStatus)) {
			String code = request.getError() != null ? request.getError().getCode() : "docreader_failed";
			String message = request.getError() != null ? request.getError().getMessage() : request.getMessage();
			boolean retryable = failureClassifier.isRetryable(code, null);
			JobFailureAction action = parseJobService.registerFailure(job.getId(), code, message, retryable);
			applyAction(job.getId(), action);
			return CallbackProcessResult.failed(action.getDecision().name());
		}

		try {
			parseJobService.markIndexing(job.getId());
			Assembly assembly = assembleChunks(job.getKnowledgeId(), request.getChunks());
			List<EmbeddingEntity> embeddings;
			try {
				embeddings = buildEmbeddings(job.getKnowledgeId(), assembly.getChunkEntities(), assembly.getIndexChunks());
			} catch (Exception e) {
				log.warn("embedding failed, fallback to chunk-only indexing: jobId={}, error={}", job.getId(), e.getMessage());
				embeddings = new ArrayList<>();
			}
			chunkPersistenceService.replaceKnowledgeData(job.getKnowledgeId(), assembly.getChunkEntities(), embeddings, assembly.getIndexChunks());
			parseJobService.markSuccess(job.getId());
			return CallbackProcessResult.success(assembly.getChunkEntities().size(), embeddings.size());
		} catch (Exception e) {
			boolean retryable = failureClassifier.isRetryable("service_unavailable", e);
			JobFailureAction action = parseJobService.registerFailure(job.getId(), "indexing_failed", e.getMessage(), retryable);
			applyAction(job.getId(), action);
			log.warn("callback handling failed: jobId={}, retryable={}, error={}", job.getId(), retryable, e.getMessage());
			return CallbackProcessResult.failed(action.getDecision().name());
		}
	}

	private boolean recordEvent(String eventId, String jobId, String rawPayload) {
		CallbackEventEntity event = new CallbackEventEntity();
		event.setEventId(eventId);
		event.setJobId(jobId);
		event.setPayloadHash(sha256(rawPayload != null ? rawPayload : ""));
		event.setCreatedAt(Instant.now());
		try {
			callbackEventRepository.save(event);
			return true;
		} catch (DataIntegrityViolationException e) {
			return false;
		}
	}

	private void applyAction(String jobId, JobFailureAction action) {
		if (action == null) {
			return;
		}
		ReservedJob synthetic = new ReservedJob(jobId, jobId);
		switch (action.getDecision()) {
			case RETRY:
				documentParseQueue.retry(synthetic, action.getNextRetryAt());
				break;
			case DEAD_LETTER:
				documentParseQueue.deadLetter(synthetic);
				break;
			case FAILED:
			default:
				documentParseQueue.ack(synthetic);
				break;
		}
	}

	private Assembly assembleChunks(String knowledgeId, List<DocreaderCallbackRequest.ChunkPayload> incoming) {
		List<DocreaderCallbackRequest.ChunkPayload> source = incoming != null ? incoming : new ArrayList<>();
		List<MainChunkRef> mainRefs = new ArrayList<>();
		for (int i = 0; i < source.size(); i++) {
			DocreaderCallbackRequest.ChunkPayload payload = source.get(i);
			if (payload == null) {
				continue;
			}
			ChunkEntity entity = new ChunkEntity();
			entity.setChunkId(normalizeChunkId(payload.getChunkId(), knowledgeId, i));
			entity.setKnowledgeId(knowledgeId);
			entity.setChunkType(parseChunkType(payload.getType()));
			entity.setChunkIndex(payload.getSeq() != null ? payload.getSeq() : i);
			entity.setStartAt(payload.getStart());
			entity.setEndAt(payload.getEnd());
			entity.setParentChunkId(null);
			entity.setContent(payload.getContent() != null ? payload.getContent() : "");
			entity.setImageInfoJson(toJson(payload.getImageInfo()));
			entity.setMetadataJson(toJson(payload.getMetadata()));
			entity.setCreatedAt(Instant.now());
			entity.setUpdatedAt(Instant.now());
			mainRefs.add(new MainChunkRef(entity, payload));
		}

		for (int i = 0; i < mainRefs.size(); i++) {
			ChunkEntity current = mainRefs.get(i).entity;
			ChunkEntity prev = i > 0 ? mainRefs.get(i - 1).entity : null;
			ChunkEntity next = i + 1 < mainRefs.size() ? mainRefs.get(i + 1).entity : null;
			current.setPreChunkId(prev != null ? prev.getChunkId() : null);
			current.setNextChunkId(next != null ? next.getChunkId() : null);
		}

		List<ChunkEntity> all = new ArrayList<>();
		for (MainChunkRef ref : mainRefs) {
			all.add(ref.entity);
			List<DocreaderCallbackRequest.ImageInfoPayload> images = ref.payload.getImageInfo();
			if (images == null || images.isEmpty()) {
				continue;
			}
			for (int i = 0; i < images.size(); i++) {
				DocreaderCallbackRequest.ImageInfoPayload image = images.get(i);
				if (image == null) {
					continue;
				}
				String singleImageJson = toJson(image);
				if (image.getOcrText() != null && !image.getOcrText().trim().isEmpty()) {
					ChunkEntity ocr = new ChunkEntity();
					ocr.setChunkId(ref.entity.getChunkId() + ":img:" + i + ":ocr");
					ocr.setKnowledgeId(knowledgeId);
					ocr.setChunkType(ChunkType.IMAGE_OCR);
					ocr.setChunkIndex(ref.entity.getChunkIndex());
					ocr.setParentChunkId(ref.entity.getChunkId());
					ocr.setContent(image.getOcrText());
					ocr.setImageInfoJson(singleImageJson);
					ocr.setMetadataJson(ref.entity.getMetadataJson());
					ocr.setCreatedAt(Instant.now());
					ocr.setUpdatedAt(Instant.now());
					all.add(ocr);
				}
				if (image.getCaption() != null && !image.getCaption().trim().isEmpty()) {
					ChunkEntity caption = new ChunkEntity();
					caption.setChunkId(ref.entity.getChunkId() + ":img:" + i + ":caption");
					caption.setKnowledgeId(knowledgeId);
					caption.setChunkType(ChunkType.IMAGE_CAPTION);
					caption.setChunkIndex(ref.entity.getChunkIndex());
					caption.setParentChunkId(ref.entity.getChunkId());
					caption.setContent(image.getCaption());
					caption.setImageInfoJson(singleImageJson);
					caption.setMetadataJson(ref.entity.getMetadataJson());
					caption.setCreatedAt(Instant.now());
					caption.setUpdatedAt(Instant.now());
					all.add(caption);
				}
			}
		}

		List<TextChunk> indexChunks = new ArrayList<>();
		for (ChunkEntity chunk : all) {
			Map<String, Object> metadata = new HashMap<>();
			metadata.put("chunk_type", chunk.getChunkType().name().toLowerCase(Locale.ROOT));
			if (chunk.getImageInfoJson() != null) {
				metadata.put("image_info", chunk.getImageInfoJson());
			}
			indexChunks.add(new TextChunk(chunk.getChunkId(), knowledgeId, chunk.getContent(), null, metadata));
		}
		return new Assembly(all, indexChunks);
	}

	private List<EmbeddingEntity> buildEmbeddings(String knowledgeId, List<ChunkEntity> chunks, List<TextChunk> indexChunks) {
		List<String> texts = new ArrayList<>();
		List<ChunkEntity> toEmbed = new ArrayList<>();
		for (ChunkEntity chunk : chunks) {
			String content = chunk.getContent();
			if (content == null || content.trim().isEmpty()) {
				continue;
			}
			texts.add(content);
			toEmbed.add(chunk);
		}
		if (texts.isEmpty()) {
			return new ArrayList<>();
		}

		List<List<Double>> vectors = embeddingModel.embedTexts(texts);
		Map<String, TextChunk> byChunkId = new HashMap<>();
		for (TextChunk t : indexChunks) {
			byChunkId.put(t.getChunkId(), t);
		}

		List<EmbeddingEntity> out = new ArrayList<>();
		for (int i = 0; i < toEmbed.size(); i++) {
			ChunkEntity chunk = toEmbed.get(i);
			List<Double> vector = vectors != null && i < vectors.size() ? vectors.get(i) : null;
			if (vector == null || vector.isEmpty()) {
				continue;
			}
			TextChunk textChunk = byChunkId.get(chunk.getChunkId());
			if (textChunk != null) {
				textChunk.setEmbedding(vector);
			}

			EmbeddingEntity emb = new EmbeddingEntity();
			emb.setChunkId(chunk.getChunkId());
			emb.setKnowledgeId(knowledgeId);
			emb.setModelName(resolveModelName());
			emb.setDimension(vector.size());
			emb.setVectorJson(toJson(vector));
			emb.setContent(chunk.getContent());
			emb.setEnabled(true);
			emb.setCreatedAt(Instant.now());
			emb.setUpdatedAt(Instant.now());
			out.add(emb);
		}
		return out;
	}

	private String resolveModelName() {
		String provider = ragEmbeddingProperties != null ? ragEmbeddingProperties.getProvider() : null;
		if (provider != null && "siliconflow".equalsIgnoreCase(provider.trim())) {
			return siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : "unknown";
		}
		return openAiEmbeddingProperties != null ? openAiEmbeddingProperties.getModel() : "unknown";
	}

	private String normalizeChunkId(String chunkId, String knowledgeId, int index) {
		if (chunkId != null && !chunkId.trim().isEmpty()) {
			return chunkId.trim();
		}
		return knowledgeId + ":" + index;
	}

	private ChunkType parseChunkType(String type) {
		if (type == null || type.trim().isEmpty()) {
			return ChunkType.TEXT;
		}
		String normalized = type.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		try {
			return ChunkType.valueOf(normalized);
		} catch (Exception e) {
			return ChunkType.TEXT;
		}
	}

	private String normalizeStatus(String status) {
		if (status == null) {
			return "";
		}
		return status.trim().toLowerCase(Locale.ROOT);
	}

	private String toJson(Object obj) {
		if (obj == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(obj);
		} catch (Exception e) {
			return null;
		}
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : out) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			return "";
		}
	}

	private static class MainChunkRef {
		private final ChunkEntity entity;
		private final DocreaderCallbackRequest.ChunkPayload payload;

		private MainChunkRef(ChunkEntity entity, DocreaderCallbackRequest.ChunkPayload payload) {
			this.entity = entity;
			this.payload = payload;
		}
	}

	private static class Assembly {
		private final List<ChunkEntity> chunkEntities;
		private final List<TextChunk> indexChunks;

		private Assembly(List<ChunkEntity> chunkEntities, List<TextChunk> indexChunks) {
			this.chunkEntities = chunkEntities;
			this.indexChunks = indexChunks;
		}

		private List<ChunkEntity> getChunkEntities() {
			return chunkEntities;
		}

		private List<TextChunk> getIndexChunks() {
			return indexChunks;
		}
	}

	public static class CallbackProcessResult {
		private final boolean duplicate;
		private final boolean success;
		private final boolean notFound;
		private final String state;
		private final int chunks;
		private final int embeddings;

		private CallbackProcessResult(boolean duplicate, boolean success, boolean notFound, String state, int chunks, int embeddings) {
			this.duplicate = duplicate;
			this.success = success;
			this.notFound = notFound;
			this.state = state;
			this.chunks = chunks;
			this.embeddings = embeddings;
		}

		public static CallbackProcessResult duplicate() {
			return new CallbackProcessResult(true, true, false, "duplicate", 0, 0);
		}

		public static CallbackProcessResult notFound() {
			return new CallbackProcessResult(false, false, true, "not_found", 0, 0);
		}

		public static CallbackProcessResult success(int chunks, int embeddings) {
			return new CallbackProcessResult(false, true, false, "success", chunks, embeddings);
		}

		public static CallbackProcessResult failed(String state) {
			return new CallbackProcessResult(false, false, false, state != null ? state : "failed", 0, 0);
		}

		public boolean isDuplicate() {
			return duplicate;
		}

		public boolean isSuccess() {
			return success;
		}

		public boolean isNotFound() {
			return notFound;
		}

		public String getState() {
			return state;
		}

		public int getChunks() {
			return chunks;
		}

		public int getEmbeddings() {
			return embeddings;
		}
	}
}
