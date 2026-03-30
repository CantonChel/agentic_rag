package com.agenticrag.app.memory;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.agenticrag.app.rag.store.CosineSimilarity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MemoryRecallService {
	private final MemoryProperties properties;
	private final EmbeddingModel embeddingModel;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiEmbeddingProperties openAiEmbeddingProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;
	private final ObjectMapper objectMapper;
	private final Map<String, CachedDocument> cacheByFilePath = new ConcurrentHashMap<>();

	public MemoryRecallService(
		MemoryProperties properties,
		EmbeddingModel embeddingModel,
		MemoryFileService memoryFileService,
		MemoryBlockParser memoryBlockParser,
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiEmbeddingProperties openAiEmbeddingProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties,
		ObjectMapper objectMapper
	) {
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.memoryFileService = memoryFileService;
		this.memoryBlockParser = memoryBlockParser;
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiEmbeddingProperties = openAiEmbeddingProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
		this.objectMapper = objectMapper;
	}

	public List<MemorySearchHit> search(String userId, String query, Integer requestedTopK) {
		if (!properties.isEnabled()) {
			return new ArrayList<>();
		}
		String normalizedQuery = query == null ? "" : query.trim();
		if (normalizedQuery.isEmpty()) {
			return new ArrayList<>();
		}

		List<CandidateChunk> candidates = new ArrayList<>();
		for (Path file : memoryFileService.discoverMemoryFiles(userId, true)) {
			candidates.addAll(loadOrRefreshFileChunks(userId, file));
		}
		if (candidates.isEmpty()) {
			return new ArrayList<>();
		}

		List<Double> queryEmbedding = embedOne(normalizedQuery);
		for (CandidateChunk candidate : candidates) {
			double lexical = lexicalScore(normalizedQuery, candidate.text);
			double vector = 0.0;
			if (queryEmbedding != null && candidate.embedding != null) {
				vector = CosineSimilarity.cosine(queryEmbedding, candidate.embedding);
			}
			candidate.score = queryEmbedding != null && candidate.embedding != null
				? (0.68 * vector + 0.32 * lexical)
				: lexical;
		}

		int topK = requestedTopK != null && requestedTopK > 0 ? requestedTopK : properties.getTopK();
		int topCandidates = properties.getTopKCandidates() > 0 ? properties.getTopKCandidates() : 20;
		Set<String> seen = new LinkedHashSet<>();
		return candidates.stream()
			.sorted(Comparator.comparingDouble((CandidateChunk c) -> c.score).reversed())
			.limit(topCandidates)
			.filter(candidate -> seen.add(candidate.path + "|" + candidate.blockId + "|" + candidate.lineStart + "|" + candidate.lineEnd))
			.limit(topK > 0 ? topK : 5)
			.map(candidate -> new MemorySearchHit(
				candidate.path,
				candidate.kind,
				candidate.blockId,
				candidate.lineStart,
				candidate.lineEnd,
				candidate.score,
				candidate.text
			))
			.collect(Collectors.toList());
	}

	public MemoryReadResult get(String userId, String path, Integer lineStart, Integer lineEnd) {
		Path file = memoryFileService.resolveReadablePath(userId, path);
		if (file == null) {
			return null;
		}
		int start = lineStart != null ? lineStart.intValue() : 1;
		int end = lineEnd != null ? lineEnd.intValue() : start;
		if (start < 1 || end < start) {
			return null;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
		if (lines.isEmpty() || start > lines.size()) {
			return null;
		}
		int boundedEnd = Math.min(end, lines.size());
		StringBuilder content = new StringBuilder();
		for (int i = start; i <= boundedEnd; i++) {
			if (content.length() > 0) {
				content.append('\n');
			}
			content.append(lines.get(i - 1));
		}

		String kind = memoryFileService.kindOf(userId, file);
		String blockId = null;
		for (ParsedMemoryBlock block : memoryBlockParser.parse(userId, file)) {
			if (start >= block.getStartLine() && boundedEnd <= block.getEndLine()) {
				blockId = block.getMetadata() != null ? block.getMetadata().getBlockId() : null;
				kind = block.getKind();
				break;
			}
		}
		return new MemoryReadResult(
			memoryFileService.relPath(file),
			kind,
			blockId,
			start,
			boundedEnd,
			content.toString()
		);
	}

	private List<CandidateChunk> loadOrRefreshFileChunks(String userId, Path file) {
		String abs = file.toAbsolutePath().normalize().toString();
		String content;
		try {
			content = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new ArrayList<>();
		}
		if (content == null || content.trim().isEmpty()) {
			return new ArrayList<>();
		}
		String hash = sha256(content);
		CachedDocument cached = cacheByFilePath.get(abs);
		if (cached != null && hash.equals(cached.contentHash)) {
			return cloneCandidates(cached.chunks);
		}

		List<ChunkSeed> seeds = new ArrayList<>();
		for (ParsedMemoryBlock block : memoryBlockParser.parseContent(userId, file, content)) {
			seeds.addAll(splitBlockIntoChunks(block));
		}
		if (seeds.isEmpty()) {
			cacheByFilePath.put(abs, new CachedDocument(hash, new ArrayList<>()));
			return new ArrayList<>();
		}

		Map<String, List<Double>> embeddingsByChunkHash = loadEmbeddings(seeds);
		List<CandidateChunk> chunks = new ArrayList<>();
		for (ChunkSeed seed : seeds) {
			chunks.add(new CandidateChunk(
				seed.path,
				seed.kind,
				seed.blockId,
				seed.lineStart,
				seed.lineEnd,
				seed.text,
				embeddingsByChunkHash.get(seed.chunkHash)
			));
		}
		cacheByFilePath.put(abs, new CachedDocument(hash, cloneCandidates(chunks)));
		return chunks;
	}

	private List<ChunkSeed> splitBlockIntoChunks(ParsedMemoryBlock block) {
		String content = block.getContent() != null ? block.getContent().trim() : "";
		if (content.isEmpty()) {
			return new ArrayList<>();
		}
		int maxChars = properties.getMaxChunkChars() > 0 ? properties.getMaxChunkChars() : 800;
		String[] lines = content.split("\\r?\\n", -1);
		List<ChunkSeed> out = new ArrayList<>();
		StringBuilder buffer = new StringBuilder();
		int chunkStartLine = block.getStartLine();
		int currentLine = block.getStartLine();
		for (String line : lines) {
			String candidate = buffer.length() == 0 ? line : buffer + "\n" + line;
			if (buffer.length() > 0 && candidate.length() > maxChars) {
				addChunk(out, block, chunkStartLine, currentLine - 1, buffer.toString());
				buffer.setLength(0);
				chunkStartLine = currentLine;
			}
			if (buffer.length() > 0) {
				buffer.append('\n');
			}
			buffer.append(line);
			currentLine++;
		}
		if (buffer.length() > 0) {
			addChunk(out, block, chunkStartLine, currentLine - 1, buffer.toString());
		}
		return out;
	}

	private void addChunk(List<ChunkSeed> out, ParsedMemoryBlock block, int lineStart, int lineEnd, String text) {
		String normalized = text == null ? "" : text.trim();
		if (normalized.isEmpty()) {
			return;
		}
		out.add(new ChunkSeed(
			block.getRelativePath(),
			block.getKind(),
			block.getMetadata() != null ? block.getMetadata().getBlockId() : null,
			lineStart,
			lineEnd,
			normalized,
			sha256(normalized)
		));
	}

	private Map<String, List<Double>> loadEmbeddings(List<ChunkSeed> seeds) {
		Map<String, List<Double>> out = new LinkedHashMap<>();
		List<ChunkSeed> missing = new ArrayList<>();
		for (ChunkSeed seed : seeds) {
			List<Double> cached = readCachedEmbedding(seed.chunkHash);
			if (cached != null) {
				out.put(seed.chunkHash, cached);
			} else {
				missing.add(seed);
			}
		}
		if (!missing.isEmpty()) {
			List<List<Double>> generated = embedBatch(missing.stream().map(seed -> seed.text).collect(Collectors.toList()));
			for (int i = 0; i < missing.size(); i++) {
				List<Double> vector = i < generated.size() ? generated.get(i) : null;
				if (vector == null) {
					continue;
				}
				ChunkSeed seed = missing.get(i);
				out.put(seed.chunkHash, vector);
				writeCachedEmbedding(seed.chunkHash, vector);
			}
		}
		return out;
	}

	private List<List<Double>> embedBatch(List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return new ArrayList<>();
		}
		try {
			List<List<Double>> vectors = embeddingModel.embedTexts(texts);
			if (vectors == null || vectors.size() != texts.size()) {
				return new ArrayList<>();
			}
			return vectors;
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	private List<Double> embedOne(String text) {
		List<List<Double>> vectors = embedBatch(Collections.singletonList(text));
		if (vectors.isEmpty()) {
			return null;
		}
		return vectors.get(0);
	}

	private List<Double> readCachedEmbedding(String chunkHash) {
		Path file = embeddingCacheFile(chunkHash);
		if (!Files.exists(file) || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			return objectMapper.readValue(file.toFile(), new TypeReference<List<Double>>() {});
		} catch (Exception e) {
			return null;
		}
	}

	private void writeCachedEmbedding(String chunkHash, List<Double> vector) {
		if (vector == null || vector.isEmpty()) {
			return;
		}
		Path file = embeddingCacheFile(chunkHash);
		try {
			Files.createDirectories(file.getParent());
			objectMapper.writeValue(file.toFile(), vector);
		} catch (Exception ignored) {
			// keep recall flow non-blocking when cache persistence fails
		}
	}

	private Path embeddingCacheFile(String chunkHash) {
		String provider = ragEmbeddingProperties != null && ragEmbeddingProperties.getProvider() != null
			? ragEmbeddingProperties.getProvider().trim().toLowerCase(Locale.ROOT)
			: "openai";
		String model = resolveEmbeddingModel(provider);
		return memoryFileService.embeddingCacheDir()
			.resolve(provider)
			.resolve(sanitizeFileSegment(model))
			.resolve(chunkHash + ".json");
	}

	private String resolveEmbeddingModel(String provider) {
		if ("siliconflow".equals(provider)) {
			return siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : "default";
		}
		return openAiEmbeddingProperties != null ? openAiEmbeddingProperties.getModel() : "default";
	}

	private String sanitizeFileSegment(String value) {
		String text = value == null ? "default" : value.trim();
		if (text.isEmpty()) {
			return "default";
		}
		return text.replace('/', '_').replace('\\', '_').replace(':', '_');
	}

	private double lexicalScore(String query, String text) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
		if (q.isEmpty() || t.isEmpty()) {
			return 0.0;
		}
		String[] terms = q.split("\\s+");
		int hit = 0;
		for (String term : terms) {
			if (!term.isEmpty() && t.contains(term)) {
				hit++;
			}
		}
		return terms.length == 0 ? 0.0 : (double) hit / (double) terms.length;
	}

	private String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			return Integer.toHexString(text != null ? text.hashCode() : 0);
		}
	}

	private List<CandidateChunk> cloneCandidates(List<CandidateChunk> chunks) {
		List<CandidateChunk> out = new ArrayList<>();
		for (CandidateChunk chunk : chunks) {
			out.add(new CandidateChunk(
				chunk.path,
				chunk.kind,
				chunk.blockId,
				chunk.lineStart,
				chunk.lineEnd,
				chunk.text,
				chunk.embedding == null ? null : new ArrayList<>(chunk.embedding)
			));
		}
		return out;
	}

	private static class CachedDocument {
		private final String contentHash;
		private final List<CandidateChunk> chunks;

		private CachedDocument(String contentHash, List<CandidateChunk> chunks) {
			this.contentHash = contentHash;
			this.chunks = chunks;
		}
	}

	private static class ChunkSeed {
		private final String path;
		private final String kind;
		private final String blockId;
		private final int lineStart;
		private final int lineEnd;
		private final String text;
		private final String chunkHash;

		private ChunkSeed(
			String path,
			String kind,
			String blockId,
			int lineStart,
			int lineEnd,
			String text,
			String chunkHash
		) {
			this.path = path;
			this.kind = kind;
			this.blockId = blockId;
			this.lineStart = lineStart;
			this.lineEnd = lineEnd;
			this.text = text;
			this.chunkHash = chunkHash;
		}
	}

	private static class CandidateChunk {
		private final String path;
		private final String kind;
		private final String blockId;
		private final int lineStart;
		private final int lineEnd;
		private final String text;
		private final List<Double> embedding;
		private double score;

		private CandidateChunk(
			String path,
			String kind,
			String blockId,
			int lineStart,
			int lineEnd,
			String text,
			List<Double> embedding
		) {
			this.path = path;
			this.kind = kind;
			this.blockId = blockId;
			this.lineStart = lineStart;
			this.lineEnd = lineEnd;
			this.text = text;
			this.embedding = embedding;
		}
	}
}
