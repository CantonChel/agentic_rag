package com.agenticrag.app.benchmark.retrieval;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class BenchmarkRetrievalTraceServiceTest {
	@Test
	void resolvesBuildIdFromKnowledgeBaseWhenMissingOnRecord() {
		BenchmarkRetrievalTraceRepository repository = Mockito.mock(BenchmarkRetrievalTraceRepository.class);
		BenchmarkBuildService buildService = Mockito.mock(BenchmarkBuildService.class);
		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-1");
		Mockito.when(buildService.findBuildByKnowledgeBaseId("kb-1")).thenReturn(Optional.of(build));

		BenchmarkRetrievalTraceService service = new BenchmarkRetrievalTraceService(repository, buildService);
		service.persistRecords(List.of(
			RetrievalTraceRecord.stageSummary("trace-1", "call-1", "search_knowledge_base", "kb-1", null, "hello", RetrievalTraceStage.DENSE, 0)
		));

		ArgumentCaptor<List<BenchmarkRetrievalTraceEntity>> captor = ArgumentCaptor.forClass(List.class);
		Mockito.verify(repository).saveAll(captor.capture());
		Assertions.assertEquals(1, captor.getValue().size());
		Assertions.assertEquals("build-1", captor.getValue().get(0).getBuildId());
		Assertions.assertEquals(RetrievalTraceRecordType.STAGE_SUMMARY, captor.getValue().get(0).getRecordType());
	}
}
