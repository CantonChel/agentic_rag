package com.agenticrag.app.memory;

import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.StoredMessageEntity;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.store.CosineSimilarity;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class MemoryRecallService {
	private final MemoryProperties properties;
	private final EmbeddingModel embeddingModel;
	private final PersistentMessageStore persistentMessageStore;
	private final Map<String, CachedDocument> cacheByFilePath = new java.util.concurrent.ConcurrentHashMap<>();

	public MemoryRecallService(
		MemoryProperties properties,
		EmbeddingModel embeddingModel,
		PersistentMessageStore persistentMessageStore
	) {
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.persistentMessageStore = persistentMessageStore;
	}

	public List<TextChunk> search(String userId, String query, Integer requestedTopK) {
		if (!properties.isEnabled()) {
			return new ArrayList<>();
		}
		if (query == null || query.trim().isEmpty()) {
			return new ArrayList<>();
		}
		String uid = SessionScope.normalizeUserId(userId);

		List<CandidateChunk> candidates = new ArrayList<>();
		candidates.addAll(loadFileCandidates(uid));
		candidates.addAll(loadTranscriptCandidates(uid));
		if (candidates.isEmpty()) {
			return new ArrayList<>();
		}

		List<Double> queryEmbedding = embedOne(query.trim());
		for (CandidateChunk candidate : candidates) {
			double lexical = lexicalScore(query, candidate.text);
			double vector = 0.0;
			if (queryEmbedding != null && candidate.embedding != null) {
				vector = CosineSimilarity.cosine(queryEmbedding, candidate.embedding);
			}
			candidate.score = queryEmbedding != null ? (0.68 * vector + 0.32 * lexical) : lexical;
		}

		int topK = requestedTopK != null && requestedTopK > 0 ? requestedTopK : properties.getTopK();
		int topCandidates = properties.getTopKCandidates() > 0 ? properties.getTopKCandidates() : 20;
		Set<String> seen = new HashSet<>();
		return candidates.stream()
			.sorted(Comparator.comparingDouble((CandidateChunk c) -> c.score).reversed())
			.limit(topCandidates)
			.filter(c -> seen.add(c.source + "|" + c.text))
			.limit(topK > 0 ? topK : 5)
			.map(this::toTextChunk)
			.collect(Collectors.toList());
	}

	private TextChunk toTextChunk(CandidateChunk candidate) {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("source", candidate.source);
		return new TextChunk(
			candidate.source + "#" + candidate.index,
			candidate.source,
			candidate.text,
			candidate.embedding,
			metadata
		);
	}

	private List<CandidateChunk> loadFileCandidates(String userId) {
		List<Path> files = discoverMemoryFiles(userId);
		List<CandidateChunk> out = new ArrayList<>();
		for (Path file : files) {
			out.addAll(loadOrRefreshFileChunks(file));
		}
		return out;
	}

	private List<Path> discoverMemoryFiles(String userId) {
		Path root = workspaceRoot();
		List<Path> files = new ArrayList<>();
		Path memoryFile = root.resolve("MEMORY.md");
		if (Files.exists(memoryFile) && Files.isRegularFile(memoryFile)) {
			files.add(memoryFile);
		}

		Path userMemoryDir = root.resolve(properties.getUserMemoryBaseDir()).resolve(userId);
		if (Files.exists(userMemoryDir) && Files.isDirectory(userMemoryDir)) {
			try (Stream<Path> walk = Files.walk(userMemoryDir)) {
				walk.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
					.sorted()
					.forEach(files::add);
			} catch (IOException ignored) {
				// ignore broken memory path
			}
		}
		return files;
	}

	private List<CandidateChunk> loadOrRefreshFileChunks(Path file) {
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

		List<String> parts = splitIntoChunks(content);
		List<List<Double>> vectors = embedBatch(parts);
		List<CandidateChunk> chunks = new ArrayList<>();
		String rel = relPath(file);
		for (int i = 0; i < parts.size(); i++) {
			chunks.add(new CandidateChunk(i, rel + "#chunk-" + (i + 1), parts.get(i), vectorAt(vectors, i)));
		}
		cacheByFilePath.put(abs, new CachedDocument(hash, cloneCandidates(chunks)));
		return chunks;
	}

	private List<CandidateChunk> loadTranscriptCandidates(String userId) {
		if (!properties.isIncludeTranscripts()) {
			return new ArrayList<>();
		}
		List<String> scopedSessionIds = persistentMessageStore.listSessionIds().stream()
			.filter(scoped -> userId.equals(SessionScope.userIdFromScopedSessionId(scoped)))
			.sorted()
			.collect(Collectors.toList());
		if (scopedSessionIds.isEmpty()) {
			return new ArrayList<>();
		}

		List<CandidateChunk> out = new ArrayList<>();
		for (String scopedSessionId : scopedSessionIds) {
			String rawSessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
			List<StoredMessageEntity> messages = persistentMessageStore.list(scopedSessionId);
			if (messages == null || messages.isEmpty()) {
				continue;
			}
			List<String> transcriptLines = new ArrayList<>();
			int max = properties.getTranscriptMaxMessagesPerSession() > 0 ? properties.getTranscriptMaxMessagesPerSession() : 30;
			int start = Math.max(0, messages.size() - max);
			for (int i = start; i < messages.size(); i++) {
				StoredMessageEntity message = messages.get(i);
				if (message == null || message.getType() == null || message.getContent() == null) {
					continue;
				}
				String type = message.getType().trim().toUpperCase();
				if (!"USER".equals(type) && !"ASSISTANT".equals(type) && !"THINKING".equals(type)) {
					continue;
				}
				String text = message.getContent().trim();
				if (text.isEmpty()) {
					continue;
				}
				transcriptLines.add(type + ": " + text);
			}
			if (transcriptLines.isEmpty()) {
				continue;
			}

			String transcript = String.join("\n", transcriptLines);
			List<String> chunks = splitIntoChunks(transcript);
			List<List<Double>> vectors = embedBatch(chunks);
			for (int i = 0; i < chunks.size(); i++) {
				out.add(new CandidateChunk(
					i,
					"session:" + rawSessionId + "#chunk-" + (i + 1),
					chunks.get(i),
					vectorAt(vectors, i)
				));
			}
		}
		return out;
	}

	private List<String> splitIntoChunks(String content) {
		int maxChars = properties.getMaxChunkChars() > 0 ? properties.getMaxChunkChars() : 800;
		int overlap = properties.getChunkOverlapChars() > 0 ? properties.getChunkOverlapChars() : 80;
		List<String> seeds = new ArrayList<>();
		for (String part : content.split("\\n\\s*\\n")) {
			String t = part != null ? part.trim() : "";
			if (!t.isEmpty()) {
				seeds.add(t);
			}
		}
		if (seeds.isEmpty()) {
			return Collections.singletonList(content.trim());
		}

		List<String> out = new ArrayList<>();
		for (String seed : seeds) {
			if (seed.length() <= maxChars) {
				out.add(seed);
				continue;
			}
			int start = 0;
			while (start < seed.length()) {
				int end = Math.min(seed.length(), start + maxChars);
				String chunk = seed.substring(start, end).trim();
				if (!chunk.isEmpty()) {
					out.add(chunk);
				}
				if (end >= seed.length()) {
					break;
				}
				start = Math.max(start + 1, end - overlap);
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
		} catch (Exception ignored) {
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

	private List<Double> vectorAt(List<List<Double>> vectors, int idx) {
		if (vectors == null || idx < 0 || idx >= vectors.size()) {
			return null;
		}
		return vectors.get(idx);
	}

	private double lexicalScore(String query, String text) {
		String q = query == null ? "" : query.trim().toLowerCase();
		String t = text == null ? "" : text.toLowerCase();
		if (q.isEmpty() || t.isEmpty()) {
			return 0.0;
		}
		String[] terms = q.split("\\s+");
		int hit = 0;
		for (String term : terms) {
			if (term == null || term.isEmpty()) {
				continue;
			}
			if (t.contains(term)) {
				hit++;
			}
		}
		if (terms.length == 0) {
			return 0.0;
		}
		return (double) hit / (double) terms.length;
	}

	private Path workspaceRoot() {
		String configured = properties.getWorkspaceRoot();
		if (configured == null || configured.trim().isEmpty()) {
			return Paths.get("").toAbsolutePath().normalize();
		}
		return Paths.get(configured).toAbsolutePath().normalize();
	}

	private String relPath(Path path) {
		Path root = workspaceRoot();
		Path abs = path.toAbsolutePath().normalize();
		try {
			return root.relativize(abs).toString().replace('\\', '/');
		} catch (Exception ignored) {
			return abs.toString().replace('\\', '/');
		}
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
		} catch (Exception ignored) {
			return String.valueOf(text.hashCode());
		}
	}

	private List<CandidateChunk> cloneCandidates(List<CandidateChunk> chunks) {
		List<CandidateChunk> out = new ArrayList<>();
		for (CandidateChunk chunk : chunks) {
			out.add(new CandidateChunk(chunk.index, chunk.source, chunk.text, chunk.embedding));
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

	private static class CandidateChunk {
		private final int index;
		private final String source;
		private final String text;
		private final List<Double> embedding;
		private double score;

		private CandidateChunk(int index, String source, String text, List<Double> embedding) {
			this.index = index;
			this.source = source;
			this.text = text;
			this.embedding = embedding;
		}
	}
}
