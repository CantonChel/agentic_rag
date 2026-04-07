package com.agenticrag.app.benchmark.mapping;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.packageio.PortableAuthoringBlock;
import com.agenticrag.app.benchmark.packageio.PortableBenchmarkPackage;
import com.agenticrag.app.benchmark.packageio.PortableNormalizedDocument;
import com.agenticrag.app.ingest.entity.ChunkEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BenchmarkBuildChunkMappingService {
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};
	private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
	};

	private final BenchmarkBuildChunkMappingRepository repository;
	private final ObjectMapper objectMapper;

	public BenchmarkBuildChunkMappingService(
		BenchmarkBuildChunkMappingRepository repository,
		ObjectMapper objectMapper
	) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void rebuildMappings(
		BenchmarkBuildEntity build,
		PortableBenchmarkPackage benchmarkPackage,
		List<ChunkEntity> chunkEntities
	) {
		if (build == null) {
			throw new IllegalArgumentException("build is required");
		}
		repository.deleteByBuildId(normalize(build.getBuildId()));
		if (benchmarkPackage == null || chunkEntities == null || chunkEntities.isEmpty()) {
			return;
		}

		Map<String, PortableNormalizedDocument> documentsByPath = benchmarkPackage.getNormalizedDocuments().stream()
			.filter(item -> item != null && !normalize(item.getDocPath()).isEmpty())
			.collect(Collectors.toMap(item -> normalize(item.getDocPath()), item -> item, (left, right) -> left, LinkedHashMap::new));
		Map<String, List<GoldBlockInterval>> goldIntervalsByDoc = buildGoldIntervals(benchmarkPackage, documentsByPath);
		Instant now = Instant.now();
		List<BenchmarkBuildChunkMappingEntity> entities = new ArrayList<>();

		for (ChunkEntity chunkEntity : chunkEntities) {
			if (chunkEntity == null) {
				continue;
			}
			Map<String, Object> metadata = parseMetadata(chunkEntity.getMetadataJson());
			String docPath = normalize(stringValue(metadata.get("doc_path")));
			if (docPath.isEmpty()) {
				continue;
			}
			List<GoldOverlap> overlaps = collectOverlaps(
				goldIntervalsByDoc.getOrDefault(docPath, Collections.emptyList()),
				defaultInt(chunkEntity.getStartAt()),
				defaultInt(chunkEntity.getEndAt())
			);
			List<String> goldBlockIds = overlaps.stream()
				.map(overlap -> overlap.blockId)
				.collect(Collectors.toList());
			String primaryGoldBlockId = overlaps.isEmpty() ? null : overlaps.get(0).blockId;

			BenchmarkBuildChunkMappingEntity entity = new BenchmarkBuildChunkMappingEntity();
			entity.setBuildId(normalize(build.getBuildId()));
			entity.setKnowledgeId(normalize(chunkEntity.getKnowledgeId()));
			entity.setChunkId(normalize(chunkEntity.getChunkId()));
			entity.setDocPath(docPath);
			entity.setStartAt(defaultInt(chunkEntity.getStartAt()));
			entity.setEndAt(defaultInt(chunkEntity.getEndAt()));
			entity.setGoldBlockIdsJson(writeGoldBlockIds(goldBlockIds));
			entity.setPrimaryGoldBlockId(primaryGoldBlockId);
			entity.setCreatedAt(now);
			entity.setUpdatedAt(now);
			entities.add(entity);
		}

		if (!entities.isEmpty()) {
			repository.saveAll(entities);
		}
	}

	@Transactional(readOnly = true)
	public List<BenchmarkBuildChunkMappingView> listMappings(String buildId, String chunkId) {
		String normalizedBuildId = normalize(buildId);
		if (normalizedBuildId.isEmpty()) {
			throw new IllegalArgumentException("buildId is required");
		}
		String normalizedChunkId = normalize(chunkId);
		List<BenchmarkBuildChunkMappingEntity> rows = normalizedChunkId.isEmpty()
			? repository.findByBuildIdOrderByDocPathAscStartAtAscIdAsc(normalizedBuildId)
			: repository.findByBuildIdAndChunkIdOrderByDocPathAscStartAtAscIdAsc(normalizedBuildId, normalizedChunkId);
		return rows.stream().map(this::toView).collect(Collectors.toList());
	}

	@Transactional
	public void deleteByBuildId(String buildId) {
		String normalizedBuildId = normalize(buildId);
		if (normalizedBuildId.isEmpty()) {
			return;
		}
		repository.deleteByBuildId(normalizedBuildId);
	}

	private BenchmarkBuildChunkMappingView toView(BenchmarkBuildChunkMappingEntity entity) {
		return new BenchmarkBuildChunkMappingView(
			entity.getId(),
			entity.getBuildId(),
			entity.getKnowledgeId(),
			entity.getChunkId(),
			entity.getDocPath(),
			entity.getStartAt(),
			entity.getEndAt(),
			readGoldBlockIds(entity.getGoldBlockIdsJson()),
			entity.getPrimaryGoldBlockId()
		);
	}

	private Map<String, List<GoldBlockInterval>> buildGoldIntervals(
		PortableBenchmarkPackage benchmarkPackage,
		Map<String, PortableNormalizedDocument> documentsByPath
	) {
		Map<String, List<GoldBlockInterval>> out = new LinkedHashMap<>();
		for (PortableAuthoringBlock block : benchmarkPackage.getAuthoringBlocks()) {
			if (block == null) {
				continue;
			}
			String docPath = normalize(block.getDocPath());
			PortableNormalizedDocument document = documentsByPath.get(docPath);
			if (docPath.isEmpty() || document == null) {
				continue;
			}
			LineOffsetTable offsetTable = buildLineOffsetTable(document.getNormalizedText());
			int startAt = offsetTable.lineStart(block.getStartLine());
			int endAt = offsetTable.lineTextEnd(block.getEndLine());
			out.computeIfAbsent(docPath, ignored -> new ArrayList<>())
				.add(new GoldBlockInterval(normalize(block.getBlockId()), startAt, endAt));
		}
		return out;
	}

	private List<GoldOverlap> collectOverlaps(List<GoldBlockInterval> goldIntervals, int chunkStart, int chunkEnd) {
		if (goldIntervals == null || goldIntervals.isEmpty()) {
			return Collections.emptyList();
		}
		List<GoldOverlap> overlaps = new ArrayList<>();
		for (GoldBlockInterval interval : goldIntervals) {
			int overlap = overlap(chunkStart, chunkEnd, interval.startAt, interval.endAt);
			if (overlap > 0) {
				overlaps.add(new GoldOverlap(interval.blockId, overlap));
			}
		}
		overlaps.sort((left, right) -> {
			int compare = Integer.compare(right.overlapChars, left.overlapChars);
			if (compare != 0) {
				return compare;
			}
			return left.blockId.compareTo(right.blockId);
		});
		return overlaps;
	}

	private int overlap(int leftStart, int leftEnd, int rightStart, int rightEnd) {
		int start = Math.max(leftStart, rightStart);
		int end = Math.min(leftEnd, rightEnd);
		return Math.max(0, end - start);
	}

	private LineOffsetTable buildLineOffsetTable(String normalizedText) {
		String text = normalizedText != null ? normalizedText : "";
		String[] lines = text.split("\n", -1);
		List<Integer> starts = new ArrayList<>();
		List<Integer> ends = new ArrayList<>();
		int cursor = 0;
		for (String line : lines) {
			starts.add(cursor);
			int textEnd = cursor + (line != null ? line.length() : 0);
			ends.add(textEnd);
			cursor = textEnd + 1;
		}
		if (starts.isEmpty()) {
			starts.add(0);
			ends.add(0);
		}
		return new LineOffsetTable(starts, ends);
	}

	private Map<String, Object> parseMetadata(String metadataJson) {
		if (metadataJson == null || metadataJson.trim().isEmpty()) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(metadataJson, MAP_TYPE);
			return parsed != null ? parsed : Collections.emptyMap();
		} catch (Exception ignored) {
			return Collections.emptyMap();
		}
	}

	private List<String> readGoldBlockIds(String goldBlockIdsJson) {
		if (goldBlockIdsJson == null || goldBlockIdsJson.trim().isEmpty()) {
			return Collections.emptyList();
		}
		try {
			List<String> parsed = objectMapper.readValue(goldBlockIdsJson, STRING_LIST_TYPE);
			if (parsed == null) {
				return Collections.emptyList();
			}
			LinkedHashSet<String> deduped = new LinkedHashSet<>();
			for (String item : parsed) {
				String normalized = normalize(item);
				if (!normalized.isEmpty()) {
					deduped.add(normalized);
				}
			}
			return new ArrayList<>(deduped);
		} catch (Exception ignored) {
			return Collections.emptyList();
		}
	}

	private String writeGoldBlockIds(List<String> goldBlockIds) {
		try {
			LinkedHashSet<String> deduped = new LinkedHashSet<>();
			for (String item : goldBlockIds) {
				String normalized = normalize(item);
				if (!normalized.isEmpty()) {
					deduped.add(normalized);
				}
			}
			return objectMapper.writeValueAsString(new ArrayList<>(deduped));
		} catch (Exception e) {
			throw new IllegalStateException("failed to serialize gold block ids", e);
		}
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private int defaultInt(Integer value) {
		return value != null ? value : 0;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	private static final class GoldBlockInterval {
		private final String blockId;
		private final int startAt;
		private final int endAt;

		private GoldBlockInterval(String blockId, int startAt, int endAt) {
			this.blockId = blockId;
			this.startAt = startAt;
			this.endAt = endAt;
		}
	}

	private static final class GoldOverlap {
		private final String blockId;
		private final int overlapChars;

		private GoldOverlap(String blockId, int overlapChars) {
			this.blockId = blockId;
			this.overlapChars = overlapChars;
		}
	}

	private static final class LineOffsetTable {
		private final List<Integer> starts;
		private final List<Integer> textEnds;

		private LineOffsetTable(List<Integer> starts, List<Integer> textEnds) {
			this.starts = starts;
			this.textEnds = textEnds;
		}

		private int lineStart(int lineNumber) {
			int index = clampIndex(lineNumber);
			return starts.get(index);
		}

		private int lineTextEnd(int lineNumber) {
			int index = clampIndex(lineNumber);
			return textEnds.get(index);
		}

		private int clampIndex(int lineNumber) {
			if (lineNumber <= 1) {
				return 0;
			}
			return Math.min(lineNumber - 1, starts.size() - 1);
		}
	}
}
