package com.agenticrag.app.benchmark.packageio;

import com.agenticrag.app.benchmark.build.BenchmarkBuildEntity;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMapper;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMaterialization;
import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.ingest.service.TextSanitizer;
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
		Assertions.assertEquals(3, benchmarkPackage.getEvidenceUnits().size());
		Assertions.assertEquals(1, benchmarkPackage.getBenchmarkSamples().size());
		Assertions.assertEquals("sample-1", benchmarkPackage.getBenchmarkSamples().get(0).getSampleId());
	}

	@Test
	void mapsEvidenceUnitsIntoKnowledgeChunkAndEmbeddingShapes() throws Exception {
		Path packageDir = createPackageDir();
		PortableBenchmarkPackageReader reader = new PortableBenchmarkPackageReader(objectMapper);
		PortableBenchmarkPackage benchmarkPackage = reader.readPackage(packageDir);

		BenchmarkBuildEntity build = new BenchmarkBuildEntity();
		build.setBuildId("build-123");
		build.setKnowledgeBaseId("bm-build-123");
		build.setProjectKey("api_docs");
		build.setSuiteVersion("base_v1");

		BenchmarkKnowledgeBaseMapper mapper = new BenchmarkKnowledgeBaseMapper(objectMapper, new TextSanitizer());
		BenchmarkKnowledgeBaseMaterialization materialization = mapper.materialize(build, benchmarkPackage);

		Assertions.assertEquals(2, materialization.getKnowledgeEntities().size());
		Assertions.assertEquals(3, materialization.getChunkEntities().size());
		Assertions.assertEquals(3, materialization.getIndexChunks().size());
		Assertions.assertEquals(3, materialization.getEmbeddingInputs().size());
		Assertions.assertEquals("evi-1", materialization.getChunkEntities().get(0).getChunkId());
		Assertions.assertEquals("evi-2", materialization.getChunkEntities().get(0).getNextChunkId());
		Assertions.assertNull(materialization.getChunkEntities().get(1).getNextChunkId());

		@SuppressWarnings("unchecked")
		Map<String, Object> metadata = objectMapper.readValue(materialization.getChunkEntities().get(0).getMetadataJson(), Map.class);
		Assertions.assertEquals("build-123", metadata.get("build_id"));
		Assertions.assertEquals("evi-1", metadata.get("evidence_id"));
		Assertions.assertEquals("docs/guide/a.md#Intro", metadata.get("source"));

		List<EmbeddingEntity> embeddings = materialization.buildEmbeddingEntities(
			"text-embedding-3-small",
			Arrays.asList(
				Arrays.asList(0.1d, 0.2d),
				Arrays.asList(0.3d, 0.4d),
				Arrays.asList(0.5d, 0.6d)
			),
			Instant.parse("2026-03-27T00:00:00Z")
		);
		Assertions.assertEquals(3, embeddings.size());
		Assertions.assertEquals("evi-1", embeddings.get(0).getChunkId());
		Assertions.assertEquals("text-embedding-3-small", embeddings.get(0).getModelName());
		Assertions.assertEquals("[0.1,0.2]", embeddings.get(0).getVectorJson());
	}

	private Path createPackageDir() throws Exception {
		Path dir = Files.createTempDirectory("benchmark-package");
		Files.writeString(
			dir.resolve("suite_manifest.json"),
			"{\n"
				+ "  \"package_version\": \"v1\",\n"
				+ "  \"project_key\": \"api_docs\",\n"
				+ "  \"suite_version\": \"base_v1\",\n"
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
			"{\"evidence_id\":\"evi-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\",\"section_title\":\"Intro\",\"canonical_text\":\"First evidence\",\"anchor\":\"docs/guide/a.md#intro\",\"source_hash\":\"hash-1\",\"extractor_version\":\"stage2_v1\"}\n"
				+ "{\"evidence_id\":\"evi-2\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"limits\",\"section_title\":\"Limits\",\"canonical_text\":\"Second evidence\",\"anchor\":\"docs/guide/a.md#limits\",\"source_hash\":\"hash-2\",\"extractor_version\":\"stage2_v1\"}\n"
				+ "{\"evidence_id\":\"evi-3\",\"doc_path\":\"docs/api/b.pdf\",\"section_key\":\"api\",\"section_title\":\"API\",\"canonical_text\":\"Third evidence\",\"anchor\":\"docs/api/b.pdf#api\",\"source_hash\":\"hash-3\",\"extractor_version\":\"stage2_v1\"}\n"
		);
		Files.writeString(
			dir.resolve("benchmark_suite.jsonl"),
			"{\"sample_id\":\"sample-1\",\"question\":\"What is first evidence?\",\"ground_truth\":\"First evidence\",\"ground_truth_contexts\":[\"First evidence\"],\"gold_evidence_refs\":[{\"evidence_id\":\"evi-1\",\"doc_path\":\"docs/guide/a.md\",\"section_key\":\"intro\"}],\"tags\":[\"smoke\"],\"difficulty\":\"easy\",\"suite_version\":\"base_v1\"}\n"
		);
		Files.writeString(dir.resolve("benchmark_suite.md"), "# review\n");
		return dir;
	}
}
