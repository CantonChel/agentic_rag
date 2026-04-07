package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexChunkEntity;
import com.agenticrag.app.memory.index.MemoryIndexEmbeddingCacheEntity;
import com.agenticrag.app.memory.index.MemoryIndexFileEntity;
import com.agenticrag.app.memory.index.MemoryIndexMetaEntity;
import com.agenticrag.app.memory.index.MemoryIndexMetaId;
import com.agenticrag.app.memory.index.repo.MemoryIndexChunkRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexEmbeddingCacheRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexFileRepository;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

@DataJpaTest
class MemoryIndexRepositoryTest {
	@Autowired
	private MemoryIndexMetaRepository metaRepository;

	@Autowired
	private MemoryIndexFileRepository fileRepository;

	@Autowired
	private MemoryIndexChunkRepository chunkRepository;

	@Autowired
	private MemoryIndexEmbeddingCacheRepository embeddingCacheRepository;

	@Test
	void persistsAndReadsMinimalMemoryIndexEntities() {
		Instant now = Instant.now();

		MemoryIndexMetaEntity meta = new MemoryIndexMetaEntity();
		meta.setId(new MemoryIndexMetaId("user", "anonymous"));
		meta.setIndexVersion(1);
		meta.setProvider("openai");
		meta.setModel("text-embedding-3-small");
		meta.setProviderKeyFingerprint("fp-1");
		meta.setSourcesJson("{\"root\":\"memory/users/anonymous\"}");
		meta.setScopeHash("scope-hash");
		meta.setChunkChars(800);
		meta.setChunkOverlap(120);
		meta.setVectorDims(1536);
		meta.setDirty(true);
		meta.setCreatedAt(now);
		meta.setUpdatedAt(now);
		metaRepository.save(meta);

		MemoryIndexFileEntity file = new MemoryIndexFileEntity();
		file.setScopeType("user");
		file.setScopeId("anonymous");
		file.setPath("memory/users/anonymous/facts/project.reminder.md");
		file.setKind("fact");
		file.setContentHash("file-hash");
		file.setFileMtime(now);
		file.setIndexedAt(now);
		file.setCreatedAt(now);
		file.setUpdatedAt(now);
		fileRepository.save(file);

		MemoryIndexChunkEntity chunk = new MemoryIndexChunkEntity();
		chunk.setScopeType("user");
		chunk.setScopeId("anonymous");
		chunk.setPath(file.getPath());
		chunk.setKind("fact");
		chunk.setBlockId("block-1");
		chunk.setLineStart(10);
		chunk.setLineEnd(16);
		chunk.setChunkHash("chunk-hash");
		chunk.setContent("只对接企业微信，不接飞书。");
		chunk.setIndexedAt(now);
		chunk.setCreatedAt(now);
		chunk.setUpdatedAt(now);
		chunkRepository.save(chunk);

		MemoryIndexEmbeddingCacheEntity embedding = new MemoryIndexEmbeddingCacheEntity();
		embedding.setProvider("openai");
		embedding.setModel("text-embedding-3-small");
		embedding.setProviderKeyFingerprint("fp-1");
		embedding.setChunkHash("chunk-hash");
		embedding.setDimension(3);
		embedding.setVectorJson("[0.1,0.2,0.3]");
		embedding.setContentHash("chunk-hash");
		embedding.setCreatedAt(now);
		embedding.setUpdatedAt(now);
		embeddingCacheRepository.save(embedding);

		Assertions.assertTrue(metaRepository.findById(new MemoryIndexMetaId("user", "anonymous")).isPresent());
		Assertions.assertEquals(1, fileRepository.findByScopeTypeAndScopeId("user", "anonymous").size());
		Assertions.assertEquals(1, chunkRepository.findByScopeTypeAndScopeId("user", "anonymous").size());
		Assertions.assertTrue(
			embeddingCacheRepository.findByProviderAndModelAndProviderKeyFingerprintAndChunkHash(
				"openai",
				"text-embedding-3-small",
				"fp-1",
				"chunk-hash"
			).isPresent()
		);
	}
}
