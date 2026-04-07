package com.agenticrag.app.benchmark.mapping;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.build.BenchmarkBuildImportService;
import com.agenticrag.app.benchmark.build.BenchmarkBuildRepository;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BenchmarkBuildChunkMappingIntegrationTest {
	@Autowired
	private BenchmarkBuildImportService benchmarkBuildImportService;

	@Autowired
	private BenchmarkBuildChunkMappingService benchmarkBuildChunkMappingService;

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
	private BenchmarkBuildChunkMappingRepository benchmarkBuildChunkMappingRepository;

	@MockBean
	private EmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() {
		benchmarkBuildChunkMappingRepository.deleteAll();
		benchmarkBuildRepository.deleteAll();
		embeddingRepository.deleteAll();
		chunkRepository.deleteAll();
		knowledgeRepository.deleteAll();
		knowledgeBaseRepository.deleteAll();
		Mockito.when(embeddingModel.embedTexts(Mockito.anyList()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> texts = invocation.getArgument(0, List.class);
				return Collections.nCopies(texts.size(), Arrays.asList(0.1d, 0.2d));
			});
	}

	@Test
	void runtimeChunkCanMapToMultipleGoldBlocks() throws Exception {
		BenchmarkBuildEntity build = benchmarkBuildImportService.importPackage(createCompactPackageDir());

		List<BenchmarkBuildChunkMappingView> mappings = benchmarkBuildChunkMappingService.listMappings(build.getBuildId(), null);

		Assertions.assertEquals(1, mappings.size());
		Assertions.assertEquals(Set.of("block-1", "block-2"), Set.copyOf(mappings.get(0).getGoldBlockIds()));
		Assertions.assertTrue(mappings.get(0).getGoldBlockIds().contains(mappings.get(0).getPrimaryGoldBlockId()));
		Assertions.assertEquals("docs/guide/a.md", mappings.get(0).getDocPath());
	}

	@Test
	void oneGoldBlockCanMapToMultipleRuntimeChunks() throws Exception {
		BenchmarkBuildEntity build = benchmarkBuildImportService.importPackage(createLargeSingleBlockPackageDir());

		List<BenchmarkBuildChunkMappingView> mappings = benchmarkBuildChunkMappingService.listMappings(build.getBuildId(), null);

		Assertions.assertTrue(build.getRuntimeChunkCount() > 1);
		Assertions.assertTrue(mappings.size() > 1);
		Assertions.assertTrue(mappings.stream().allMatch(item -> item.getGoldBlockIds().contains("block-1")));
		Assertions.assertTrue(mappings.stream().allMatch(item -> "block-1".equals(item.getPrimaryGoldBlockId())));
		Assertions.assertTrue(
			mappings.stream().map(BenchmarkBuildChunkMappingView::getChunkId).collect(Collectors.toSet()).size() > 1
		);
	}

	private Path createCompactPackageDir() throws Exception {
		Path dir = Files.createTempDirectory("benchmark-chunk-mapping-compact");
		Files.writeString(
			dir.resolve("source_manifest.json"),
			"{\n"
				+ "  \"source_set_id\": \"api_docs-source-set\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"source_root\": \"/tmp/source-set\",\n"
				+ "  \"file_count\": 1,\n"
				+ "  \"files\": [{\"path\": \"docs/guide/a.md\", \"size_bytes\": 96, \"sha256\": \"sha-a\"}],\n"
				+ "  \"created_at\": \"2026-04-07T00:00:00Z\"\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("normalized_documents.jsonl"),
			"{\"doc_path\":\"docs/guide/a.md\",\"title\":\"Guide A\",\"normalized_text\":\"# Intro\\nFirst evidence\\n\\n## Limits\\nSecond evidence\",\"metadata\":{\"source_name\":\"a.md\"},\"blocks\":[{\"block_id\":\"block-1\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# Intro\\nFirst evidence\",\"start_line\":1,\"end_line\":2},{\"block_id\":\"block-2\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"content\":\"## Limits\\nSecond evidence\",\"start_line\":4,\"end_line\":5}]}\n"
		);
		Files.writeString(
			dir.resolve("authoring_blocks.jsonl"),
			"{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# Intro\\nFirst evidence\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"start_line\":1,\"end_line\":2}\n"
				+ "{\"block_id\":\"block-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"text\":\"## Limits\\nSecond evidence\",\"anchor\":\"docs/guide/a.md#limits\",\"source_hash\":\"hash-2\",\"start_line\":4,\"end_line\":5}\n"
		);
		Files.writeString(dir.resolve("block_links.jsonl"), "{\"from_block_id\":\"block-1\",\"to_block_id\":\"block-2\",\"link_type\":\"prev_next\"}\n");
		Files.writeString(
			dir.resolve("samples.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is first evidence?\",\"ground_truth\":\"First evidence\",\"ground_truth_contexts\":[\"# Intro\\nFirst evidence\"],\"gold_block_refs\":[{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"base_v1\"}\n"
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
				+ "  \"suite_version\": \"base_v1\",\n"
				+ "  \"created_at\": \"2026-04-07T00:00:00Z\",\n"
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

	private Path createLargeSingleBlockPackageDir() throws Exception {
		String longText = "# Intro\n" + String.join(" ", Collections.nCopies(1200, "benchmark"));
		Path dir = Files.createTempDirectory("benchmark-chunk-mapping-large");
		Files.writeString(
			dir.resolve("source_manifest.json"),
			"{\n"
				+ "  \"source_set_id\": \"api_docs-source-set\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"source_root\": \"/tmp/source-set\",\n"
				+ "  \"file_count\": 1,\n"
				+ "  \"files\": [{\"path\": \"docs/guide/large.md\", \"size_bytes\": " + longText.length() + ", \"sha256\": \"sha-large\"}],\n"
				+ "  \"created_at\": \"2026-04-07T00:00:00Z\"\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("normalized_documents.jsonl"),
			"{\"doc_path\":\"docs/guide/large.md\",\"title\":\"Large Guide\",\"normalized_text\":"
				+ jsonString(longText)
				+ ",\"metadata\":{\"source_name\":\"large.md\"},\"blocks\":[{\"block_id\":\"block-1\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"content\":"
				+ jsonString(longText)
				+ ",\"start_line\":1,\"end_line\":2}]}\n"
		);
		Files.writeString(
			dir.resolve("authoring_blocks.jsonl"),
			"{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/large.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"text\":"
				+ jsonString(longText)
				+ ",\"anchor\":\"docs/guide/large.md#intro\",\"source_hash\":\"hash-large\",\"start_line\":1,\"end_line\":2}\n"
		);
		Files.writeString(dir.resolve("block_links.jsonl"), "");
		Files.writeString(
			dir.resolve("samples.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is repeated in the guide?\",\"ground_truth\":\"benchmark\",\"ground_truth_contexts\":["
				+ jsonString(longText)
				+ "],\"gold_block_refs\":[{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/large.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"large_v1\"}\n"
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
				+ "  \"suite_version\": \"large_v1\",\n"
				+ "  \"created_at\": \"2026-04-07T00:00:00Z\",\n"
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

	private String jsonString(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}
}
