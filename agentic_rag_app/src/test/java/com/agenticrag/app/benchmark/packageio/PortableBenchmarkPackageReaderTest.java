package com.agenticrag.app.benchmark.packageio;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMapper;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMaterialization;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.service.TextSanitizer;
import com.agenticrag.app.rag.splitter.RecursiveCharacterTextSplitter;
import com.agenticrag.app.rag.splitter.RecursiveCharacterTextSplitterProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PortableBenchmarkPackageReaderTest {
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void readsPortablePackageFromDirectory() throws Exception {
		Path packageDir = createPackageDir();
		PortableBenchmarkPackageReader reader = new PortableBenchmarkPackageReader(objectMapper);

		PortableBenchmarkPackage benchmarkPackage = reader.readPackage(packageDir);

		Assertions.assertEquals("api_docs", benchmarkPackage.getManifest().getProjectKey());
		Assertions.assertEquals("base_v1", benchmarkPackage.getManifest().getSuiteVersion());
		Assertions.assertEquals("api_docs-source-set", benchmarkPackage.getSourceManifest().getSourceSetId());
		Assertions.assertEquals(2, benchmarkPackage.getNormalizedDocuments().size());
		Assertions.assertEquals(3, benchmarkPackage.getAuthoringBlocks().size());
		Assertions.assertEquals(1, benchmarkPackage.getSampleGenerationTraces().size());
		Assertions.assertEquals(1, benchmarkPackage.getBenchmarkSamples().size());
		Assertions.assertEquals("sample-1", benchmarkPackage.getBenchmarkSamples().get(0).getSampleId());
		Assertions.assertEquals("block-1", benchmarkPackage.getBenchmarkSamples().get(0).getGoldBlockRefs().get(0).getBlockId());
	}

	@Test
	void mapsNormalizedDocumentsIntoRuntimeChunkAndEmbeddingShapes() throws Exception {
		Path packageDir = createPackageDir();
		PortableBenchmarkPackageReader reader = new PortableBenchmarkPackageReader(objectMapper);
		PortableBenchmarkPackage benchmarkPackage = reader.readPackage(packageDir);

		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-123");
		build.setKnowledgeBaseId("bm-build-123");
		build.setProjectKey("api_docs");
		build.setSuiteVersion("base_v1");
		build.setSourceSetId("api_docs-source-set");
		build.setGoldPackageVersion("v1");

		RecursiveCharacterTextSplitterProperties properties = new RecursiveCharacterTextSplitterProperties();
		properties.setChunkSize(1000);
		properties.setChunkOverlap(50);
		BenchmarkKnowledgeBaseMapper mapper = new BenchmarkKnowledgeBaseMapper(
			objectMapper,
			new TextSanitizer(),
			new RecursiveCharacterTextSplitter(text -> text != null ? text.length() : 0, properties)
		);
		BenchmarkKnowledgeBaseMaterialization materialization = mapper.materialize(build, benchmarkPackage);

		Assertions.assertEquals(2, materialization.getKnowledgeEntities().size());
		Assertions.assertEquals(2, materialization.getChunkEntities().size());
		Assertions.assertEquals(2, materialization.getIndexChunks().size());
		Assertions.assertEquals(2, materialization.getEmbeddingInputs().size());
		Assertions.assertTrue(materialization.getChunkEntities().get(0).getChunkId().endsWith(":0"));
		Assertions.assertNull(materialization.getChunkEntities().get(0).getNextChunkId());
		Assertions.assertEquals(Integer.valueOf(0), materialization.getChunkEntities().get(0).getStartAt());
		Assertions.assertTrue(materialization.getChunkEntities().get(0).getEndAt() > 0);
		Assertions.assertTrue(materialization.getChunkEntities().get(0).getContent().contains("First evidence"));
		Assertions.assertTrue(materialization.getChunkEntities().get(0).getContent().contains("Second evidence"));

		@SuppressWarnings("unchecked")
		Map<String, Object> metadata = objectMapper.readValue(materialization.getChunkEntities().get(0).getMetadataJson(), Map.class);
		Assertions.assertEquals("build-123", metadata.get("build_id"));
		Assertions.assertEquals("docs/guide/a.md", metadata.get("doc_path"));
		Assertions.assertEquals(0, metadata.get("chunk_index"));
		Assertions.assertEquals("docs/guide/a.md#chunk-0", metadata.get("source"));

		List<EmbeddingEntity> embeddings = materialization.buildEmbeddingEntities(
			"text-embedding-3-small",
			Arrays.asList(
				Arrays.asList(0.1d, 0.2d),
				Arrays.asList(0.3d, 0.4d)
			),
			Instant.parse("2026-03-27T00:00:00Z")
		);
		Assertions.assertEquals(2, embeddings.size());
		Assertions.assertEquals(materialization.getChunkEntities().get(0).getChunkId(), embeddings.get(0).getChunkId());
		Assertions.assertEquals("text-embedding-3-small", embeddings.get(0).getModelName());
		Assertions.assertEquals("[0.1,0.2]", embeddings.get(0).getVectorJson());
	}

	private Path createPackageDir() throws Exception {
		Path dir = Files.createTempDirectory("benchmark-package");
		Files.writeString(
			dir.resolve("source_manifest.json"),
			"{\n"
				+ "  \"source_set_id\": \"api_docs-source-set\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"source_root\": \"/tmp/source-set\",\n"
				+ "  \"file_count\": 2,\n"
				+ "  \"files\": [\n"
				+ "    {\"path\": \"docs/guide/a.md\", \"size_bytes\": 48, \"sha256\": \"sha-a\"},\n"
				+ "    {\"path\": \"docs/api/b.pdf\", \"size_bytes\": 23, \"sha256\": \"sha-b\"}\n"
				+ "  ],\n"
				+ "  \"created_at\": \"2026-03-27T00:00:00Z\"\n"
				+ "}\n"
		);
		Files.writeString(
			dir.resolve("normalized_documents.jsonl"),
			"{\"doc_path\":\"docs/guide/a.md\",\"title\":\"Guide A\",\"normalized_text\":\"# Intro\\nFirst evidence\\n\\n## Limits\\nSecond evidence\",\"metadata\":{\"source_name\":\"a.md\"},\"blocks\":[{\"block_id\":\"block-1\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# Intro\\nFirst evidence\",\"start_line\":1,\"end_line\":2},{\"block_id\":\"block-2\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"content\":\"## Limits\\nSecond evidence\",\"start_line\":4,\"end_line\":5}]}\n"
				+ "{\"doc_path\":\"docs/api/b.pdf\",\"title\":\"API B\",\"normalized_text\":\"# API\\nThird evidence\",\"metadata\":{\"source_name\":\"b.pdf\"},\"blocks\":[{\"block_id\":\"block-3\",\"section_key\":\"api\",\"section_title\":\"API\",\"block_type\":\"section\",\"heading_level\":1,\"content\":\"# API\\nThird evidence\",\"start_line\":1,\"end_line\":2}]}\n"
		);
		Files.writeString(
			dir.resolve("authoring_blocks.jsonl"),
			"{\"block_id\":\"block-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# Intro\\nFirst evidence\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"start_line\":1,\"end_line\":2}\n"
				+ "{\"block_id\":\"block-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"block_type\":\"section\",\"heading_level\":2,\"text\":\"## Limits\\nSecond evidence\",\"anchor\":\"docs/guide/a.md#limits\",\"source_hash\":\"hash-2\",\"start_line\":4,\"end_line\":5}\n"
				+ "{\"block_id\":\"block-3\",\"doc_path\":\"docs/api/b.pdf\",\"section_key\":\"api\",\"section_title\":\"API\",\"block_type\":\"section\",\"heading_level\":1,\"text\":\"# API\\nThird evidence\",\"anchor\":\"docs/api/b.pdf#api\",\"source_hash\":\"hash-3\",\"start_line\":1,\"end_line\":2}\n"
		);
		Files.writeString(
			dir.resolve("block_links.jsonl"),
			"{\"from_block_id\":\"block-1\",\"to_block_id\":\"block-2\",\"link_type\":\"prev_next\"}\n"
		);
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
}
