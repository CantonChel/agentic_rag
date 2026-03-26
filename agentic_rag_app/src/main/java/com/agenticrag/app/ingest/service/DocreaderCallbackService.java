package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.docreader.DocreaderCallbackRequest;
import com.agenticrag.app.ingest.entity.CallbackEventEntity;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.ingest.repo.CallbackEventRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.splitter.TextSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class DocreaderCallbackService {
	private static final Logger log = LoggerFactory.getLogger(DocreaderCallbackService.class);
	private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

	private final CallbackEventRepository callbackEventRepository;
	private final ParseJobService parseJobService;
	private final DocumentParseQueue documentParseQueue;
	private final FailureClassifier failureClassifier;
	private final EmbeddingModel embeddingModel;
	private final TextSplitter textSplitter;
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
		TextSplitter textSplitter,
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
		this.textSplitter = textSplitter;
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

		if (!parseJobService.findById(jobId.trim()).isPresent()) {
			return CallbackProcessResult.notFound();
		}

		processAsync(jobId.trim(), request);
		return CallbackProcessResult.accepted();
	}

	private void processAsync(String jobId, DocreaderCallbackRequest request) {
		Mono.fromRunnable(() -> processInternal(jobId, request))
			.subscribeOn(Schedulers.boundedElastic())
			.doOnError(e -> log.warn("async callback processing failed: jobId={}, error={}", jobId, e.getMessage()))
			.subscribe();
	}

	private void processInternal(String jobId, DocreaderCallbackRequest request) {
		Optional<ParseJobEntity> jobOpt = parseJobService.findById(jobId);
		if (!jobOpt.isPresent()) {
			log.warn("callback job not found when processing async: jobId={}", jobId);
			return;
		}
		ParseJobEntity job = jobOpt.get();

		String normalizedStatus = normalizeStatus(request.getStatus());
		if (!"success".equals(normalizedStatus) && !"completed".equals(normalizedStatus)) {
			String code = request.getError() != null ? request.getError().getCode() : "docreader_failed";
			String message = request.getError() != null ? request.getError().getMessage() : request.getMessage();
			boolean retryable = failureClassifier.isRetryable(code, null);
			JobFailureAction action = parseJobService.registerFailure(job.getId(), code, message, retryable);
			applyAction(job.getId(), action);
			log.warn(
				"callback reported failed status: jobId={}, status={}, decision={}",
				job.getId(),
				normalizedStatus,
				action.getDecision()
			);
			return;
		}

		try {
			boolean indexingMarked = parseJobService.markIndexing(job.getId());
			if (!indexingMarked) {
				ParseJobEntity latest = parseJobService.findById(job.getId()).orElse(null);
				if (latest == null) {
					log.warn("callback skip indexing because job disappeared: jobId={}", job.getId());
					return;
				}
				ParseJobStatus latestStatus = latest.getStatus();
				if (latestStatus != ParseJobStatus.INDEXING
					&& latestStatus != ParseJobStatus.PARSING
					&& latestStatus != ParseJobStatus.DISPATCHED) {
					log.info("callback skip indexing due to terminal status: jobId={}, status={}", job.getId(), latestStatus);
					return;
				}
			}
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
			log.info(
				"callback processed success: jobId={}, chunks={}, embeddings={}",
				job.getId(),
				assembly.getChunkEntities().size(),
				embeddings.size()
			);
			return;
		} catch (Exception e) {
			boolean retryable = failureClassifier.isRetryable("service_unavailable", e);
			JobFailureAction action = parseJobService.registerFailure(job.getId(), "indexing_failed", e.getMessage(), retryable);
			applyAction(job.getId(), action);
			log.warn("callback handling failed: jobId={}, retryable={}, error={}", job.getId(), retryable, e.getMessage());
			return;
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
		int generatedIndex = 0;
		for (int i = 0; i < source.size(); i++) {
			DocreaderCallbackRequest.ChunkPayload payload = source.get(i);
			if (payload == null) {
				continue;
			}
			ChunkType chunkType = parseChunkType(payload.getType());
			if (chunkType != ChunkType.TEXT) {
				ChunkEntity entity = createBaseEntity(knowledgeId, payload, normalizeChunkId(payload.getChunkId(), knowledgeId, i), i);
				entity.setChunkType(chunkType);
				mainRefs.add(new MainChunkRef(entity, payload.getImageInfo()));
				continue;
			}

			Map<String, Object> baseMetadata = copyMetadata(payload.getMetadata());
			String sourceExt = sourceExt(baseMetadata);
			List<DocreaderCallbackRequest.ImageInfoPayload> images = payload.getImageInfo() != null
				? payload.getImageInfo()
				: Collections.emptyList();
			String content = payload.getContent() != null ? payload.getContent() : "";

			if (shouldSplitInRagApp(sourceExt)) {
				String resolvedContent = replaceImageRefs(content, images);
				String baseChunkId = normalizeChunkId(payload.getChunkId(), knowledgeId, i);
				Document doc = new Document(baseChunkId, resolvedContent, baseMetadata);
				List<TextChunk> splitChunks = textSplitter != null ? textSplitter.split(doc) : new ArrayList<>();
				if (splitChunks == null || splitChunks.isEmpty()) {
					ChunkEntity single = createBaseEntity(knowledgeId, payload, baseChunkId, generatedIndex++);
					single.setContent(resolvedContent);
					single.setMetadataJson(toJson(baseMetadata));
					single.setImageInfoJson(toJson(images));
					mainRefs.add(new MainChunkRef(single, images));
					continue;
				}

				for (int j = 0; j < splitChunks.size(); j++) {
					TextChunk split = splitChunks.get(j);
					if (split == null) {
						continue;
					}
					String splitContent = split.getText() != null ? split.getText() : "";
					List<DocreaderCallbackRequest.ImageInfoPayload> splitImages = imagesForContent(splitContent, images);
					Map<String, Object> splitMetadata = split.getMetadata() != null ? split.getMetadata() : baseMetadata;
					String chunkId = split.getChunkId() != null && !split.getChunkId().trim().isEmpty()
						? split.getChunkId().trim()
						: baseChunkId + ":" + j;
					ChunkEntity entity = createBaseEntity(knowledgeId, payload, chunkId, generatedIndex++);
					entity.setStartAt(null);
					entity.setEndAt(null);
					entity.setContent(splitContent);
					entity.setMetadataJson(toJson(splitMetadata));
					entity.setImageInfoJson(splitImages.isEmpty() ? null : toJson(splitImages));
					mainRefs.add(new MainChunkRef(entity, splitImages));
				}
				continue;
			}

			ChunkEntity entity = createBaseEntity(knowledgeId, payload, normalizeChunkId(payload.getChunkId(), knowledgeId, i), i);
			entity.setContent(replaceImageRefs(content, images));
			entity.setMetadataJson(toJson(baseMetadata));
			entity.setImageInfoJson(toJson(images));
			mainRefs.add(new MainChunkRef(entity, images));
		}

		for (int i = 0; i < mainRefs.size(); i++) {
			ChunkEntity current = mainRefs.get(i).entity;
			current.setChunkIndex(i);
			ChunkEntity prev = i > 0 ? mainRefs.get(i - 1).entity : null;
			ChunkEntity next = i + 1 < mainRefs.size() ? mainRefs.get(i + 1).entity : null;
			current.setPreChunkId(prev != null ? prev.getChunkId() : null);
			current.setNextChunkId(next != null ? next.getChunkId() : null);
		}

		List<ChunkEntity> all = new ArrayList<>();
		for (MainChunkRef ref : mainRefs) {
			all.add(ref.entity);
			List<DocreaderCallbackRequest.ImageInfoPayload> images = ref.images;
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
				metadata.put("image_info", parseJsonValue(chunk.getImageInfoJson()));
			}
			indexChunks.add(new TextChunk(chunk.getChunkId(), knowledgeId, chunk.getContent(), null, metadata));
		}
		return new Assembly(all, indexChunks);
	}

	private ChunkEntity createBaseEntity(
		String knowledgeId,
		DocreaderCallbackRequest.ChunkPayload payload,
		String chunkId,
		int fallbackIndex
	) {
		ChunkEntity entity = new ChunkEntity();
		entity.setChunkId(chunkId);
		entity.setKnowledgeId(knowledgeId);
		entity.setChunkType(parseChunkType(payload.getType()));
		entity.setChunkIndex(payload.getSeq() != null ? payload.getSeq() : fallbackIndex);
		entity.setStartAt(payload.getStart());
		entity.setEndAt(payload.getEnd());
		entity.setParentChunkId(null);
		entity.setPreChunkId(null);
		entity.setNextChunkId(null);
		entity.setContent(payload.getContent() != null ? payload.getContent() : "");
		entity.setImageInfoJson(toJson(payload.getImageInfo()));
		entity.setMetadataJson(toJson(payload.getMetadata()));
		entity.setCreatedAt(Instant.now());
		entity.setUpdatedAt(Instant.now());
		return entity;
	}

	private Map<String, Object> copyMetadata(Map<String, Object> metadata) {
		Map<String, Object> out = new HashMap<>();
		if (metadata != null) {
			out.putAll(metadata);
		}
		return out;
	}

	private boolean shouldSplitInRagApp(String sourceExt) {
		String ext = sourceExt != null ? sourceExt.trim().toLowerCase(Locale.ROOT) : "";
		if (ext.isEmpty()) {
			return true;
		}
		if (!ext.startsWith(".")) {
			ext = "." + ext;
		}
		return !".docx".equals(ext);
	}

	private String sourceExt(Map<String, Object> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return "";
		}
		Object ext = metadata.get("source_ext");
		if (ext == null) {
			ext = metadata.get("sourceExt");
		}
		return ext != null ? String.valueOf(ext) : "";
	}

	private String replaceImageRefs(String content, List<DocreaderCallbackRequest.ImageInfoPayload> images) {
		if (content == null || content.trim().isEmpty() || images == null || images.isEmpty()) {
			return content != null ? content : "";
		}
		Map<String, String> replacements = new HashMap<>();
		for (DocreaderCallbackRequest.ImageInfoPayload image : images) {
			if (image == null) {
				continue;
			}
			String original = image.getOriginalUrl();
			String target = resolveImageUrl(image);
			if (original == null || original.trim().isEmpty() || target == null || target.trim().isEmpty()) {
				continue;
			}
			replacements.put(original.trim(), target.trim());
		}
		if (replacements.isEmpty()) {
			return content;
		}
		StringBuffer sb = new StringBuffer();
		Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(content);
		while (matcher.find()) {
			String alt = matcher.group(1);
			String path = matcher.group(2);
			String replacement = replacements.get(path);
			if (replacement == null) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
				continue;
			}
			String replacedMarkdown = "![" + alt + "](" + replacement + ")";
			matcher.appendReplacement(sb, Matcher.quoteReplacement(replacedMarkdown));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private List<DocreaderCallbackRequest.ImageInfoPayload> imagesForContent(
		String content,
		List<DocreaderCallbackRequest.ImageInfoPayload> images
	) {
		if (content == null || content.trim().isEmpty() || images == null || images.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> refs = extractMarkdownImageRefs(content);
		if (refs.isEmpty()) {
			return Collections.emptyList();
		}
		List<DocreaderCallbackRequest.ImageInfoPayload> out = new ArrayList<>();
		for (DocreaderCallbackRequest.ImageInfoPayload image : images) {
			if (image == null) {
				continue;
			}
			String resolved = normalizeRef(resolveImageUrl(image));
			String original = normalizeRef(image.getOriginalUrl());
			if ((resolved != null && refs.contains(resolved)) || (original != null && refs.contains(original))) {
				out.add(image);
			}
		}
		return out;
	}

	private Set<String> extractMarkdownImageRefs(String content) {
		Set<String> refs = new HashSet<>();
		if (content == null || content.trim().isEmpty()) {
			return refs;
		}
		Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(content);
		while (matcher.find()) {
			String ref = normalizeRef(matcher.group(2));
			if (ref != null) {
				refs.add(ref);
			}
		}
		return refs;
	}

	private String normalizeRef(String value) {
		if (value == null) {
			return null;
		}
		String out = value.trim();
		return out.isEmpty() ? null : out;
	}

	private String resolveImageUrl(DocreaderCallbackRequest.ImageInfoPayload image) {
		if (image == null) {
			return null;
		}
		if (image.getUrl() != null && !image.getUrl().trim().isEmpty()) {
			return image.getUrl().trim();
		}
		String bucket = image.getStorageBucket();
		String key = image.getStorageKey();
		if (bucket != null && !bucket.trim().isEmpty() && key != null && !key.trim().isEmpty()) {
			return "minio://" + bucket.trim() + "/" + key.trim();
		}
		return null;
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

	private Object parseJsonValue(String json) {
		if (json == null || json.trim().isEmpty()) {
			return null;
		}
		try {
			return objectMapper.readValue(json, Object.class);
		} catch (Exception ignored) {
			return json;
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
		private final List<DocreaderCallbackRequest.ImageInfoPayload> images;

		private MainChunkRef(ChunkEntity entity, List<DocreaderCallbackRequest.ImageInfoPayload> images) {
			this.entity = entity;
			this.images = images != null ? images : Collections.emptyList();
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

		public static CallbackProcessResult accepted() {
			return new CallbackProcessResult(false, true, false, "accepted", 0, 0);
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
