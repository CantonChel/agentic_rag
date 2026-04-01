package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.memory.MemorySearchHit;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexSearchService {
	private final MemoryProperties properties;
	private final EmbeddingModel embeddingModel;
	private final MemoryIndexProviderProfileResolver providerProfileResolver;
	private final MemoryIndexSearchRepository searchRepository;
	private final MemoryIndexScopeService scopeService;
	private final MemoryIndexMetaRepository metaRepository;
	private final MemoryIndexManager indexManager;

	public MemoryIndexSearchService(
		MemoryProperties properties,
		EmbeddingModel embeddingModel,
		MemoryIndexProviderProfileResolver providerProfileResolver,
		MemoryIndexSearchRepository searchRepository,
		MemoryIndexScopeService scopeService,
		MemoryIndexMetaRepository metaRepository,
		MemoryIndexManager indexManager
	) {
		this.properties = properties;
		this.embeddingModel = embeddingModel;
		this.providerProfileResolver = providerProfileResolver;
		this.searchRepository = searchRepository;
		this.scopeService = scopeService;
		this.metaRepository = metaRepository;
		this.indexManager = indexManager;
	}

	public List<MemorySearchHit> search(String userId, String query, Integer requestedTopK) {
		if (!properties.isEnabled()) {
			return new ArrayList<>();
		}
		String normalizedQuery = query == null ? "" : query.trim();
		if (normalizedQuery.isEmpty()) {
			return new ArrayList<>();
		}
		List<MemoryIndexScope> scopes = new ArrayList<>();
		scopes.add(scopeService.globalScope());
		scopes.add(scopeService.userScope(userId));
		ensureScopeSyncIfNeeded(scopes);

		int topK = requestedTopK != null && requestedTopK > 0 ? requestedTopK : properties.getTopK();
		int candidateLimit = properties.getTopKCandidates() > 0 ? properties.getTopKCandidates() : 20;
		MemoryIndexProviderProfile profile = providerProfileResolver.resolveCurrent();
		List<Double> queryEmbedding = embedOne(normalizedQuery);
		List<MemoryIndexSearchCandidate> vectorCandidates = queryEmbedding != null && !queryEmbedding.isEmpty()
			? searchRepository.searchVector(scopes, profile, queryEmbedding, candidateLimit)
			: new ArrayList<>();
		List<MemoryIndexSearchCandidate> lexicalCandidates = searchRepository.searchLexical(scopes, normalizedQuery, candidateLimit);

		Map<String, AggregatedCandidate> merged = new LinkedHashMap<>();
		for (MemoryIndexSearchCandidate candidate : vectorCandidates) {
			AggregatedCandidate aggregated = merged.computeIfAbsent(keyOf(candidate), ignored -> new AggregatedCandidate(candidate));
			aggregated.vectorScore = Math.max(aggregated.vectorScore, candidate.getScore());
		}
		for (MemoryIndexSearchCandidate candidate : lexicalCandidates) {
			AggregatedCandidate aggregated = merged.computeIfAbsent(keyOf(candidate), ignored -> new AggregatedCandidate(candidate));
			aggregated.lexicalScore = Math.max(aggregated.lexicalScore, candidate.getScore());
		}

		return merged.values().stream()
			.map(aggregated -> aggregated.toHit(queryEmbedding != null && !queryEmbedding.isEmpty()))
			.sorted(Comparator.comparingDouble(MemorySearchHit::getScore).reversed())
			.limit(topK > 0 ? topK : 5)
			.collect(Collectors.toList());
	}

	private void ensureScopeSyncIfNeeded(List<MemoryIndexScope> scopes) {
		for (MemoryIndexScope scope : scopes) {
			boolean needsSync = metaRepository.findById(new MemoryIndexMetaId(scope.getTypeValue(), scope.getId()))
				.map(meta -> meta.isDirty())
				.orElse(true);
			if (!needsSync) {
				continue;
			}
			if (scope.getType() == MemoryIndexScopeType.GLOBAL) {
				indexManager.requestGlobalScope();
			} else {
				indexManager.requestUserScope(scope.getId());
			}
		}
	}

	private List<Double> embedOne(String text) {
		try {
			List<List<Double>> embeddings = embeddingModel != null ? embeddingModel.embedTexts(List.of(text)) : new ArrayList<>();
			if (embeddings == null || embeddings.isEmpty() || embeddings.get(0) == null || embeddings.get(0).isEmpty()) {
				return null;
			}
			return embeddings.get(0);
		} catch (Exception e) {
			return null;
		}
	}

	private String keyOf(MemoryIndexSearchCandidate candidate) {
		return safe(candidate.getPath()) + "|" + safe(candidate.getBlockId()) + "|" + candidate.getLineStart() + "|" + candidate.getLineEnd();
	}

	private String safe(String value) {
		return value != null ? value : "";
	}

	private static class AggregatedCandidate {
		private final MemoryIndexSearchCandidate source;
		private double vectorScore;
		private double lexicalScore;

		private AggregatedCandidate(MemoryIndexSearchCandidate source) {
			this.source = source;
		}

		private MemorySearchHit toHit(boolean hasVector) {
			double score = hasVector ? (0.68 * vectorScore + 0.32 * lexicalScore) : lexicalScore;
			return new MemorySearchHit(
				source.getPath(),
				source.getKind(),
				source.getBlockId(),
				source.getLineStart(),
				source.getLineEnd(),
				score,
				source.getContent()
			);
		}
	}
}
