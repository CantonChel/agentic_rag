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
import java.util.Comparator;
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
		Assertions.assertEquals(3, first.getAuthoringBlockCount());
		Assertions.assertEquals(2, first.getNormalizedDocumentCount());
		Assertions.assertEquals(2, first.getRuntimeChunkCount());
		Assertions.assertTrue(knowledgeBaseRepository.existsById(first.getKnowledgeBaseId()));
		Assertions.assertTrue(knowledgeBaseRepository.existsById(second.getKnowledgeBaseId()));
		Assertions.assertEquals(2, benchmarkBuildRepository.count());
		Assertions.assertEquals(2, knowledgeBaseRepository.count());
		Assertions.assertEquals(4, knowledgeRepository.count());
		Assertions.assertEquals(4, chunkRepository.count());
		Assertions.assertEquals(4, embeddingRepository.count());
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
	void runtimeBuildUsesNormalizedDocumentsAndFillsChunkOffsets() throws Exception {
		Path packageDir = createPackageDir();
		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> texts = invocation.getArgument(0, List.class);
				return Collections.nCopies(texts.size(), Arrays.asList(0.1d, 0.2d));
			});

		BenchmarkBuildEntity build = benchmarkBuildImportService.importPackage(packageDir);

		Assertions.assertEquals(3, build.getAuthoringBlockCount());
		Assertions.assertEquals(2, build.getNormalizedDocumentCount());
		Assertions.assertEquals(2, build.getRuntimeChunkCount());

		List<com.agenticrag.app.ingest.entity.ChunkEntity> chunks = chunkRepository.findAll();
		chunks.sort(Comparator.comparing(com.agenticrag.app.ingest.entity.ChunkEntity::getKnowledgeId)
			.thenComparing(com.agenticrag.app.ingest.entity.ChunkEntity::getChunkIndex));
		Assertions.assertEquals(2, chunks.size());
		Assertions.assertTrue(chunks.stream().allMatch(chunk -> chunk.getStartAt() != null && chunk.getEndAt() != null));
		Assertions.assertTrue(chunks.stream().allMatch(chunk -> chunk.getEndAt() > chunk.getStartAt()));
		Assertions.assertTrue(chunks.stream().anyMatch(chunk ->
			chunk.getContent().contains(firstSentence("First evidence")) && chunk.getContent().contains("Second evidence")
		));
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
			dir.resolve("source_manifest.json"),
			"{\n"
				+ "  \"source_set_id\": \"api_docs-source-set\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"source_root\": \"/tmp/source-set\",\n"
				+ "  \"file_count\": 2,\n"
				+ "  \"files\": [\n"
				+ "    {\"path\": \"docs/guide/a.md\", \"size_bytes\": 64, \"sha256\": \"sha-a\"},\n"
				+ "    {\"path\": \"docs/api/b.pdf\", \"size_bytes\": 23, \"sha256\": \"sha-b\"}\n"
				+ "  ],\n"
				+ "  \"created_at\": \"2026-03-27T00:00:00Z\"\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("normalized_documents.jsonl"),
			"{\"doc_path\":\"docs/guide/a.md\",\"title\":\"Guide A\",\"normalized_text\":\"# Intro\\n" + firstEvidenceText + "\\n\\n## Limits\\nSecond evidence\",\"metadata\":{\"source_name\":\"a.md\"},\"blocks\":[{\"block_id\":\"block-1\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# Intro\\n" + firstEvidenceText + "\",\"start_line\":1,\"end_line\":2},{\"block_id\":\"block-2\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"content\":\"## Limits\\nSecond evidence\",\"start_line\":4,\"end_line\":5}]}\n"
				+ "{\"doc_path\":\"docs/api/b.pdf\",\"title\":\"API B\",\"normalized_text\":\"# API\\nThird evidence\",\"metadata\":{\"source_name\":\"b.pdf\"},\"blocks\":[{\"block_id\":\"block-3\",\"section_key\":\"api\",\"section_title\":\"API\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# API\\nThird evidence\",\"start_line\":1,\"end_line\":2}]}\n"
		);
		Files.writeString(
			dir.resolve("authoring_blocks.jsonl"),
			"{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# Intro\\n" + firstEvidenceText + "\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"start_line\":1,\"end_line\":2}\n"
				+ "{\"block_id\":\"block-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"text\":\"## Limits\\nSecond evidence\",\"anchor\":\"docs/guide/a.md#limits\",\"source_hash\":\"hash-2\",\"start_line\":4,\"end_line\":5}\n"
				+ "{\"block_id\":\"block-3\",\"doc_path\":\"docs/api/b.pdf\",\"section_key\":\"api\",\"section_title\":\"API\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# API\\nThird evidence\",\"anchor\":\"docs/api/b.pdf#api\",\"source_hash\":\"hash-3\",\"start_line\":1,\"end_line\":2}\n"
		);
		Files.writeString(
			dir.resolve("block_links.jsonl"),
			"{\"from_block_id\":\"block-1\",\"to_block_id\":\"block-2\",\"link_type\":\"prev_next\"}\n"
		);
		Files.writeString(
			dir.resolve("samples.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is first evidence?\",\"ground_truth\":\"" + firstEvidenceText + "\",\"ground_truth_contexts\":[\"# Intro\\n" + firstEvidenceText + "\"],\"gold_block_refs\":[{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"" + suiteVersion + "\"}\n"
		);
		Files.writeString(
			dir.resolve("sample_generation_trace.jsonl"),
			"{\"sample_id\":\"sample-1\",\"generation_method\":\"rule_based\",\"input_block_ids\":[\"block-1\"],\"generator_version\":\"gold_stage1_v1\",\"model_or_rule_name\":\"BenchmarkAuthoringStrategy\",\"validation_status\":\"generated\"}\n"
		);
		Files.writeString(
			dir.resolve("gold_package_manifest.json"),
			"{\n"
				+ "  \"package_version\": \"v1\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"suite_version\": \"" + suiteVersion + "\",\n"
				+ "  \"created_at\": \"2026-03-27T00:00:00Z\",\n"
				+ "  \"generator_version\": \"gold_stage1_v1\",\n"
				+ "  \"files\": {\n"
				+ "    \"source_manifest\": \"source_manifest.json\",\n"
				+ "    \"normalized_documents\": \"normalized_documents.jsonl\",\n"
				+ "    \"authoring_blocks\": \"authoring_blocks.jsonl\",\n"
				+ "    \"block_links\": \"block_links.jsonl\",\n"
				+ "    \"samples\": \"samples.jsonl\",\n"
				+ "    \"sample_generation_trace\": \"sample_generation_trace.jsonl\",\n"
				+ "    \"gold_package_manifest\": \"gold_package_manifest.json\",\n"
				+ "    \"review_markdown\": \"review.md\"\n"
				+ "  }\n"
				+ "}\n"
		);
		Files.writeString(dir.resolve("review.md"), "# review\n");
		return dir;
	}

	private String firstSentence(String text) {
		return text;
	}
}
