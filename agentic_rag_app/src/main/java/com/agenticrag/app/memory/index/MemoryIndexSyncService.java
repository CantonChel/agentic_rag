package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.memory.index.repo.MemoryIndexChunkRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexEmbeddingCacheRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexFileRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryIndexSyncService {
	private static final Logger log = LoggerFactory.getLogger(MemoryIndexSyncService.class);

	private final MemoryProperties properties;
	private final MemoryFileService memoryFileService;
	private final MemoryIndexScopeService scopeService;
	private final MemoryIndexChunkingService chunkingService;
	private final MemoryIndexProviderProfileResolver providerProfileResolver;
	private final MemoryIndexMetaRepository metaRepository;
	private final MemoryIndexFileRepository fileRepository;
	private final MemoryIndexChunkRepository chunkRepository;
	private final MemoryIndexEmbeddingCacheRepository embeddingCacheRepository;
	private final EmbeddingModel embeddingModel;
	private final ObjectMapper objectMapper;

	public MemoryIndexSyncService(
		MemoryProperties properties,
		MemoryFileService memoryFileService,
		MemoryIndexScopeService scopeService,
		MemoryIndexChunkingService chunkingService,
		MemoryIndexProviderProfileResolver providerProfileResolver,
		MemoryIndexMetaRepository metaRepository,
		MemoryIndexFileRepository fileRepository,
		MemoryIndexChunkRepository chunkRepository,
		MemoryIndexEmbeddingCacheRepository embeddingCacheRepository,
		EmbeddingModel embeddingModel,
		ObjectMapper objectMapper
	) {
		this.properties = properties;
		this.memoryFileService = memoryFileService;
		this.scopeService = scopeService;
		this.chunkingService = chunkingService;
		this.providerProfileResolver = providerProfileResolver;
		this.metaRepository = metaRepository;
		this.fileRepository = fileRepository;
		this.chunkRepository = chunkRepository;
		this.embeddingCacheRepository = embeddingCacheRepository;
		this.embeddingModel = embeddingModel;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public void markDirty(MemoryIndexScope scope, String lastError) {
		if (scope == null || !properties.isEnabled()) {
			return;
		}
		MemoryIndexProviderProfile profile = providerProfileResolver.resolveCurrent();
		Instant now = Instant.now();
		MemoryIndexMetaEntity meta = metaRepository.findById(metaId(scope)).orElseGet(() -> buildBaseMeta(scope, profile, 0, now));
		meta.setDirty(true);
		if (lastError != null && !lastError.trim().isEmpty()) {
			meta.setLastError(lastError.trim());
		}
		meta.setUpdatedAt(now);
		metaRepository.save(meta);
	}

	public boolean needsFullReindex(MemoryIndexMetaEntity current, MemoryIndexMetaEntity desired) {
		if (current == null || current.getId() == null) {
			return true;
		}
		if (desired == null || desired.getId() == null) {
			return true;
		}
		return current.getIndexVersion() != desired.getIndexVersion()
			|| !same(current.getProvider(), desired.getProvider())
			|| !same(current.getModel(), desired.getModel())
			|| !same(current.getProviderKeyFingerprint(), desired.getProviderKeyFingerprint())
			|| !same(current.getSourcesJson(), desired.getSourcesJson())
			|| !same(current.getScopeHash(), desired.getScopeHash())
			|| current.getChunkChars() != desired.getChunkChars()
			|| current.getChunkOverlap() != desired.getChunkOverlap()
			|| current.getVectorDims() != desired.getVectorDims();
	}

	@Transactional
	public void runSync(MemoryIndexScope scope) {
		if (scope == null || !properties.isEnabled()) {
			return;
		}
		long startNs = System.nanoTime();
		Instant now = Instant.now();
		try {
			MemoryIndexProviderProfile profile = providerProfileResolver.resolveCurrent();
			MemoryIndexMetaEntity currentMeta = metaRepository.findById(metaId(scope)).orElse(null);
			MemoryIndexMetaEntity desiredMeta = buildBaseMeta(scope, profile, resolveExpectedVectorDims(currentMeta, profile), now);
			boolean fullReindex = needsFullReindex(currentMeta, desiredMeta);

			Map<String, FileSnapshot> discoveredFiles = discoverFiles(scope);
			Map<String, MemoryIndexFileEntity> existingFiles = indexedFiles(scope);
			if (fullReindex) {
				chunkRepository.deleteByScopeTypeAndScopeId(scope.getTypeValue(), scope.getId());
				fileRepository.deleteByScopeTypeAndScopeId(scope.getTypeValue(), scope.getId());
				existingFiles.clear();
			}

			deleteRemovedFiles(scope, discoveredFiles.keySet(), existingFiles);

			List<FileSnapshot> changedFiles = new ArrayList<>();
			for (FileSnapshot snapshot : discoveredFiles.values()) {
				MemoryIndexFileEntity existing = existingFiles.get(snapshot.path);
				if (existing != null && same(existing.getContentHash(), snapshot.contentHash)) {
					continue;
				}
				chunkRepository.deleteByScopeTypeAndScopeIdAndPath(scope.getTypeValue(), scope.getId(), snapshot.path);
				changedFiles.add(snapshot);
			}

			int vectorDims = desiredMeta.getVectorDims();
			for (FileSnapshot snapshot : changedFiles) {
				List<MemoryIndexChunkSeed> seeds = chunkingService.buildSeeds(scope, snapshot.absolutePath, snapshot.content);
				Map<String, List<Double>> embeddings = resolveEmbeddings(seeds, profile, now);
				Integer resolvedDims = saveChunks(scope, seeds, embeddings, now);
				if (resolvedDims != null && resolvedDims.intValue() > 0) {
					vectorDims = resolvedDims.intValue();
				}
				saveOrUpdateFile(scope, snapshot, existingFiles.get(snapshot.path), now);
			}

			MemoryIndexMetaEntity finalMeta = currentMeta != null ? currentMeta : desiredMeta;
			if (finalMeta.getId() == null) {
				finalMeta.setId(desiredMeta.getId());
			}
			finalMeta.setIndexVersion(desiredMeta.getIndexVersion());
			finalMeta.setProvider(desiredMeta.getProvider());
			finalMeta.setModel(desiredMeta.getModel());
			finalMeta.setProviderKeyFingerprint(desiredMeta.getProviderKeyFingerprint());
			finalMeta.setSourcesJson(desiredMeta.getSourcesJson());
			finalMeta.setScopeHash(desiredMeta.getScopeHash());
			finalMeta.setChunkChars(desiredMeta.getChunkChars());
			finalMeta.setChunkOverlap(desiredMeta.getChunkOverlap());
			finalMeta.setVectorDims(vectorDims);
			finalMeta.setDirty(false);
			finalMeta.setLastSyncAt(now);
			finalMeta.setLastError(null);
			finalMeta.setUpdatedAt(now);
			if (finalMeta.getCreatedAt() == null) {
				finalMeta.setCreatedAt(now);
			}
			metaRepository.save(finalMeta);

			log.info(
				"event=memory_index_sync scope={} fullReindex={} changedFiles={} totalFiles={} durationMs={}",
				scope.toKey(),
				fullReindex,
				changedFiles.size(),
				discoveredFiles.size(),
				(System.nanoTime() - startNs) / 1_000_000
			);
		} catch (Exception e) {
			log.warn("event=memory_index_sync_error scope={} type={} message={}", scope.toKey(), e.getClass().getSimpleName(), e.getMessage());
			throw new IllegalStateException("Failed to sync scope " + scope.toKey(), e);
		}
	}

	private Map<String, FileSnapshot> discoverFiles(MemoryIndexScope scope) {
		Map<String, FileSnapshot> out = new LinkedHashMap<>();
		for (Path file : scopeService.filesForScope(scope)) {
			if (file == null || !Files.exists(file) || !Files.isRegularFile(file)) {
				continue;
			}
			try {
				String content = Files.readString(file, StandardCharsets.UTF_8);
				String path = memoryFileService.relPath(file);
				FileTime modifiedTime = Files.getLastModifiedTime(file);
				String kind = scope.getType() == MemoryIndexScopeType.USER
					? memoryFileService.kindOf(scope.getId(), file)
					: memoryFileService.kindOf(MemoryIndexConstants.GLOBAL_SCOPE_ID, file);
				out.put(path, new FileSnapshot(file, path, kind, content, sha256(content), modifiedTime != null ? modifiedTime.toInstant() : null));
			} catch (IOException ignored) {
				// skip unreadable files during sync
			}
		}
		return out;
	}

	private Map<String, MemoryIndexFileEntity> indexedFiles(MemoryIndexScope scope) {
		Map<String, MemoryIndexFileEntity> out = new LinkedHashMap<>();
		for (MemoryIndexFileEntity entity : fileRepository.findByScopeTypeAndScopeId(scope.getTypeValue(), scope.getId())) {
			out.put(entity.getPath(), entity);
		}
		return out;
	}

	private void deleteRemovedFiles(MemoryIndexScope scope, Set<String> currentPaths, Map<String, MemoryIndexFileEntity> existingFiles) {
		Set<String> removed = new LinkedHashSet<>(existingFiles.keySet());
		removed.removeAll(currentPaths);
		for (String path : removed) {
			chunkRepository.deleteByScopeTypeAndScopeIdAndPath(scope.getTypeValue(), scope.getId(), path);
			fileRepository.deleteByScopeTypeAndScopeIdAndPath(scope.getTypeValue(), scope.getId(), path);
		}
	}

	private Map<String, List<Double>> resolveEmbeddings(
		List<MemoryIndexChunkSeed> seeds,
		MemoryIndexProviderProfile profile,
		Instant now
	) {
		Map<String, List<Double>> resolved = new LinkedHashMap<>();
		Map<String, MemoryIndexChunkSeed> missing = new LinkedHashMap<>();
		for (MemoryIndexChunkSeed seed : seeds) {
			if (resolved.containsKey(seed.getChunkHash())) {
				continue;
			}
			Optional<MemoryIndexEmbeddingCacheEntity> cached = embeddingCacheRepository.findByProviderAndModelAndProviderKeyFingerprintAndChunkHash(
				profile.getProvider(),
				profile.getModel(),
				profile.getProviderKeyFingerprint(),
				seed.getChunkHash()
			);
			if (cached.isPresent()) {
				resolved.put(seed.getChunkHash(), parseVectorJson(cached.get().getVectorJson()));
				continue;
			}
			missing.put(seed.getChunkHash(), seed);
		}
		if (missing.isEmpty()) {
			return resolved;
		}
		List<MemoryIndexChunkSeed> missingSeeds = new ArrayList<>(missing.values());
		List<String> texts = new ArrayList<>();
		for (MemoryIndexChunkSeed seed : missingSeeds) {
			texts.add(seed.getContent());
		}
		List<List<Double>> vectors = embeddingModel != null ? embeddingModel.embedTexts(texts) : Collections.emptyList();
		for (int i = 0; i < missingSeeds.size(); i++) {
			MemoryIndexChunkSeed seed = missingSeeds.get(i);
			List<Double> vector = vectors != null && i < vectors.size() ? sanitizeVector(vectors.get(i)) : Collections.emptyList();
			resolved.put(seed.getChunkHash(), vector);
			if (vector.isEmpty()) {
				continue;
			}
			MemoryIndexEmbeddingCacheEntity entity = embeddingCacheRepository
				.findByProviderAndModelAndProviderKeyFingerprintAndChunkHash(
					profile.getProvider(),
					profile.getModel(),
					profile.getProviderKeyFingerprint(),
					seed.getChunkHash()
				)
				.orElseGet(MemoryIndexEmbeddingCacheEntity::new);
			if (entity.getCreatedAt() == null) {
				entity.setCreatedAt(now);
			}
			entity.setProvider(profile.getProvider());
			entity.setModel(profile.getModel());
			entity.setProviderKeyFingerprint(profile.getProviderKeyFingerprint());
			entity.setChunkHash(seed.getChunkHash());
			entity.setContentHash(seed.getChunkHash());
			entity.setDimension(vector.size());
			entity.setVectorJson(toVectorJson(vector));
			entity.setUpdatedAt(now);
			embeddingCacheRepository.save(entity);
		}
		return resolved;
	}

	private Integer saveChunks(
		MemoryIndexScope scope,
		List<MemoryIndexChunkSeed> seeds,
		Map<String, List<Double>> embeddings,
		Instant now
	) {
		Integer vectorDims = null;
		for (MemoryIndexChunkSeed seed : seeds) {
			MemoryIndexChunkEntity entity = new MemoryIndexChunkEntity();
			entity.setScopeType(scope.getTypeValue());
			entity.setScopeId(scope.getId());
			entity.setPath(seed.getPath());
			entity.setKind(seed.getKind());
			entity.setBlockId(seed.getBlockId());
			entity.setLineStart(seed.getLineStart());
			entity.setLineEnd(seed.getLineEnd());
			entity.setChunkHash(seed.getChunkHash());
			entity.setContent(seed.getContent());
			entity.setIndexedAt(now);
			entity.setCreatedAt(now);
			entity.setUpdatedAt(now);
			chunkRepository.save(entity);
			List<Double> vector = embeddings.get(seed.getChunkHash());
			if (vectorDims == null && vector != null && !vector.isEmpty()) {
				vectorDims = vector.size();
			}
		}
		return vectorDims;
	}

	private void saveOrUpdateFile(MemoryIndexScope scope, FileSnapshot snapshot, MemoryIndexFileEntity existing, Instant now) {
		MemoryIndexFileEntity entity = existing != null ? existing : new MemoryIndexFileEntity();
		entity.setScopeType(scope.getTypeValue());
		entity.setScopeId(scope.getId());
		entity.setPath(snapshot.path);
		entity.setKind(snapshot.kind);
		entity.setContentHash(snapshot.contentHash);
		entity.setFileMtime(snapshot.fileMtime);
		entity.setIndexedAt(now);
		entity.setUpdatedAt(now);
		if (entity.getCreatedAt() == null) {
			entity.setCreatedAt(now);
		}
		fileRepository.save(entity);
	}

	private MemoryIndexMetaEntity buildBaseMeta(MemoryIndexScope scope, MemoryIndexProviderProfile profile, int vectorDims, Instant now) {
		MemoryIndexMetaEntity meta = new MemoryIndexMetaEntity();
		meta.setId(metaId(scope));
		meta.setIndexVersion(MemoryIndexConstants.INDEX_VERSION);
		meta.setProvider(profile.getProvider());
		meta.setModel(profile.getModel());
		meta.setProviderKeyFingerprint(profile.getProviderKeyFingerprint());
		meta.setSourcesJson(scopeService.sourcesJson(scope));
		meta.setScopeHash(scopeService.scopeHash(scope));
		meta.setChunkChars(properties.getMaxChunkChars() > 0 ? properties.getMaxChunkChars() : 800);
		meta.setChunkOverlap(Math.max(0, properties.getChunkOverlap()));
		meta.setVectorDims(Math.max(0, vectorDims));
		meta.setDirty(true);
		meta.setCreatedAt(now);
		meta.setUpdatedAt(now);
		return meta;
	}

	private int resolveExpectedVectorDims(MemoryIndexMetaEntity currentMeta, MemoryIndexProviderProfile profile) {
		if (currentMeta != null
			&& same(currentMeta.getProvider(), profile.getProvider())
			&& same(currentMeta.getModel(), profile.getModel())
			&& same(currentMeta.getProviderKeyFingerprint(), profile.getProviderKeyFingerprint())
			&& currentMeta.getVectorDims() > 0) {
			return currentMeta.getVectorDims();
		}
		Optional<MemoryIndexEmbeddingCacheEntity> cached = embeddingCacheRepository.findTopByProviderAndModelAndProviderKeyFingerprintOrderByUpdatedAtDesc(
			profile.getProvider(),
			profile.getModel(),
			profile.getProviderKeyFingerprint()
		);
		if (cached.isPresent() && cached.get().getDimension() > 0) {
			return cached.get().getDimension();
		}
		return Math.max(0, profile.getVectorDims());
	}

	private MemoryIndexMetaId metaId(MemoryIndexScope scope) {
		return new MemoryIndexMetaId(scope.getTypeValue(), scope.getId());
	}

	private String toVectorJson(List<Double> vector) {
		try {
			return objectMapper.writeValueAsString(vector);
		} catch (Exception e) {
			return "[]";
		}
	}

	private List<Double> parseVectorJson(String value) {
		if (value == null || value.trim().isEmpty()) {
			return Collections.emptyList();
		}
		try {
			List<?> raw = objectMapper.readValue(value, List.class);
			List<Double> out = new ArrayList<>();
			for (Object item : raw) {
				if (item instanceof Number) {
					out.add(((Number) item).doubleValue());
				} else if (item != null) {
					out.add(Double.valueOf(String.valueOf(item)));
				}
			}
			return out;
		} catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private List<Double> sanitizeVector(List<Double> vector) {
		if (vector == null || vector.isEmpty()) {
			return Collections.emptyList();
		}
		List<Double> out = new ArrayList<>(vector.size());
		for (Double value : vector) {
			out.add(value != null ? value : 0.0);
		}
		return out;
	}

	private boolean same(String left, String right) {
		if (left == null) {
			return right == null;
		}
		return left.equals(right);
	}

	private String sha256(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((raw != null ? raw : "").getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte value : bytes) {
				out.append(String.format("%02x", value));
			}
			return out.toString();
		} catch (Exception e) {
			return Integer.toHexString(raw != null ? raw.hashCode() : 0);
		}
	}

	private static class FileSnapshot {
		private final Path absolutePath;
		private final String path;
		private final String kind;
		private final String content;
		private final String contentHash;
		private final Instant fileMtime;

		private FileSnapshot(Path absolutePath, String path, String kind, String content, String contentHash, Instant fileMtime) {
			this.absolutePath = absolutePath;
			this.path = path;
			this.kind = kind;
			this.content = content;
			this.contentHash = contentHash;
			this.fileMtime = fileMtime;
		}
	}
}
