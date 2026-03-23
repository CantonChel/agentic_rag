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

	/**
	 * 构造记忆召回服务，注入配置、向量模型和消息存储。
	 */
	public MemoryRecallService(
		MemoryProperties properties,
		EmbeddingModel embeddingModel,
		PersistentMessageStore persistentMessageStore
	) {
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.persistentMessageStore = persistentMessageStore;
	}

	/**
	 * 对指定用户执行记忆检索，融合文件记忆与会话记忆并返回 TopK 片段。
	 */
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

		// 对查询文本做一次向量化，用于后续和每个候选块做余弦相似度。
		List<Double> queryEmbedding = embedOne(query.trim());
		// 逐个候选块计算融合分数（向量分 + 关键词分）。
		for (CandidateChunk candidate : candidates) {
			// 关键词命中分，刻画“字面相关性”。
			double lexical = lexicalScore(query, candidate.text);
			// 默认向量分为 0，只有向量齐全时才计算。
			double vector = 0.0;
			// 查询向量和候选向量都存在时，计算余弦相似度。
			if (queryEmbedding != null && candidate.embedding != null) {
				// 计算语义相似度分数。
				vector = CosineSimilarity.cosine(queryEmbedding, candidate.embedding);
			}
			// 融合打分：有查询向量时用 0.68*语义 + 0.32*字面，否则仅用字面分。
			double base = queryEmbedding != null ? (0.68 * vector + 0.32 * lexical) : lexical;
			candidate.score = base + candidate.sourceBoost;
		}

		// 决定最终返回条数：优先用请求参数，其次配置默认值。
		int topK = requestedTopK != null && requestedTopK > 0 ? requestedTopK : properties.getTopK();
		// 决定排序后的候选截断上限，避免后续流式处理过多数据。
		int topCandidates = properties.getTopKCandidates() > 0 ? properties.getTopKCandidates() : 20;
		// 用于去重，避免同来源同文本重复返回。
		Set<String> seen = new HashSet<>();
		// 按融合分从高到低排序后，按候选上限、去重和最终 topK 依次截断并输出 TextChunk。
		return candidates.stream()
			// 先按 score 降序排序，高分优先。
			.sorted(Comparator.comparingDouble((CandidateChunk c) -> c.score).reversed())
			// 第一层截断：只保留前 topCandidates 个高分候选。
			.limit(topCandidates)
			// 去重：source + text 作为唯一键，只保留第一次出现。
			.filter(c -> seen.add(c.source + "|" + c.text))
			// 第二层截断：返回给调用方的最终 TopK。
			.limit(topK > 0 ? topK : 5)
			// 转成统一对外结构 TextChunk。
			.map(this::toTextChunk)
			// 收集成列表返回。
			.collect(Collectors.toList());
	}

	/**
	 * 将内部候选块转换为统一的 TextChunk 输出结构。
	 */
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

	/**
	 * 读取并汇总用户可见的 Markdown 文件候选块。
	 */
	private List<CandidateChunk> loadFileCandidates(String userId) {
		List<Path> files = discoverMemoryFiles(userId);
		List<CandidateChunk> out = new ArrayList<>();
		for (Path file : files) {
			out.addAll(loadOrRefreshFileChunks(file));
		}
		return out;
	}

	/**
	 * 发现可参与检索的文件集合：全局 MEMORY.md + 用户私有 memory 目录。
	 */
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

	/**
	 * 按文件内容增量加载候选块；命中缓存时直接复用，未命中时重分块并重算向量。
	 */
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
			chunks.add(new CandidateChunk(i, rel + "#chunk-" + (i + 1), parts.get(i), vectorAt(vectors, i), 0.05));
		}
		cacheByFilePath.put(abs, new CachedDocument(hash, cloneCandidates(chunks)));
		return chunks;
	}

	/**
	 * 从当前用户的历史会话消息构建候选块，作为 episodic memory 检索来源。
	 */
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
					vectorAt(vectors, i),
					-0.05
				));
			}
		}
		return out;
	}

	/**
	 * 按段落优先、超长切片的策略拆分文本块，支持 overlap 保留上下文连续性。
	 */
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

	/**
	 * 对文本列表批量生成向量；发生异常或维度异常时返回空结果以降级。
	 */
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

	/**
	 * 对单条查询文本生成向量；失败时返回 null。
	 */
	private List<Double> embedOne(String text) {
		List<List<Double>> vectors = embedBatch(Collections.singletonList(text));
		if (vectors.isEmpty()) {
			return null;
		}
		return vectors.get(0);
	}

	/**
	 * 安全读取指定下标的向量，越界或空输入时返回 null。
	 */
	private List<Double> vectorAt(List<List<Double>> vectors, int idx) {
		if (vectors == null || idx < 0 || idx >= vectors.size()) {
			return null;
		}
		return vectors.get(idx);
	}

	/**
	 * 计算简单关键词命中分数，用于与向量相似度做融合排序。
	 */
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

	/**
	 * 获取记忆检索使用的工作区根目录。
	 */
	private Path workspaceRoot() {
		String configured = properties.getWorkspaceRoot();
		if (configured == null || configured.trim().isEmpty()) {
			return Paths.get("").toAbsolutePath().normalize();
		}
		return Paths.get(configured).toAbsolutePath().normalize();
	}

	/**
	 * 将绝对路径尽量转换为相对工作区路径，便于展示和去噪。
	 */
	private String relPath(Path path) {
		Path root = workspaceRoot();
		Path abs = path.toAbsolutePath().normalize();
		try {
			return root.relativize(abs).toString().replace('\\', '/');
		} catch (Exception ignored) {
			return abs.toString().replace('\\', '/');
		}
	}

	/**
	 * 计算文本 SHA-256，用于文件内容变更判断和缓存失效。
	 */
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

	/**
	 * 深拷贝候选块列表，避免缓存对象被外部修改。
	 */
	private List<CandidateChunk> cloneCandidates(List<CandidateChunk> chunks) {
		List<CandidateChunk> out = new ArrayList<>();
		for (CandidateChunk chunk : chunks) {
			out.add(new CandidateChunk(chunk.index, chunk.source, chunk.text, chunk.embedding, chunk.sourceBoost));
		}
		return out;
	}

	private static class CachedDocument {
		private final String contentHash;
		private final List<CandidateChunk> chunks;

		/**
		 * 保存单个文件的内容指纹和对应候选块缓存。
		 */
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
		private final double sourceBoost;
		private double score;

		/**
		 * 记忆候选块实体：包含来源、文本、向量与排序分数。
		 */
		private CandidateChunk(int index, String source, String text, List<Double> embedding, double sourceBoost) {
			this.index = index;
			this.source = source;
			this.text = text;
			this.embedding = embedding;
			this.sourceBoost = sourceBoost;
		}
	}
}
