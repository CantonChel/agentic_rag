package com.agenticrag.app.benchmark.materialize;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.packageio.PortableBenchmarkPackage;
import com.agenticrag.app.benchmark.packageio.PortableNormalizedDocument;
import com.agenticrag.app.benchmark.packageio.PortableSourceFileRecord;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.service.TextSanitizer;
import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.splitter.TextSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkKnowledgeBaseMapper {
	private final ObjectMapper objectMapper;
	private final TextSanitizer textSanitizer;
	private final TextSplitter textSplitter;

	public BenchmarkKnowledgeBaseMapper(ObjectMapper objectMapper, TextSanitizer textSanitizer, TextSplitter textSplitter) {
		this.objectMapper = objectMapper;
		this.textSanitizer = textSanitizer;
		this.textSplitter = textSplitter;
	}

	public BenchmarkKnowledgeBaseMaterialization materialize(
		BenchmarkBuildEntity build,
		PortableBenchmarkPackage benchmarkPackage
	) {
		if (build == null) {
			throw new IllegalArgumentException("build is required");
		}
		if (benchmarkPackage == null) {
			throw new IllegalArgumentException("benchmarkPackage is required");
		}

		Map<String, PortableSourceFileRecord> sourceFilesByPath = indexSourceFiles(benchmarkPackage);
		Instant now = Instant.now();
		List<KnowledgeEntity> knowledgeEntities = new ArrayList<>();
		List<ChunkEntity> chunkEntities = new ArrayList<>();
		List<TextChunk> indexChunks = new ArrayList<>();
		List<BenchmarkEmbeddingInput> embeddingInputs = new ArrayList<>();

		for (PortableNormalizedDocument document : benchmarkPackage.getNormalizedDocuments()) {
			if (document == null) {
				continue;
			}
			String docPath = safe(document.getDocPath());
			String normalizedText = textSanitizer.sanitizeText(safe(document.getNormalizedText()));
			if (docPath.isEmpty() || normalizedText.isEmpty()) {
				continue;
			}

			String knowledgeId = toKnowledgeId(build.getBuildId(), docPath);
			knowledgeEntities.add(buildKnowledgeEntity(build, document, knowledgeId, sourceFilesByPath.get(docPath), now));

			List<RuntimeChunkSlice> slices = buildRuntimeChunkSlices(build, document, knowledgeId);
			for (int i = 0; i < slices.size(); i++) {
				RuntimeChunkSlice slice = slices.get(i);
				String previousChunkId = i > 0 ? slices.get(i - 1).chunkId : null;
				String nextChunkId = i + 1 < slices.size() ? slices.get(i + 1).chunkId : null;
				ChunkEntity chunk = buildChunkEntity(
					build,
					document,
					knowledgeId,
					slice,
					previousChunkId,
					nextChunkId,
					now
				);
				chunkEntities.add(chunk);
				Map<String, Object> indexMetadata = parseMetadata(chunk.getMetadataJson());
				indexChunks.add(new TextChunk(chunk.getChunkId(), knowledgeId, chunk.getContent(), null, indexMetadata));
				embeddingInputs.add(new BenchmarkEmbeddingInput(knowledgeId, chunk.getChunkId(), chunk.getContent()));
			}
		}

		return new BenchmarkKnowledgeBaseMaterialization(knowledgeEntities, chunkEntities, indexChunks, embeddingInputs);
	}

	private Map<String, PortableSourceFileRecord> indexSourceFiles(PortableBenchmarkPackage benchmarkPackage) {
		Map<String, PortableSourceFileRecord> out = new LinkedHashMap<>();
		if (benchmarkPackage == null || benchmarkPackage.getSourceManifest() == null || benchmarkPackage.getSourceManifest().getFiles() == null) {
			return out;
		}
		for (PortableSourceFileRecord record : benchmarkPackage.getSourceManifest().getFiles()) {
			if (record == null) {
				continue;
			}
			String docPath = safe(record.getPath());
			if (docPath.isEmpty()) {
				continue;
			}
			out.put(docPath, record);
		}
		return out;
	}

	private KnowledgeEntity buildKnowledgeEntity(
		BenchmarkBuildEntity build,
		PortableNormalizedDocument document,
		String knowledgeId,
		PortableSourceFileRecord sourceFileRecord,
		Instant now
	) {
		String docPath = safe(document.getDocPath());
		String normalizedText = textSanitizer.sanitizeText(safe(document.getNormalizedText()));
		KnowledgeEntity entity = new KnowledgeEntity();
		entity.setId(knowledgeId);
		entity.setKnowledgeBaseId(build.getKnowledgeBaseId());
		entity.setFileName(fileName(docPath));
		entity.setFileType(fileType(docPath));
		entity.setFilePath("package://" + safe(build.getProjectKey()) + "/" + safe(build.getSuiteVersion()) + "/" + docPath);
		entity.setFileSize(normalizedText.getBytes(StandardCharsets.UTF_8).length);
		entity.setFileHash(resolveKnowledgeFileHash(normalizedText, sourceFileRecord));
		entity.setParseStatus(KnowledgeParseStatus.COMPLETED);
		entity.setEnableStatus(KnowledgeEnableStatus.ENABLED);
		entity.setMetadataJson(toJson(buildKnowledgeMetadata(build, document)));
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
	}

	private ChunkEntity buildChunkEntity(
		BenchmarkBuildEntity build,
		PortableNormalizedDocument document,
		String knowledgeId,
		RuntimeChunkSlice slice,
		String previousChunkId,
		String nextChunkId,
		Instant now
	) {
		String docPath = safe(document.getDocPath());
		ChunkEntity entity = new ChunkEntity();
		entity.setChunkId(slice.chunkId);
		entity.setKnowledgeId(knowledgeId);
		entity.setChunkType(ChunkType.TEXT);
		entity.setChunkIndex(slice.chunkIndex);
		entity.setStartAt(slice.startAt);
		entity.setEndAt(slice.endAt);
		entity.setParentChunkId(null);
		entity.setPreChunkId(previousChunkId);
		entity.setNextChunkId(nextChunkId);
		entity.setContent(slice.content);
		entity.setImageInfoJson(null);
		entity.setMetadataJson(toJson(buildChunkMetadata(build, docPath, slice)));
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
	}

	private Map<String, Object> buildKnowledgeMetadata(BenchmarkBuildEntity build, PortableNormalizedDocument document) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("import_source", "benchmark_package");
		metadata.put("build_id", safe(build.getBuildId()));
		metadata.put("knowledge_base_id", safe(build.getKnowledgeBaseId()));
		metadata.put("project_key", safe(build.getProjectKey()));
		metadata.put("suite_version", safe(build.getSuiteVersion()));
		metadata.put("source_set_id", safe(build.getSourceSetId()));
		metadata.put("doc_path", safe(document.getDocPath()));
		metadata.put("title", safe(document.getTitle()));
		metadata.put("gold_package_version", safe(build.getGoldPackageVersion()));
		return metadata;
	}

	private Map<String, Object> buildChunkMetadata(BenchmarkBuildEntity build, String docPath, RuntimeChunkSlice slice) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("source", buildSource(docPath, slice.chunkIndex));
		metadata.put("build_id", safe(build.getBuildId()));
		metadata.put("knowledge_base_id", safe(build.getKnowledgeBaseId()));
		metadata.put("project_key", safe(build.getProjectKey()));
		metadata.put("suite_version", safe(build.getSuiteVersion()));
		metadata.put("source_set_id", safe(build.getSourceSetId()));
		metadata.put("doc_path", docPath);
		metadata.put("chunk_index", slice.chunkIndex);
		metadata.put("start_at", slice.startAt);
		metadata.put("end_at", slice.endAt);
		return metadata;
	}

	private Map<String, Object> parseMetadata(String metadataJson) {
		if (metadataJson == null || metadataJson.trim().isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = objectMapper.readValue(metadataJson, Map.class);
			return parsed != null ? parsed : Collections.emptyMap();
		} catch (Exception e) {
			return Collections.emptyMap();
		}
	}

	private String resolveKnowledgeFileHash(String normalizedText, PortableSourceFileRecord sourceFileRecord) {
		String sourceHash = sourceFileRecord != null ? safe(sourceFileRecord.getSha256()) : "";
		if (!sourceHash.isEmpty()) {
			return sourceHash;
		}
		return sha256(normalizedText);
	}

	private List<RuntimeChunkSlice> buildRuntimeChunkSlices(
		BenchmarkBuildEntity build,
		PortableNormalizedDocument document,
		String knowledgeId
	) {
		String normalizedText = textSanitizer.sanitizeText(safe(document.getNormalizedText()));
		Document splitterDocument = new Document(knowledgeId, normalizedText, buildDocumentMetadata(build, document));
		List<TextChunk> splitChunks = textSplitter != null ? textSplitter.split(splitterDocument) : Collections.emptyList();
		if (splitChunks.isEmpty()) {
			Map<String, Object> metadata = buildDocumentMetadata(build, document);
			return List.of(new RuntimeChunkSlice(knowledgeId + ":0", 0, 0, normalizedText.length(), normalizedText, metadata));
		}

		List<RuntimeChunkSlice> out = new ArrayList<>();
		int previousStart = 0;
		int previousEnd = 0;
		int previousLength = 0;
		for (int i = 0; i < splitChunks.size(); i++) {
			TextChunk splitChunk = splitChunks.get(i);
			String content = textSanitizer.sanitizeText(splitChunk != null ? splitChunk.getText() : "");
			if (content.isEmpty()) {
				continue;
			}
			int startAt = findChunkStart(normalizedText, content, previousStart, previousEnd, previousLength);
			int endAt = Math.min(normalizedText.length(), startAt + content.length());
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.putAll(buildDocumentMetadata(build, document));
			if (splitChunk != null && splitChunk.getMetadata() != null) {
				metadata.putAll(splitChunk.getMetadata());
			}
			out.add(new RuntimeChunkSlice(
				safe(splitChunk != null ? splitChunk.getChunkId() : knowledgeId + ":" + i),
				i,
				startAt,
				endAt,
				content,
				metadata
			));
			previousStart = startAt;
			previousEnd = endAt;
			previousLength = content.length();
		}
		return out;
	}

	private Map<String, Object> buildDocumentMetadata(BenchmarkBuildEntity build, PortableNormalizedDocument document) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("build_id", safe(build.getBuildId()));
		metadata.put("knowledge_base_id", safe(build.getKnowledgeBaseId()));
		metadata.put("project_key", safe(build.getProjectKey()));
		metadata.put("suite_version", safe(build.getSuiteVersion()));
		metadata.put("source_set_id", safe(build.getSourceSetId()));
		metadata.put("doc_path", safe(document.getDocPath()));
		return metadata;
	}

	private int findChunkStart(String fullText, String chunkText, int previousStart, int previousEnd, int previousLength) {
		if (fullText.isEmpty() || chunkText.isEmpty()) {
			return 0;
		}
		int overlapWindow = Math.min(Math.max(previousLength, chunkText.length()), 512);
		int searchFrom = Math.max(0, previousEnd - overlapWindow);
		int startAt = fullText.indexOf(chunkText, searchFrom);
		if (startAt >= 0) {
			return startAt;
		}
		startAt = fullText.indexOf(chunkText, Math.max(0, previousStart));
		if (startAt >= 0) {
			return startAt;
		}
		startAt = fullText.indexOf(chunkText);
		if (startAt >= 0) {
			return startAt;
		}
		return Math.max(0, Math.min(fullText.length(), previousEnd));
	}

	private String buildSource(String docPath, int chunkIndex) {
		return docPath + "#chunk-" + chunkIndex;
	}

	private String fileName(String docPath) {
		String normalized = docPath.replace('\\', '/');
		int index = normalized.lastIndexOf('/');
		return index >= 0 ? normalized.substring(index + 1) : normalized;
	}

	private String fileType(String docPath) {
		String name = fileName(docPath);
		int index = name.lastIndexOf('.');
		if (index < 0 || index == name.length() - 1) {
			return "bin";
		}
		return name.substring(index + 1).toLowerCase(Locale.ROOT);
	}

	private String toKnowledgeId(String buildId, String docPath) {
		return sha1(buildId + "|" + docPath).substring(0, 32);
	}

	private String toJson(Map<String, Object> payload) {
		try {
			return objectMapper.writeValueAsString(textSanitizer.sanitizeMap(payload, objectMapper));
		} catch (Exception e) {
			throw new IllegalStateException("failed to serialize benchmark mapping metadata", e);
		}
	}

	private String sha1(String value) {
		return hex("SHA-1", value);
	}

	private String sha256(String value) {
		return hex("SHA-256", value);
	}

	private String hex(String algorithm, String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance(algorithm);
			byte[] bytes = digest.digest(safe(value).getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder();
			for (byte b : bytes) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		} catch (Exception e) {
			throw new IllegalStateException("failed to hash benchmark mapping content", e);
		}
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private static final class RuntimeChunkSlice {
		private final String chunkId;
		private final int chunkIndex;
		private final int startAt;
		private final int endAt;
		private final String content;
		private final Map<String, Object> metadata;

		private RuntimeChunkSlice(
			String chunkId,
			int chunkIndex,
			int startAt,
			int endAt,
			String content,
			Map<String, Object> metadata
		) {
			this.chunkId = chunkId;
			this.chunkIndex = chunkIndex;
			this.startAt = startAt;
			this.endAt = endAt;
			this.content = content;
			this.metadata = metadata;
		}
	}
}
