package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexManager;
import com.agenticrag.app.memory.index.MemoryIndexMetaEntity;
import com.agenticrag.app.memory.index.MemoryIndexMetaId;
import com.agenticrag.app.memory.index.MemoryIndexScope;
import com.agenticrag.app.memory.index.MemoryIndexScopeService;
import com.agenticrag.app.memory.index.MemoryIndexSyncService;
import com.agenticrag.app.memory.index.repo.MemoryIndexChunkRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexEmbeddingCacheRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexFileRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MemoryIndexSyncServiceTest {
	private static final Path WORKSPACE_ROOT = createWorkspaceRoot();

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("memory.workspace-root", () -> WORKSPACE_ROOT.toString());
		registry.add("memory.user-memory-base-dir", () -> "memory/users");
		registry.add("memory.index-startup-sync-enabled", () -> "false");
	}

	@Autowired
	private MemoryIndexSyncService syncService;

	@Autowired
	private MemoryIndexManager indexManager;

	@Autowired
	private MemoryIndexScopeService scopeService;

	@Autowired
	private MemoryIndexMetaRepository metaRepository;

	@Autowired
	private MemoryIndexFileRepository fileRepository;

	@Autowired
	private MemoryIndexChunkRepository chunkRepository;

	@Autowired
	private MemoryIndexEmbeddingCacheRepository embeddingCacheRepository;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() throws Exception {
		chunkRepository.deleteAll();
		fileRepository.deleteAll();
		metaRepository.deleteAll();
		embeddingCacheRepository.deleteAll();
		clearWorkspace();
		Mockito.reset(embeddingModel);
		Mockito.when(embeddingModel.embedTexts(ArgumentMatchers.anyList())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> texts = invocation.getArgument(0, List.class);
			List<List<Double>> vectors = new ArrayList<>();
			for (String text : texts) {
				double wecom = text != null && text.contains("企业微信") ? 1.0 : 0.0;
				double approval = text != null && text.contains("审批") ? 1.0 : 0.0;
				double release = text != null && text.contains("释放") ? 1.0 : 0.0;
				vectors.add(List.of(wecom, approval, release));
			}
			return vectors;
		});
	}

	@Test
	void needsFullReindexWhenComparedFieldsChange() {
		MemoryIndexMetaEntity current = meta("openai", "text-embedding-3-small", "fp-1", 800, 120, 1536, "{\"root\":\"a\"}", "scope-a");
		MemoryIndexMetaEntity desired = meta("openai", "text-embedding-3-small", "fp-1", 800, 120, 1536, "{\"root\":\"a\"}", "scope-a");

		Assertions.assertFalse(syncService.needsFullReindex(current, desired));

		desired.setChunkOverlap(64);
		Assertions.assertTrue(syncService.needsFullReindex(current, desired));
	}

	@Test
	void runSyncSkipsUnchangedFilesWithoutReembedding() throws Exception {
		Path file = writeUserFact("u1", "project.reminder.md", "只对接企业微信\n先不开审批\n");
		MemoryIndexScope scope = scopeService.userScope("u1");

		syncService.runSync(scope);
		Mockito.verify(embeddingModel, Mockito.times(1)).embedTexts(ArgumentMatchers.anyList());
		int firstChunkCount = chunkRepository.findByScopeTypeAndScopeId("user", "u1").size();
		Assertions.assertTrue(firstChunkCount > 0);
		Assertions.assertTrue(Files.exists(file));

		Mockito.reset(embeddingModel);
		syncService.markDirty(scope, null);
		syncService.runSync(scope);

		Mockito.verifyNoInteractions(embeddingModel);
		Assertions.assertEquals(firstChunkCount, chunkRepository.findByScopeTypeAndScopeId("user", "u1").size());
		Assertions.assertFalse(metaRepository.findById(new MemoryIndexMetaId("user", "u1")).orElseThrow().isDirty());
	}

	@Test
	void fullReindexReusesEmbeddingCache() throws Exception {
		writeUserFact("u2", "project.constraint.md", "先开启自动释放会议室\n权限按空间细分\n");
		MemoryIndexScope scope = scopeService.userScope("u2");

		syncService.runSync(scope);
		Assertions.assertEquals(1, embeddingCacheRepository.count());

		MemoryIndexMetaEntity meta = metaRepository.findById(new MemoryIndexMetaId("user", "u2")).orElseThrow();
		meta.setChunkOverlap(meta.getChunkOverlap() + 1);
		metaRepository.save(meta);

		Mockito.reset(embeddingModel);
		syncService.runSync(scope);

		Mockito.verifyNoInteractions(embeddingModel);
		Assertions.assertEquals(1, embeddingCacheRepository.count());
		Assertions.assertFalse(metaRepository.findById(new MemoryIndexMetaId("user", "u2")).orElseThrow().isDirty());
	}

	@Test
	void markAllKnownScopesDirtyKeepsExistingChunksAvailable() throws Exception {
		writeUserFact("u3", "project.policy.md", "本地化环境下只保留站内通知\n");
		MemoryIndexScope scope = scopeService.userScope("u3");
		syncService.runSync(scope);
		int chunkCount = chunkRepository.findByScopeTypeAndScopeId("user", "u3").size();

		indexManager.markAllKnownScopesDirty();

		Assertions.assertTrue(metaRepository.findById(new MemoryIndexMetaId("user", "u3")).orElseThrow().isDirty());
		Assertions.assertEquals(chunkCount, chunkRepository.findByScopeTypeAndScopeId("user", "u3").size());
	}

	@Test
	void deletesRemovedFilesFromIndexOnNextSync() throws Exception {
		Path file = writeUserFact("u4", "project.reminder.md", "先只保留站内通知\n");
		MemoryIndexScope scope = scopeService.userScope("u4");

		syncService.runSync(scope);
		Assertions.assertFalse(chunkRepository.findByScopeTypeAndScopeId("user", "u4").isEmpty());
		Assertions.assertFalse(fileRepository.findByScopeTypeAndScopeId("user", "u4").isEmpty());

		Files.deleteIfExists(file);
		syncService.markDirty(scope, null);
		syncService.runSync(scope);

		Assertions.assertTrue(chunkRepository.findByScopeTypeAndScopeId("user", "u4").isEmpty());
		Assertions.assertTrue(fileRepository.findByScopeTypeAndScopeId("user", "u4").isEmpty());
	}

	@Test
	void indexesSessionSummaryFilesIntoSearchableScope() throws Exception {
		writeUserSummary("u5", "session-1.md", "这是会话摘要\n后续要按空间细分权限\n");
		MemoryIndexScope scope = scopeService.userScope("u5");

		syncService.runSync(scope);

		Assertions.assertTrue(
			chunkRepository.findByScopeTypeAndScopeId("user", "u5").stream()
				.anyMatch(chunk -> "session_summary".equals(chunk.getKind()) && chunk.getPath().contains("/summaries/"))
		);
	}

	@Test
	void rerunsSyncForRewrittenFactFileWithoutDuplicateChunkFailure() throws Exception {
		Path file = writeUserFact(
			"u6",
			"project.reminder.md",
			factBlock("block-1", "project.reminder", "fact-1", "只对接企业微信\n先不开审批")
		);
		MemoryIndexScope scope = scopeService.userScope("u6");

		syncService.runSync(scope);
		Assertions.assertEquals(1, chunkRepository.findByScopeTypeAndScopeId("user", "u6").size());

		Files.writeString(
			file,
			factBlock("block-1", "project.reminder", "fact-1", "只对接企业微信\n先不开审批")
				+ factBlock("block-2", "project.decision", "fact-2", "项目代号：青岚-42\n试点范围：华东二区"),
			StandardCharsets.UTF_8
		);

		syncService.markDirty(scope, null);
		Assertions.assertDoesNotThrow(() -> syncService.runSync(scope));
		Assertions.assertEquals(2, chunkRepository.findByScopeTypeAndScopeId("user", "u6").size());
		Assertions.assertTrue(
			chunkRepository.findByScopeTypeAndScopeId("user", "u6").stream()
				.anyMatch(chunk -> chunk.getContent().contains("青岚-42"))
		);
	}

	private MemoryIndexMetaEntity meta(
		String provider,
		String model,
		String fingerprint,
		int chunkChars,
		int chunkOverlap,
		int vectorDims,
		String sourcesJson,
		String scopeHash
	) {
		MemoryIndexMetaEntity meta = new MemoryIndexMetaEntity();
		meta.setId(new MemoryIndexMetaId("user", "u-meta"));
		meta.setIndexVersion(1);
		meta.setProvider(provider);
		meta.setModel(model);
		meta.setProviderKeyFingerprint(fingerprint);
		meta.setChunkChars(chunkChars);
		meta.setChunkOverlap(chunkOverlap);
		meta.setVectorDims(vectorDims);
		meta.setSourcesJson(sourcesJson);
		meta.setScopeHash(scopeHash);
		return meta;
	}

	private Path writeUserFact(String userId, String fileName, String content) throws IOException {
		Path file = WORKSPACE_ROOT.resolve("memory/users/" + userId + "/facts/" + fileName);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private Path writeUserSummary(String userId, String fileName, String content) throws IOException {
		Path file = WORKSPACE_ROOT.resolve("memory/users/" + userId + "/summaries/" + fileName);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private String factBlock(String blockId, String bucket, String factKey, String body) {
		return "<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\""
			+ blockId
			+ "\",\"user_id\":\"anonymous\",\"session_id\":\"s1\",\"created_at\":\"2026-04-06T00:00:00Z\",\"updated_at\":\"2026-04-06T00:00:00Z\",\"trigger\":\"test\",\"bucket\":\""
			+ bucket
			+ "\",\"fact_key\":\""
			+ factKey
			+ "\"} -->\n"
			+ body
			+ "\n<!-- /MEMORY_BLOCK -->\n\n";
	}

	private void clearWorkspace() throws IOException {
		if (!Files.exists(WORKSPACE_ROOT)) {
			Files.createDirectories(WORKSPACE_ROOT);
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(WORKSPACE_ROOT)) {
			walk.sorted(Comparator.reverseOrder())
				.filter(path -> !WORKSPACE_ROOT.equals(path))
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException ignored) {
					}
				});
		}
		Files.createDirectories(WORKSPACE_ROOT);
	}

	private static Path createWorkspaceRoot() {
		try {
			return Files.createTempDirectory("memory-index-sync-test");
		} catch (IOException e) {
			throw new IllegalStateException("Failed to create temp workspace", e);
		}
	}
}
