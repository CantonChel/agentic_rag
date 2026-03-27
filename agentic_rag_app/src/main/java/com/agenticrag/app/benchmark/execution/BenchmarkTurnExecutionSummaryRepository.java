package com.agenticrag.app.benchmark.execution;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenchmarkTurnExecutionSummaryRepository extends JpaRepository<BenchmarkTurnExecutionSummaryEntity, Long> {
	Optional<BenchmarkTurnExecutionSummaryEntity> findByTurnId(String turnId);

	List<BenchmarkTurnExecutionSummaryEntity> findAllByOrderByCreatedAtDesc();
}
