package com.agenticrag.app.benchmark.mapping;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkBuildChunkMappingRepository extends JpaRepository<BenchmarkBuildChunkMappingEntity, Long> {
	List<BenchmarkBuildChunkMappingEntity> findByBuildIdOrderByDocPathAscStartAtAscIdAsc(String buildId);

	List<BenchmarkBuildChunkMappingEntity> findByBuildIdAndChunkIdOrderByDocPathAscStartAtAscIdAsc(String buildId, String chunkId);

	long deleteByBuildId(String buildId);
}
