package com.agenticrag.app.memory.index.repo;

import com.agenticrag.app.memory.index.MemoryIndexEmbeddingCacheEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryIndexEmbeddingCacheRepository extends JpaRepository<MemoryIndexEmbeddingCacheEntity, Long> {
	Optional<MemoryIndexEmbeddingCacheEntity> findByProviderAndModelAndProviderKeyFingerprintAndChunkHash(
		String provider,
		String model,
		String providerKeyFingerprint,
		String chunkHash
	);
}
