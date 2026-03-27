package com.agenticrag.app.benchmark.retrieval;

import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RetrievalTraceCollectorTest {
	@Test
	void recordsZeroHitStageSummary() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector("trace-1", "call-1", "search_knowledge_base", "kb-1", "hello");

		collector.recordStage(RetrievalTraceStage.DENSE, Collections.emptyList());

		Assertions.assertEquals(1, collector.getRecords().size());
		RetrievalTraceRecord record = collector.getRecords().get(0);
		Assertions.assertEquals(RetrievalTraceRecordType.STAGE_SUMMARY, record.getRecordType());
		Assertions.assertEquals(RetrievalTraceStage.DENSE, record.getStage());
		Assertions.assertEquals(0, record.getHitCount());
	}
}
