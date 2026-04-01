package com.agenticrag.app.memory;

import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.memory.index.MemoryIndexManager;
import com.agenticrag.app.memory.index.MemoryIndexProviderProfileResolver;
import com.agenticrag.app.memory.index.MemoryIndexScope;
import com.agenticrag.app.memory.index.MemoryIndexScopeService;
import com.agenticrag.app.memory.index.MemoryIndexSearchCandidate;
import com.agenticrag.app.memory.index.MemoryIndexSearchRepository;
import com.agenticrag.app.memory.index.MemoryIndexSearchService;
import com.agenticrag.app.memory.index.MemoryIndexMetaEntity;
import com.agenticrag.app.memory.index.MemoryIndexMetaId;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MemorySearchServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void searchUsesGlobalAndCurrentUserScopesOnly() {
		MemoryProperties properties = newProperties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexSearchRepository repository = Mockito.mock(MemoryIndexSearchRepository.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		MemoryIndexManager indexManager = Mockito.mock(MemoryIndexManager.class);
		MemoryIndexSearchService service = new MemoryIndexSearchService(
			properties,
			new CountingEmbeddingModel(),
			profileResolver(),
			repository,
			scopeService,
			metaRepository,
			indexManager
		);
		Mockito.when(metaRepository.findById(Mockito.any())).thenReturn(Optional.of(meta(false, "u1")));
		Mockito.when(repository.searchVector(Mockito.anyList(), Mockito.any(), Mockito.anyList(), Mockito.anyInt()))
			.thenReturn(List.of(new MemoryIndexSearchCandidate("MEMORY.md", "global", "g1", 1, 2, "global note", 0.9)));
		Mockito.when(repository.searchLexical(Mockito.anyList(), Mockito.eq("banana"), Mockito.anyInt()))
			.thenReturn(List.of(new MemoryIndexSearchCandidate("memory/users/u1/daily/2026-04-01.md", "daily_durable", "u1b", 3, 4, "banana project", 1.2)));

		List<MemorySearchHit> result = service.search("u1", "banana", 5);

		ArgumentCaptor<List<MemoryIndexScope>> scopes = ArgumentCaptor.forClass(List.class);
		Mockito.verify(repository).searchLexical(scopes.capture(), Mockito.eq("banana"), Mockito.anyInt());
		Assertions.assertEquals(2, scopes.getValue().size());
		Assertions.assertEquals("global", scopes.getValue().get(0).getTypeValue());
		Assertions.assertEquals("user", scopes.getValue().get(1).getTypeValue());
		Assertions.assertEquals("u1", scopes.getValue().get(1).getId());
		Assertions.assertFalse(result.isEmpty());
		Assertions.assertTrue(result.stream().anyMatch(hit -> hit.getPath().contains("memory/users/u1/")));
	}

	@Test
	void mergesVectorAndLexicalCandidatesIntoStableHits() {
		MemoryProperties properties = newProperties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexSearchRepository repository = Mockito.mock(MemoryIndexSearchRepository.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		MemoryIndexManager indexManager = Mockito.mock(MemoryIndexManager.class);
		MemoryIndexSearchService service = new MemoryIndexSearchService(
			properties,
			new CountingEmbeddingModel(),
			profileResolver(),
			repository,
			scopeService,
			metaRepository,
			indexManager
		);
		Mockito.when(metaRepository.findById(Mockito.any())).thenReturn(Optional.of(meta(false, "u1")));
		Mockito.when(repository.searchVector(Mockito.anyList(), Mockito.any(), Mockito.anyList(), Mockito.anyInt()))
			.thenReturn(List.of(
				new MemoryIndexSearchCandidate("memory/users/u1/daily/2026-04-01.md", "daily_durable", "b1", 10, 12, "只对接企业微信", 0.9)
			));
		Mockito.when(repository.searchLexical(Mockito.anyList(), Mockito.eq("企业微信"), Mockito.anyInt()))
			.thenReturn(List.of(
				new MemoryIndexSearchCandidate("memory/users/u1/daily/2026-04-01.md", "daily_durable", "b1", 10, 12, "只对接企业微信", 1.5),
				new MemoryIndexSearchCandidate("MEMORY.md", "global", "g1", 1, 2, "会议规范", 0.3)
			));

		List<MemorySearchHit> result = service.search("u1", "企业微信", 5);

		Assertions.assertEquals(2, result.size());
		Assertions.assertEquals("memory/users/u1/daily/2026-04-01.md", result.get(0).getPath());
		Assertions.assertTrue(result.get(0).getScore() > result.get(1).getScore());
		Assertions.assertEquals(10, result.get(0).getLineStart());
	}

	@Test
	void dirtyOrMissingScopeTriggersBackgroundSyncRequest() {
		MemoryProperties properties = newProperties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexSearchRepository repository = Mockito.mock(MemoryIndexSearchRepository.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		MemoryIndexManager indexManager = Mockito.mock(MemoryIndexManager.class);
		MemoryIndexSearchService service = new MemoryIndexSearchService(
			properties,
			new CountingEmbeddingModel(),
			profileResolver(),
			repository,
			scopeService,
			metaRepository,
			indexManager
		);
		Mockito.when(metaRepository.findById(new MemoryIndexMetaId("global", "__global__"))).thenReturn(Optional.empty());
		Mockito.when(metaRepository.findById(new MemoryIndexMetaId("user", "u1"))).thenReturn(Optional.of(meta(true, "u1")));
		Mockito.when(repository.searchVector(Mockito.anyList(), Mockito.any(), Mockito.anyList(), Mockito.anyInt())).thenReturn(Collections.emptyList());
		Mockito.when(repository.searchLexical(Mockito.anyList(), Mockito.eq("constraint"), Mockito.anyInt())).thenReturn(Collections.emptyList());

		service.search("u1", "constraint", 5);

		Mockito.verify(indexManager).requestGlobalScope();
		Mockito.verify(indexManager).requestUserScope("u1");
	}

	@Test
	void fallsBackToLexicalHitsWhenEmbeddingFails() {
		MemoryProperties properties = newProperties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexSearchRepository repository = Mockito.mock(MemoryIndexSearchRepository.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		MemoryIndexManager indexManager = Mockito.mock(MemoryIndexManager.class);
		MemoryIndexSearchService service = new MemoryIndexSearchService(
			properties,
			new FailingEmbeddingModel(),
			profileResolver(),
			repository,
			scopeService,
			metaRepository,
			indexManager
		);
		Mockito.when(metaRepository.findById(Mockito.any())).thenReturn(Optional.of(meta(false, "u1")));
		Mockito.when(repository.searchLexical(Mockito.anyList(), Mockito.eq("站内通知"), Mockito.anyInt()))
			.thenReturn(List.of(
				new MemoryIndexSearchCandidate("memory/users/u1/daily/2026-04-01.md", "daily_durable", "b2", 6, 7, "本地化环境先只保留站内通知", 0.8)
			));

		List<MemorySearchHit> result = service.search("u1", "站内通知", 5);

		Mockito.verify(repository, Mockito.never()).searchVector(Mockito.anyList(), Mockito.any(), Mockito.anyList(), Mockito.anyInt());
		Assertions.assertEquals(1, result.size());
		Assertions.assertEquals("memory/users/u1/daily/2026-04-01.md", result.get(0).getPath());
		Assertions.assertEquals(0.8, result.get(0).getScore(), 0.0001);
	}

	private MemoryProperties newProperties() {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(tempDir.toString());
		props.setUserMemoryBaseDir("memory/users");
		props.setTopK(5);
		props.setTopKCandidates(20);
		return props;
	}

	private MemoryIndexProviderProfileResolver profileResolver() {
		RagEmbeddingProperties ragProps = new RagEmbeddingProperties();
		ragProps.setProvider("openai");
		OpenAiEmbeddingProperties openAiProps = new OpenAiEmbeddingProperties();
		openAiProps.setModel("test-embedding");
		SiliconFlowEmbeddingProperties siliconProps = new SiliconFlowEmbeddingProperties();
		siliconProps.setModel("silicon-test");
		OpenAiClientProperties openAiClientProperties = new OpenAiClientProperties();
		openAiClientProperties.setApiKey("test-key");
		return new MemoryIndexProviderProfileResolver(ragProps, openAiProps, siliconProps, openAiClientProperties);
	}

	private MemoryIndexMetaEntity meta(boolean dirty, String scopeId) {
		MemoryIndexMetaEntity meta = new MemoryIndexMetaEntity();
		meta.setId(new MemoryIndexMetaId("user", scopeId));
		meta.setDirty(dirty);
		return meta;
	}

	private static class CountingEmbeddingModel implements EmbeddingModel {
		@Override
		public List<List<Double>> embedTexts(List<String> texts) {
			if (texts == null) {
				return Collections.emptyList();
			}
			List<List<Double>> out = new ArrayList<>();
			for (String text : texts) {
				String lower = text == null ? "" : text.toLowerCase();
				double banana = lower.contains("banana") ? 1.0 : 0.0;
				double wecom = lower.contains("企业微信") ? 1.0 : 0.0;
				out.add(List.of(banana, wecom, 0.5));
			}
			return out;
		}
	}

	private static class FailingEmbeddingModel implements EmbeddingModel {
		@Override
		public List<List<Double>> embedTexts(List<String> texts) {
			throw new IllegalStateException("embedding unavailable");
		}
	}
}
