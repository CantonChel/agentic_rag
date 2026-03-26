package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.docreader.DocreaderReadResponse;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.storage.KnowledgeFileStorageService;
import com.agenticrag.app.ingest.storage.StoredFile;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.splitter.TextSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DocreaderReadResultService {
	private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

	private final EmbeddingModel embeddingModel;
	private final TextSplitter textSplitter;
	private final KnowledgeChunkPersistenceService chunkPersistenceService;
	private final KnowledgeFileStorageService fileStorageService;
	private final ObjectMapper objectMapper;
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiEmbeddingProperties openAiEmbeddingProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;

	public DocreaderReadResultService(
		EmbeddingModel embeddingModel,
		TextSplitter textSplitter,
		KnowledgeChunkPersistenceService chunkPersistenceService,
		KnowledgeFileStorageService fileStorageService,
		ObjectMapper objectMapper,
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiEmbeddingProperties openAiEmbeddingProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties
	) {
		this.embeddingModel = embeddingModel;
		this.textSplitter = textSplitter;
		this.chunkPersistenceService = chunkPersistenceService;
		this.fileStorageService = fileStorageService;
		this.objectMapper = objectMapper;
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiEmbeddingProperties = openAiEmbeddingProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
	}

	public ProcessResult process(
		String knowledgeId,
		String userId,
		DocreaderReadResponse response
	) {
		String markdown = response != null && response.getMarkdownContent() != null ? response.getMarkdownContent().trim() : "";
		if (markdown.isEmpty()) {
			throw new IllegalArgumentException("empty markdown content");
		}

		Map<String, Object> baseMetadata = new HashMap<>();
		if (response != null && response.getMetadata() != null) {
			baseMetadata.putAll(response.getMetadata());
		}
		baseMetadata.put("user_id", userId);

		List<StoredImageInfo> storedImages = storeImages(userId, knowledgeId, response != null ? response.getImageRefs() : null);
		String replacedMarkdown = replaceImageRefs(markdown, storedImages);
		Assembly assembly = assembleChunks(knowledgeId, replacedMarkdown, baseMetadata, storedImages);
		List<EmbeddingEntity> embeddings = buildEmbeddings(knowledgeId, assembly.getChunkEntities(), assembly.getIndexChunks());
		chunkPersistenceService.replaceKnowledgeData(knowledgeId, assembly.getChunkEntities(), embeddings, assembly.getIndexChunks());
		return new ProcessResult(assembly.getChunkEntities().size(), embeddings.size());
	}

	private List<StoredImageInfo> storeImages(
		String userId,
		String knowledgeId,
		List<DocreaderReadResponse.ImageRef> imageRefs
	) {
		if (imageRefs == null || imageRefs.isEmpty()) {
			return Collections.emptyList();
		}
		List<StoredImageInfo> out = new ArrayList<>();
		int index = 0;
		for (DocreaderReadResponse.ImageRef imageRef : imageRefs) {
			if (imageRef == null) {
				continue;
			}
			String originalRef = normalizeRef(imageRef.getOriginalRef());
			String bytesBase64 = normalizeRef(imageRef.getBytesBase64());
			if (originalRef == null || bytesBase64 == null) {
				continue;
			}
			byte[] bytes;
			try {
				bytes = Base64.getDecoder().decode(bytesBase64);
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("invalid image bytes base64: " + originalRef, e);
			}
			String safeName = buildImageName(index++, imageRef.getFileName(), imageRef.getMimeType());
			StoredFile stored = fileStorageService.store(userId, knowledgeId, safeName, bytes);
			String imageUrl = "/api/knowledge/images?filePath=" + URLEncoder.encode(stored.getFilePath(), StandardCharsets.UTF_8);
			out.add(new StoredImageInfo(originalRef, imageUrl, stored.getFilePath(), imageRef.getFileName(), imageRef.getMimeType()));
		}
		return out;
	}

	private String buildImageName(int index, String fileName, String mimeType) {
		String safeName = fileName != null ? fileName.trim() : "";
		if (!safeName.isEmpty()) {
			return "parsed-" + index + "-" + safeName.replaceAll("[\\\\/:*?\"<>|]", "_");
		}
		String ext = extensionFromMimeType(mimeType);
		return "parsed-" + index + "." + ext;
	}

	private String extensionFromMimeType(String mimeType) {
		String safe = mimeType != null ? mimeType.trim().toLowerCase(Locale.ROOT) : "";
		if (safe.endsWith("png")) {
			return "png";
		}
		if (safe.endsWith("jpeg") || safe.endsWith("jpg")) {
			return "jpg";
		}
		if (safe.endsWith("webp")) {
			return "webp";
		}
		if (safe.endsWith("gif")) {
			return "gif";
		}
		if (safe.endsWith("bmp")) {
			return "bmp";
		}
		return "bin";
	}

	private String replaceImageRefs(String content, List<StoredImageInfo> images) {
		if (content == null || content.trim().isEmpty() || images == null || images.isEmpty()) {
			return content != null ? content : "";
		}
		Map<String, String> replacements = new HashMap<>();
		for (StoredImageInfo image : images) {
			if (image.getOriginalUrl() != null && image.getUrl() != null) {
				replacements.put(image.getOriginalUrl(), image.getUrl());
			}
		}
		if (replacements.isEmpty()) {
			return content;
		}
		StringBuffer sb = new StringBuffer();
		Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(content);
		while (matcher.find()) {
			String alt = matcher.group(1);
			String path = normalizeRef(matcher.group(2));
			String replacement = path != null ? replacements.get(path) : null;
			if (replacement == null) {
				matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
				continue;
			}
			matcher.appendReplacement(sb, Matcher.quoteReplacement("![" + alt + "](" + replacement + ")"));
		}
		matcher.appendTail(sb);
		return sb.toString();
	}

	private Assembly assembleChunks(
		String knowledgeId,
		String markdown,
		Map<String, Object> baseMetadata,
		List<StoredImageInfo> storedImages
	) {
		Document doc = new Document(knowledgeId, markdown, baseMetadata);
		List<TextChunk> splitChunks = textSplitter != null ? textSplitter.split(doc) : Collections.emptyList();
		if (splitChunks == null || splitChunks.isEmpty()) {
			splitChunks = new ArrayList<>();
			splitChunks.add(new TextChunk(knowledgeId + ":0", knowledgeId, markdown, null, baseMetadata));
		}

		List<ChunkEntity> chunkEntities = new ArrayList<>();
		List<TextChunk> indexChunks = new ArrayList<>();
		for (int i = 0; i < splitChunks.size(); i++) {
			TextChunk split = splitChunks.get(i);
			if (split == null) {
				continue;
			}
			String chunkId = normalizeChunkId(split.getChunkId(), knowledgeId, i);
			Map<String, Object> metadata = new HashMap<>();
			if (split.getMetadata() != null) {
				metadata.putAll(split.getMetadata());
			}
			List<StoredImageInfo> chunkImages = imagesForContent(split.getText(), storedImages);
			ChunkEntity entity = new ChunkEntity();
			entity.setChunkId(chunkId);
			entity.setKnowledgeId(knowledgeId);
			entity.setChunkType(ChunkType.TEXT);
			entity.setChunkIndex(i);
			entity.setStartAt(null);
			entity.setEndAt(null);
			entity.setParentChunkId(null);
			entity.setPreChunkId(i > 0 ? normalizeChunkId(splitChunks.get(i - 1).getChunkId(), knowledgeId, i - 1) : null);
			entity.setNextChunkId(i + 1 < splitChunks.size() ? normalizeChunkId(splitChunks.get(i + 1).getChunkId(), knowledgeId, i + 1) : null);
			entity.setContent(split.getText() != null ? split.getText() : "");
			entity.setImageInfoJson(chunkImages.isEmpty() ? null : toJson(chunkImages));
			entity.setMetadataJson(toJson(metadata));
			entity.setCreatedAt(Instant.now());
			entity.setUpdatedAt(Instant.now());
			chunkEntities.add(entity);

			Map<String, Object> indexMetadata = new HashMap<>(metadata);
			if (!chunkImages.isEmpty()) {
				indexMetadata.put("image_info", chunkImages);
			}
			indexChunks.add(new TextChunk(chunkId, knowledgeId, entity.getContent(), null, indexMetadata));
		}
		return new Assembly(chunkEntities, indexChunks);
	}

	private List<StoredImageInfo> imagesForContent(String content, List<StoredImageInfo> images) {
		if (content == null || content.trim().isEmpty() || images == null || images.isEmpty()) {
			return Collections.emptyList();
		}
		Set<String> refs = extractMarkdownImageRefs(content);
		if (refs.isEmpty()) {
			return Collections.emptyList();
		}
		List<StoredImageInfo> out = new ArrayList<>();
		for (StoredImageInfo image : images) {
			if (image.url != null && refs.contains(image.url)) {
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
		for (TextChunk chunk : indexChunks) {
			byChunkId.put(chunk.getChunkId(), chunk);
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

			EmbeddingEntity embedding = new EmbeddingEntity();
			embedding.setChunkId(chunk.getChunkId());
			embedding.setKnowledgeId(knowledgeId);
			embedding.setModelName(resolveModelName());
			embedding.setDimension(vector.size());
			embedding.setVectorJson(toJson(vector));
			embedding.setContent(chunk.getContent());
			embedding.setEnabled(true);
			embedding.setCreatedAt(Instant.now());
			embedding.setUpdatedAt(Instant.now());
			out.add(embedding);
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

	private String normalizeRef(String value) {
		if (value == null) {
			return null;
		}
		String out = value.trim();
		return out.isEmpty() ? null : out;
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

	public static class ProcessResult {
		private final int chunks;
		private final int embeddings;

		public ProcessResult(int chunks, int embeddings) {
			this.chunks = chunks;
			this.embeddings = embeddings;
		}

		public int getChunks() {
			return chunks;
		}

		public int getEmbeddings() {
			return embeddings;
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

	public static class StoredImageInfo {
		private final String url;
		private final String originalUrl;
		private final String filePath;
		private final String fileName;
		private final String mimeType;

		public StoredImageInfo(String originalUrl, String url, String filePath, String fileName, String mimeType) {
			this.url = url;
			this.originalUrl = originalUrl;
			this.filePath = filePath;
			this.fileName = fileName;
			this.mimeType = mimeType;
		}

		public String getUrl() {
			return url;
		}

		public String getOriginalUrl() {
			return originalUrl;
		}

		public String getFilePath() {
			return filePath;
		}

		public String getFileName() {
			return fileName;
		}

		public String getMimeType() {
			return mimeType;
		}
	}
}
