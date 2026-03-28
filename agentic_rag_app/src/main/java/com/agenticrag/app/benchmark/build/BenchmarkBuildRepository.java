package com.agenticrag.app.benchmark.build;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkBuildRepository extends JpaRepository<BenchmarkBuildEntity, String> {
	Optional<BenchmarkBuildEntity> findByKnowledgeBaseId(String knowledgeBaseId);

	List<BenchmarkBuildEntity> findByProjectKeyOrderByCreatedAtDesc(String projectKey);

	List<BenchmarkBuildEntity> findByStatusOrderByCreatedAtDesc(BenchmarkBuildStatus status);

	List<BenchmarkBuildEntity> findByProjectKeyAndStatusOrderByCreatedAtDesc(String projectKey, BenchmarkBuildStatus status);

	List<BenchmarkBuildEntity> findAllByOrderByCreatedAtDesc();
}
