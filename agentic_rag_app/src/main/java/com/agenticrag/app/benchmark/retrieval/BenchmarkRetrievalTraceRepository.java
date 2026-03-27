package com.agenticrag.app.benchmark.retrieval;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkRetrievalTraceRepository extends JpaRepository<BenchmarkRetrievalTraceEntity, Long> {
	long countByTraceId(String traceId);

	List<BenchmarkRetrievalTraceEntity> findByTraceIdOrderByCreatedAtAscToolCallIdAscStageAscRankAscIdAsc(String traceId);
}
