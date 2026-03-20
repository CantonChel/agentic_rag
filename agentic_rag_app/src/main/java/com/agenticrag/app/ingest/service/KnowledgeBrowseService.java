package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBrowseService {
	private final KnowledgeRepository knowledgeRepository;
	private final ChunkRepository chunkRepository;
	private final ObjectMapper objectMapper;

	public KnowledgeBrowseService(
		KnowledgeRepository knowledgeRepository,
		ChunkRepository chunkRepository,
		ObjectMapper objectMapper
	) {
		this.knowledgeRepository = knowledgeRepository;
		this.chunkRepository = chunkRepository;
		this.objectMapper = objectMapper;
	}

	public List<KnowledgeBaseSummary> listKnowledgeBases() {
		List<KnowledgeEntity> all = knowledgeRepository.findAllByOrderByKnowledgeBaseIdAscCreatedAtDesc();
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (KnowledgeEntity item : all) {
			if (item == null) {
				continue;
			}
			String kbId = safe(item.getKnowledgeBaseId());
			if (kbId.isEmpty()) {
				continue;
			}
			counts.put(kbId, counts.getOrDefault(kbId, 0) + 1);
		}

		List<KnowledgeBaseSummary> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			out.add(new KnowledgeBaseSummary(e.getKey(), e.getValue()));
		}
		out.sort(Comparator.comparing(KnowledgeBaseSummary::getKnowledgeBaseId));
		return out;
	}

	public List<KnowledgeDocumentView> listDocuments(String knowledgeBaseId) {
		String kbId = safe(knowledgeBaseId);
		if (kbId.isEmpty()) {
			return new ArrayList<>();
		}
		List<KnowledgeEntity> docs = knowledgeRepository.findByKnowledgeBaseIdOrderByCreatedAtDesc(kbId);
		List<KnowledgeDocumentView> out = new ArrayList<>();
		for (KnowledgeEntity doc : docs) {
			if (doc == null) {
				continue;
			}
			out.add(new KnowledgeDocumentView(
				doc.getId(),
				doc.getKnowledgeBaseId(),
				doc.getFileName(),
				doc.getFileType(),
				doc.getFileSize(),
				doc.getParseStatus() != null ? doc.getParseStatus().name().toLowerCase(Locale.ROOT) : "unknown",
				doc.getEnableStatus() != null ? doc.getEnableStatus().name().toLowerCase(Locale.ROOT) : "unknown",
				doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : null,
				doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : null
			));
		}
		return out;
	}

	public List<ChunkDetailView> listChunks(String knowledgeId) {
		String kid = safe(knowledgeId);
		if (kid.isEmpty()) {
			return new ArrayList<>();
		}
		List<ChunkEntity> chunks = chunkRepository.findByKnowledgeIdOrderByChunkIndexAsc(kid);
		List<ChunkDetailView> out = new ArrayList<>();
		for (ChunkEntity chunk : chunks) {
			if (chunk == null) {
				continue;
			}
			out.add(new ChunkDetailView(
				chunk.getChunkId(),
				chunk.getKnowledgeId(),
				chunk.getChunkType() != null ? chunk.getChunkType().name().toLowerCase(Locale.ROOT) : "unknown",
				chunk.getChunkIndex(),
				chunk.getStartAt(),
				chunk.getEndAt(),
				chunk.getParentChunkId(),
				chunk.getContent(),
				parseImageInfos(chunk.getImageInfoJson()),
				chunk.getCreatedAt() != null ? chunk.getCreatedAt().toString() : null
			));
		}
		return out;
	}

	private List<ImageInfoView> parseImageInfos(String imageInfoJson) {
		List<ImageInfoView> out = new ArrayList<>();
		if (imageInfoJson == null || imageInfoJson.trim().isEmpty()) {
			return out;
		}
		try {
			JsonNode root = objectMapper.readTree(imageInfoJson);
			if (root == null || root.isNull()) {
				return out;
			}
			if (root.isArray()) {
				for (JsonNode node : root) {
					ImageInfoView item = parseImageInfoNode(node);
					if (item != null) {
						out.add(item);
					}
				}
				return out;
			}
			ImageInfoView one = parseImageInfoNode(root);
			if (one != null) {
				out.add(one);
			}
		} catch (Exception ignored) {
			// Ignore malformed JSON to keep browse API robust.
		}
		return out;
	}

	private ImageInfoView parseImageInfoNode(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		return new ImageInfoView(
			textOf(node, "url"),
			firstNonBlank(textOf(node, "original_url"), textOf(node, "originalUrl")),
			intOf(node, "start_pos", "startPos"),
			intOf(node, "end_pos", "endPos"),
			textOf(node, "caption"),
			firstNonBlank(textOf(node, "ocr_text"), textOf(node, "ocrText"))
		);
	}

	private String textOf(JsonNode node, String name) {
		JsonNode child = node.get(name);
		if (child == null || child.isNull()) {
			return null;
		}
		String v = child.asText();
		return v != null && !v.trim().isEmpty() ? v : null;
	}

	private Integer intOf(JsonNode node, String primary, String fallback) {
		JsonNode c1 = node.get(primary);
		if (c1 != null && c1.canConvertToInt()) {
			return c1.asInt();
		}
		JsonNode c2 = node.get(fallback);
		if (c2 != null && c2.canConvertToInt()) {
			return c2.asInt();
		}
		return null;
	}

	private String firstNonBlank(String a, String b) {
		if (a != null && !a.trim().isEmpty()) {
			return a;
		}
		return b;
	}

	private String safe(String value) {
		return value != null ? value.trim() : "";
	}

	public static class KnowledgeBaseSummary {
		private final String knowledgeBaseId;
		private final int documentCount;

		public KnowledgeBaseSummary(String knowledgeBaseId, int documentCount) {
			this.knowledgeBaseId = knowledgeBaseId;
			this.documentCount = documentCount;
		}

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public int getDocumentCount() {
			return documentCount;
		}
	}

	public static class KnowledgeDocumentView {
		private final String knowledgeId;
		private final String knowledgeBaseId;
		private final String fileName;
		private final String fileType;
		private final long fileSize;
		private final String parseStatus;
		private final String enableStatus;
		private final String createdAt;
		private final String updatedAt;

		public KnowledgeDocumentView(
			String knowledgeId,
			String knowledgeBaseId,
			String fileName,
			String fileType,
			long fileSize,
			String parseStatus,
			String enableStatus,
			String createdAt,
			String updatedAt
		) {
			this.knowledgeId = knowledgeId;
			this.knowledgeBaseId = knowledgeBaseId;
			this.fileName = fileName;
			this.fileType = fileType;
			this.fileSize = fileSize;
			this.parseStatus = parseStatus;
			this.enableStatus = enableStatus;
			this.createdAt = createdAt;
			this.updatedAt = updatedAt;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getKnowledgeBaseId() {
			return knowledgeBaseId;
		}

		public String getFileName() {
			return fileName;
		}

		public String getFileType() {
			return fileType;
		}

		public long getFileSize() {
			return fileSize;
		}

		public String getParseStatus() {
			return parseStatus;
		}

		public String getEnableStatus() {
			return enableStatus;
		}

		public String getCreatedAt() {
			return createdAt;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class ChunkDetailView {
		private final String chunkId;
		private final String knowledgeId;
		private final String chunkType;
		private final Integer chunkIndex;
		private final Integer startAt;
		private final Integer endAt;
		private final String parentChunkId;
		private final String content;
		private final List<ImageInfoView> imageInfos;
		private final String createdAt;

		public ChunkDetailView(
			String chunkId,
			String knowledgeId,
			String chunkType,
			Integer chunkIndex,
			Integer startAt,
			Integer endAt,
			String parentChunkId,
			String content,
			List<ImageInfoView> imageInfos,
			String createdAt
		) {
			this.chunkId = chunkId;
			this.knowledgeId = knowledgeId;
			this.chunkType = chunkType;
			this.chunkIndex = chunkIndex;
			this.startAt = startAt;
			this.endAt = endAt;
			this.parentChunkId = parentChunkId;
			this.content = content;
			this.imageInfos = imageInfos;
			this.createdAt = createdAt;
		}

		public String getChunkId() {
			return chunkId;
		}

		public String getKnowledgeId() {
			return knowledgeId;
		}

		public String getChunkType() {
			return chunkType;
		}

		public Integer getChunkIndex() {
			return chunkIndex;
		}

		public Integer getStartAt() {
			return startAt;
		}

		public Integer getEndAt() {
			return endAt;
		}

		public String getParentChunkId() {
			return parentChunkId;
		}

		public String getContent() {
			return content;
		}

		public List<ImageInfoView> getImageInfos() {
			return imageInfos;
		}

		public String getCreatedAt() {
			return createdAt;
		}
	}

	public static class ImageInfoView {
		private final String url;
		private final String originalUrl;
		private final Integer startPos;
		private final Integer endPos;
		private final String caption;
		private final String ocrText;

		public ImageInfoView(
			String url,
			String originalUrl,
			Integer startPos,
			Integer endPos,
			String caption,
			String ocrText
		) {
			this.url = url;
			this.originalUrl = originalUrl;
			this.startPos = startPos;
			this.endPos = endPos;
			this.caption = caption;
			this.ocrText = ocrText;
		}

		public String getUrl() {
			return url;
		}

		public String getOriginalUrl() {
			return originalUrl;
		}

		public Integer getStartPos() {
			return startPos;
		}

		public Integer getEndPos() {
			return endPos;
		}

		public String getCaption() {
			return caption;
		}

		public String getOcrText() {
			return ocrText;
		}
	}
}
