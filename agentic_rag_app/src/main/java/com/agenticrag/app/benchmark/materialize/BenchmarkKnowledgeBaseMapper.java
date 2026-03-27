package com.agenticrag.app.benchmark.materialize;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.packageio.PortableBenchmarkPackage;
import com.agenticrag.app.benchmark.packageio.PortableEvidenceUnit;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.model.ChunkType;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.service.TextSanitizer;
import com.agenticrag.app.rag.model.TextChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BenchmarkKnowledgeBaseMapper {
	private final ObjectMapper objectMapper;
	private final TextSanitizer textSanitizer;

	public BenchmarkKnowledgeBaseMapper(ObjectMapper objectMapper, TextSanitizer textSanitizer) {
		this.objectMapper = objectMapper;
		this.textSanitizer = textSanitizer;
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

		Map<String, List<PortableEvidenceUnit>> evidenceByDoc = groupByDocPath(benchmarkPackage.getEvidenceUnits());
		Instant now = Instant.now();
		List<KnowledgeEntity> knowledgeEntities = new ArrayList<>();
		List<ChunkEntity> chunkEntities = new ArrayList<>();
		List<TextChunk> indexChunks = new ArrayList<>();
		List<BenchmarkEmbeddingInput> embeddingInputs = new ArrayList<>();

		for (Map.Entry<String, List<PortableEvidenceUnit>> entry : evidenceByDoc.entrySet()) {
			String docPath = entry.getKey();
			List<PortableEvidenceUnit> units = entry.getValue();
			if (units == null || units.isEmpty()) {
				continue;
			}

			String knowledgeId = toKnowledgeId(build.getBuildId(), docPath);
			knowledgeEntities.add(buildKnowledgeEntity(build, docPath, units, knowledgeId, now));

			for (int i = 0; i < units.size(); i++) {
				PortableEvidenceUnit unit = units.get(i);
				String previousChunkId = i > 0 ? units.get(i - 1).getEvidenceId() : null;
				String nextChunkId = i + 1 < units.size() ? units.get(i + 1).getEvidenceId() : null;
				ChunkEntity chunk = buildChunkEntity(build, docPath, knowledgeId, unit, i, previousChunkId, nextChunkId, now);
				chunkEntities.add(chunk);
				Map<String, Object> indexMetadata = parseMetadata(chunk.getMetadataJson());
				indexChunks.add(new TextChunk(chunk.getChunkId(), knowledgeId, chunk.getContent(), null, indexMetadata));
				embeddingInputs.add(new BenchmarkEmbeddingInput(knowledgeId, chunk.getChunkId(), chunk.getContent()));
			}
		}

		return new BenchmarkKnowledgeBaseMaterialization(knowledgeEntities, chunkEntities, indexChunks, embeddingInputs);
	}

	private Map<String, List<PortableEvidenceUnit>> groupByDocPath(List<PortableEvidenceUnit> evidenceUnits) {
		Map<String, List<PortableEvidenceUnit>> out = new LinkedHashMap<>();
		if (evidenceUnits == null) {
			return out;
		}
		for (PortableEvidenceUnit unit : evidenceUnits) {
			if (unit == null) {
				continue;
			}
			String docPath = safe(unit.getDocPath());
			if (docPath.isEmpty()) {
				continue;
			}
			out.computeIfAbsent(docPath, ignored -> new ArrayList<>()).add(unit);
		}
		return out;
	}

	private KnowledgeEntity buildKnowledgeEntity(
		BenchmarkBuildEntity build,
		String docPath,
		List<PortableEvidenceUnit> units,
		String knowledgeId,
		Instant now
	) {
		KnowledgeEntity entity = new KnowledgeEntity();
		entity.setId(knowledgeId);
		entity.setKnowledgeBaseId(build.getKnowledgeBaseId());
		entity.setFileName(fileName(docPath));
		entity.setFileType(fileType(docPath));
		entity.setFilePath("package://" + safe(build.getProjectKey()) + "/" + safe(build.getSuiteVersion()) + "/" + docPath);
		entity.setFileSize(documentContent(units).getBytes(StandardCharsets.UTF_8).length);
		entity.setFileHash(sha256(joinSourceHashes(units)));
		entity.setParseStatus(KnowledgeParseStatus.COMPLETED);
		entity.setEnableStatus(KnowledgeEnableStatus.ENABLED);
		entity.setMetadataJson(toJson(buildKnowledgeMetadata(build, docPath)));
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
	}

	private ChunkEntity buildChunkEntity(
		BenchmarkBuildEntity build,
		String docPath,
		String knowledgeId,
		PortableEvidenceUnit unit,
		int chunkIndex,
		String previousChunkId,
		String nextChunkId,
		Instant now
	) {
		ChunkEntity entity = new ChunkEntity();
		entity.setChunkId(safe(unit.getEvidenceId()));
		entity.setKnowledgeId(knowledgeId);
		entity.setChunkType(ChunkType.TEXT);
		entity.setChunkIndex(chunkIndex);
		entity.setStartAt(null);
		entity.setEndAt(null);
		entity.setParentChunkId(null);
		entity.setPreChunkId(previousChunkId);
		entity.setNextChunkId(nextChunkId);
		entity.setContent(textSanitizer.sanitizeText(safe(unit.getCanonicalText())));
		entity.setImageInfoJson(null);
		entity.setMetadataJson(toJson(buildChunkMetadata(build, docPath, unit)));
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		return entity;
	}

	private Map<String, Object> buildKnowledgeMetadata(BenchmarkBuildEntity build, String docPath) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("import_source", "benchmark_package");
		metadata.put("build_id", safe(build.getBuildId()));
		metadata.put("project_key", safe(build.getProjectKey()));
		metadata.put("suite_version", safe(build.getSuiteVersion()));
		metadata.put("doc_path", docPath);
		return metadata;
	}

	private Map<String, Object> buildChunkMetadata(BenchmarkBuildEntity build, String docPath, PortableEvidenceUnit unit) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("source", buildSource(docPath, unit.getSectionTitle(), unit.getSectionKey()));
		metadata.put("build_id", safe(build.getBuildId()));
		metadata.put("knowledge_base_id", safe(build.getKnowledgeBaseId()));
		metadata.put("evidence_id", safe(unit.getEvidenceId()));
		metadata.put("doc_path", docPath);
		metadata.put("section_key", safe(unit.getSectionKey()));
		metadata.put("section_title", safe(unit.getSectionTitle()));
		metadata.put("anchor", safe(unit.getAnchor()));
		metadata.put("source_hash", safe(unit.getSourceHash()));
		metadata.put("extractor_version", safe(unit.getExtractorVersion()));
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

	private String documentContent(List<PortableEvidenceUnit> units) {
		List<String> parts = new ArrayList<>();
		for (PortableEvidenceUnit unit : units) {
			String value = textSanitizer.sanitizeText(safe(unit.getCanonicalText()));
			if (!value.isEmpty()) {
				parts.add(value);
			}
		}
		return String.join("\n\n", parts);
	}

	private String joinSourceHashes(List<PortableEvidenceUnit> units) {
		List<String> parts = new ArrayList<>();
		for (PortableEvidenceUnit unit : units) {
			String sourceHash = safe(unit.getSourceHash());
			if (!sourceHash.isEmpty()) {
				parts.add(sourceHash);
			}
		}
		return String.join("\n", parts);
	}

	private String buildSource(String docPath, String sectionTitle, String sectionKey) {
		String anchor = safe(sectionTitle);
		if (anchor.isEmpty()) {
			anchor = safe(sectionKey);
		}
		return anchor.isEmpty() ? docPath : docPath + "#" + anchor;
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
}
