package com.agenticrag.app.benchmark.build;

import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceEntity;
import com.agenticrag.app.benchmark.retrieval.BenchmarkRetrievalTraceRepository;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceRecordType;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.tool.ToolExecutionContext;
import com.agenticrag.app.tool.ToolResult;
import com.agenticrag.app.tool.impl.KnowledgeSearchTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkBuildImportServiceIntegrationTest {
	@Autowired
	private BenchmarkBuildImportService benchmarkBuildImportService;

	@Autowired
	private BenchmarkBuildRepository benchmarkBuildRepository;

	@Autowired
	private KnowledgeBaseRepository knowledgeBaseRepository;

	@Autowired
	private KnowledgeRepository knowledgeRepository;

	@Autowired
	private ChunkRepository chunkRepository;

	@Autowired
	private EmbeddingRepository embeddingRepository;

	@Autowired
	private KnowledgeSearchTool knowledgeSearchTool;

	@Autowired
	private BenchmarkRetrievalTraceRepository benchmarkRetrievalTraceRepository;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
		benchmarkRetrievalTraceRepository.deleteAll();
		benchmarkBuildRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();
	}

	@Test
	void importsSamePackageIntoTwoIndependentBuilds() throws Exception {
		Path packageDir = createPackageDir();
		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> texts = invocation.getArgument(0, List.class);
				return Arrays.asList(
					Arrays.asList(0.1d, 0.2d),
					Arrays.asList(0.3d, 0.4d),
					Arrays.asList(0.5d, 0.6d)
				).subList(0, texts.size());
			});

		BenchmarkBuildEntity first = benchmarkBuildImportService.importPackage(packageDir);
		BenchmarkBuildEntity second = benchmarkBuildImportService.importPackage(packageDir);

		Assertions.assertNotEquals(first.getBuildId(), second.getBuildId());
		Assertions.assertNotEquals(first.getKnowledgeBaseId(), second.getKnowledgeBaseId());
		Assertions.assertEquals(BenchmarkBuildStatus.READY, first.getStatus());
		Assertions.assertEquals(BenchmarkBuildStatus.READY, second.getStatus());
		Assertions.assertTrue(knowledgeBaseRepository.existsById(first.getKnowledgeBaseId()));
		Assertions.assertTrue(knowledgeBaseRepository.existsById(second.getKnowledgeBaseId()));
		Assertions.assertEquals(2, benchmarkBuildRepository.count());
		Assertions.assertEquals(2, knowledgeBaseRepository.count());
		Assertions.assertEquals(4, knowledgeRepository.count());
		Assertions.assertEquals(6, chunkRepository.count());
		Assertions.assertEquals(6, embeddingRepository.count());
	}

	@Test
	void marksBuildFailedAndCleansKnowledgeBaseOnEmbeddingFailure() throws Exception {
		Path packageDir = createPackageDir();
		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenThrow(new IllegalStateException("embedding failed"));

		IllegalStateException error = Assertions.assertThrows(
			IllegalStateException.class,
			() -> benchmarkBuildImportService.importPackage(packageDir)
		);

		Assertions.assertTrue(error.getMessage().contains("embedding failed") || error.getMessage().contains("failed to import"));
		List<BenchmarkBuildEntity> builds = benchmarkBuildRepository.findAllByOrderByCreatedAtDesc();
		Assertions.assertEquals(1, builds.size());
		BenchmarkBuildEntity failed = builds.get(0);
		Assertions.assertEquals(BenchmarkBuildStatus.FAILED, failed.getStatus());
		Assertions.assertFalse(knowledgeBaseRepository.existsById(failed.getKnowledgeBaseId()));
		Assertions.assertEquals(0, knowledgeRepository.countByKnowledgeBaseId(failed.getKnowledgeBaseId()));
		Assertions.assertEquals(0, chunkRepository.count());
		Assertions.assertEquals(0, embeddingRepository.count());
	}

	@Test
	void knowledgeSearchToolRespectsKnowledgeBaseIsolationAfterImport() throws Exception {
		Path firstPackage = createPackageDir("base_v1", "shared benchmark evidence from build one");
		Path secondPackage = createPackageDir("base_v2", "shared benchmark evidence from build two");
		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> texts = invocation.getArgument(0, List.class);
				return Collections.nCopies(texts.size(), Arrays.asList(0.1d, 0.2d));
			});

		BenchmarkBuildEntity first = benchmarkBuildImportService.importPackage(firstPackage);
		BenchmarkBuildEntity second = benchmarkBuildImportService.importPackage(secondPackage);

		ToolResult firstResult = knowledgeSearchTool.execute(
			new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("query", "shared benchmark evidence"),
			new ToolExecutionContext("req-1", "u1", "s1", "trace-1", first.getKnowledgeBaseId())
		).block();
		ToolResult secondResult = knowledgeSearchTool.execute(
			new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("query", "shared benchmark evidence"),
			new ToolExecutionContext("req-2", "u1", "s1", "trace-2", second.getKnowledgeBaseId())
		).block();

		Assertions.assertNotNull(firstResult);
		Assertions.assertNotNull(secondResult);
		Assertions.assertTrue(firstResult.isSuccess());
		Assertions.assertTrue(secondResult.isSuccess());
		Assertions.assertTrue(firstResult.getOutput().contains("build one"));
		Assertions.assertFalse(firstResult.getOutput().contains("build two"));
		Assertions.assertTrue(secondResult.getOutput().contains("build two"));
		Assertions.assertFalse(secondResult.getOutput().contains("build one"));
		Assertions.assertTrue(benchmarkRetrievalTraceRepository.countByTraceId("trace-1") > 0);
		Assertions.assertTrue(benchmarkRetrievalTraceRepository.countByTraceId("trace-2") > 0);
		List<BenchmarkRetrievalTraceEntity> firstTrace = benchmarkRetrievalTraceRepository
			.findByTraceIdOrderByCreatedAtAscToolCallIdAscStageAscRankAscIdAsc("trace-1");
		List<BenchmarkRetrievalTraceEntity> secondTrace = benchmarkRetrievalTraceRepository
			.findByTraceIdOrderByCreatedAtAscToolCallIdAscStageAscRankAscIdAsc("trace-2");
		Assertions.assertTrue(firstTrace.stream().anyMatch(entity ->
			entity.getRecordType() == RetrievalTraceRecordType.STAGE_SUMMARY
				&& entity.getStage() == RetrievalTraceStage.DENSE
		));
		Assertions.assertTrue(firstTrace.stream().anyMatch(entity ->
			entity.getRecordType() == RetrievalTraceRecordType.CHUNK
				&& entity.getStage() == RetrievalTraceStage.CONTEXT_OUTPUT
				&& first.getBuildId().equals(entity.getBuildId())
		));
		Assertions.assertTrue(secondTrace.stream().anyMatch(entity ->
			entity.getRecordType() == RetrievalTraceRecordType.CHUNK
				&& entity.getStage() == RetrievalTraceStage.CONTEXT_OUTPUT
				&& second.getBuildId().equals(entity.getBuildId())
		));
	}

	private Path createPackageDir() throws Exception {
		return createPackageDir("base_v1", "First evidence");
	}

	private Path createPackageDir(String suiteVersion, String firstEvidenceText) throws Exception {
		Path dir = Files.createTempDirectory("benchmark-import");
		Files.writeString(
			dir.resolve("suite_manifest.json"),
			"{\n"
				+ "  \"package_version\": \"v1\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"suite_version\": \"" + suiteVersion + "\",\n"
				+ "  \"created_at\": \"2026-03-27T00:00:00Z\",\n"
				+ "  \"generator_version\": \"stage2_v1\",\n"
				+ "  \"files\": {\n"
				+ "    \"evidence_units\": \"evidence_units.jsonl\",\n"
				+ "    \"benchmark_suite\": \"benchmark_suite.jsonl\",\n"
				+ "    \"suite_manifest\": \"suite_manifest.json\",\n"
				+ "    \"review_markdown\": \"benchmark_suite.md\"\n"
				+ "  }\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("evidence_units.jsonl"),
			"{\"evidence_id\":\"evi-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"canonical_text\":\"" + firstEvidenceText + "\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"extractor_version\":\"stage2_v1\"}\n"
				+ "{\"evidence_id\":\"evi-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"canonical_text\":\"Second evidence\",\"anchor\":\"docs/guide/a.md#limits\",\"source_hash\":\"hash-2\",\"extractor_version\":\"stage2_v1\"}\n"
				+ "{\"evidence_id\":\"evi-3\",\"doc_path\":\"docs/api/b.pdf\",\"section_key\":\"api\",\"section_title\":\"API\",\"canonical_text\":\"Third evidence\",\"anchor\":\"docs/api/b.pdf#api\",\"source_hash\":\"hash-3\",\"extractor_version\":\"stage2_v1\"}\n"
		);
		Files.writeString(
			dir.resolve("benchmark_suite.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is first evidence?\",\"ground_truth\":\"" + firstEvidenceText + "\",\"ground_truth_contexts\":[\"" + firstEvidenceText + "\"],\"gold_evidence_refs\":[{\"evidence_id\":\"evi-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"" + suiteVersion + "\"}\n"
		);
		Files.writeString(dir.resolve("benchmark_suite.md"), "# review\n");
		return dir;
	}
}
