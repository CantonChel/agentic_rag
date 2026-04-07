package com.agenticrag.app.benchmark.build;

import com.agenticrag.app.benchmark.materialize.BenchmarkEmbeddingInput;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMapper;
import com.agenticrag.app.benchmark.materialize.BenchmarkKnowledgeBaseMaterialization;
import com.agenticrag.app.benchmark.mapping.BenchmarkBuildChunkMappingService;
import com.agenticrag.app.benchmark.packageio.PortableBenchmarkPackage;
import com.agenticrag.app.benchmark.packageio.PortableBenchmarkPackageReader;
import com.agenticrag.app.ingest.entity.KnowledgeBaseEntity;
import com.agenticrag.app.ingest.repo.ChunkRepository;
import com.agenticrag.app.ingest.repo.EmbeddingRepository;
import com.agenticrag.app.ingest.repo.KnowledgeBaseRepository;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.service.KnowledgeCrudService;
import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.embedding.OpenAiEmbeddingProperties;
import com.agenticrag.app.rag.embedding.RagEmbeddingProperties;
import com.agenticrag.app.rag.embedding.SiliconFlowEmbeddingProperties;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.splitter.RecursiveCharacterTextSplitterProperties;
import com.agenticrag.app.rag.splitter.TextSplitter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BenchmarkBuildImportService {
	private final PortableBenchmarkPackageReader packageReader;
	private final BenchmarkBuildService benchmarkBuildService;
	private final BenchmarkKnowledgeBaseMapper knowledgeBaseMapper;
	private final BenchmarkBuildChunkMappingService benchmarkBuildChunkMappingService;
	private final KnowledgeBaseRepository knowledgeBaseRepository;
	private final KnowledgeRepository knowledgeRepository;
	private final ChunkRepository chunkRepository;
	private final EmbeddingRepository embeddingRepository;
	private final KnowledgeCrudService knowledgeCrudService;
	private final EmbeddingModel embeddingModel;
	private final RagEmbeddingProperties ragEmbeddingProperties;
	private final OpenAiEmbeddingProperties openAiEmbeddingProperties;
	private final SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties;
	private final RecursiveCharacterTextSplitterProperties splitterProperties;
	private final TextSplitter textSplitter;
	private final Environment environment;
	private final List<ChunkIndexer> chunkIndexers;

	public BenchmarkBuildImportService(
		PortableBenchmarkPackageReader packageReader,
		BenchmarkBuildService benchmarkBuildService,
		BenchmarkKnowledgeBaseMapper knowledgeBaseMapper,
		BenchmarkBuildChunkMappingService benchmarkBuildChunkMappingService,
		KnowledgeBaseRepository knowledgeBaseRepository,
		KnowledgeRepository knowledgeRepository,
		ChunkRepository chunkRepository,
		EmbeddingRepository embeddingRepository,
		KnowledgeCrudService knowledgeCrudService,
		EmbeddingModel embeddingModel,
		RagEmbeddingProperties ragEmbeddingProperties,
		OpenAiEmbeddingProperties openAiEmbeddingProperties,
		SiliconFlowEmbeddingProperties siliconFlowEmbeddingProperties,
		RecursiveCharacterTextSplitterProperties splitterProperties,
		TextSplitter textSplitter,
		Environment environment,
		List<ChunkIndexer> chunkIndexers
	) {
		this.packageReader = packageReader;
		this.benchmarkBuildService = benchmarkBuildService;
		this.knowledgeBaseMapper = knowledgeBaseMapper;
		this.benchmarkBuildChunkMappingService = benchmarkBuildChunkMappingService;
		this.knowledgeBaseRepository = knowledgeBaseRepository;
		this.knowledgeRepository = knowledgeRepository;
		this.chunkRepository = chunkRepository;
		this.embeddingRepository = embeddingRepository;
		this.knowledgeCrudService = knowledgeCrudService;
		this.embeddingModel = embeddingModel;
		this.ragEmbeddingProperties = ragEmbeddingProperties;
		this.openAiEmbeddingProperties = openAiEmbeddingProperties;
		this.siliconFlowEmbeddingProperties = siliconFlowEmbeddingProperties;
		this.splitterProperties = splitterProperties;
		this.textSplitter = textSplitter;
		this.environment = environment;
		this.chunkIndexers = chunkIndexers;
	}

	public BenchmarkBuildEntity importPackage(Path packageDir) {
		Path resolvedDir = packageDir != null ? packageDir.toAbsolutePath().normalize() : null;
		PortableBenchmarkPackage benchmarkPackage = packageReader.readPackage(resolvedDir);
		String embeddingModelName = resolveEmbeddingModelName();
		String sourceSnapshotId = computeSourceSnapshotId(resolvedDir);
		String runtimeConfigSnapshotId = computeRuntimeConfigSnapshotId(embeddingModelName);

		BenchmarkBuildEntity build = benchmarkBuildService.createPendingBuild(
			resolvedDir.toString(),
			benchmarkPackage.getManifest().getProjectKey(),
			benchmarkPackage.getManifest().getSuiteVersion(),
			sourceSnapshotId,
			runtimeConfigSnapshotId,
			embeddingModelName,
			benchmarkPackage.getSourceManifest().getSourceSetId(),
			benchmarkPackage.getManifest().getPackageVersion(),
			benchmarkPackage.getManifest().getGeneratorVersion(),
			benchmarkPackage.getNormalizedDocuments().size(),
			0,
			benchmarkPackage.getAuthoringBlocks().size(),
			benchmarkPackage.getAuthoringBlocks().size(),
			benchmarkPackage.getBenchmarkSamples().size()
		);

		try {
			build = benchmarkBuildService.markBuilding(build.getBuildId());
			createKnowledgeBase(build);

			BenchmarkKnowledgeBaseMaterialization materialization = knowledgeBaseMapper.materialize(build, benchmarkPackage);
			build = benchmarkBuildService.updateMaterializationStats(build.getBuildId(), materialization.getChunkEntities().size());
			knowledgeRepository.saveAll(materialization.getKnowledgeEntities());
			chunkRepository.saveAll(materialization.getChunkEntities());
			benchmarkBuildChunkMappingService.rebuildMappings(build, benchmarkPackage, materialization.getChunkEntities());

			List<List<Double>> vectors = embeddingModel.embedTexts(extractEmbeddingTexts(materialization.getEmbeddingInputs()));
			embeddingRepository.saveAll(materialization.buildEmbeddingEntities(embeddingModelName, vectors, Instant.now()));

			attachEmbeddings(materialization.getIndexChunks(), vectors);
			addToIndexers(materialization.getIndexChunks());
			return benchmarkBuildService.markReady(build.getBuildId());
		} catch (Exception e) {
			cleanupKnowledgeBase(build.getKnowledgeBaseId());
			benchmarkBuildChunkMappingService.deleteByBuildId(build.getBuildId());
			benchmarkBuildService.markFailed(build.getBuildId(), e.getMessage());
			throw e instanceof IllegalStateException ? (IllegalStateException) e : new IllegalStateException("failed to import benchmark package", e);
		}
	}

	private void createKnowledgeBase(BenchmarkBuildEntity build) {
		if (knowledgeBaseRepository.existsById(build.getKnowledgeBaseId())) {
			return;
		}
		KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
		knowledgeBase.setId(build.getKnowledgeBaseId());
		knowledgeBase.setName(build.getProjectKey() + "/" + build.getSuiteVersion() + "/" + build.getBuildId());
		knowledgeBase.setDescription("benchmark build import");
		knowledgeBase.setEnabled(true);
		knowledgeBase.setCreatedAt(Instant.now());
		knowledgeBase.setUpdatedAt(Instant.now());
		try {
			knowledgeBaseRepository.save(knowledgeBase);
		} catch (DataIntegrityViolationException ignored) {
			// Another writer already created this knowledge base id.
		}
	}

	private void cleanupKnowledgeBase(String knowledgeBaseId) {
		try {
			knowledgeCrudService.deleteKnowledgeBase(knowledgeBaseId);
		} catch (Exception ignored) {
			// Best-effort cleanup; the failed build record remains for diagnosis.
		}
	}

	private List<String> extractEmbeddingTexts(List<BenchmarkEmbeddingInput> embeddingInputs) {
		List<String> out = new ArrayList<>();
		for (BenchmarkEmbeddingInput input : embeddingInputs) {
			out.add(input != null ? input.getContent() : "");
		}
		return out;
	}

	private void attachEmbeddings(List<TextChunk> indexChunks, List<List<Double>> vectors) {
		if (indexChunks == null || vectors == null) {
			return;
		}
		for (int i = 0; i < indexChunks.size() && i < vectors.size(); i++) {
			TextChunk chunk = indexChunks.get(i);
			if (chunk != null) {
				chunk.setEmbedding(vectors.get(i));
			}
		}
	}

	private void addToIndexers(List<TextChunk> indexChunks) {
		if (indexChunks == null || indexChunks.isEmpty() || chunkIndexers == null) {
			return;
		}
		for (ChunkIndexer indexer : chunkIndexers) {
			if (indexer == null) {
				continue;
			}
			indexer.addChunks(indexChunks);
		}
	}

	private String resolveEmbeddingModelName() {
		String provider = ragEmbeddingProperties != null && ragEmbeddingProperties.getProvider() != null
			? ragEmbeddingProperties.getProvider().trim().toLowerCase(Locale.ROOT)
			: "openai";
		if ("siliconflow".equals(provider)) {
			return siliconFlowEmbeddingProperties != null ? siliconFlowEmbeddingProperties.getModel() : "siliconflow";
		}
		return openAiEmbeddingProperties != null ? openAiEmbeddingProperties.getModel() : "openai";
	}

	private String computeSourceSnapshotId(Path packageDir) {
		return sha256(readFile(packageDir.resolve(PortableBenchmarkPackageReader.SOURCE_MANIFEST_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.NORMALIZED_DOCUMENTS_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.AUTHORING_BLOCKS_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.BLOCK_LINKS_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.BENCHMARK_SAMPLES_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.SAMPLE_GENERATION_TRACE_FILE))
			+ "\n--\n"
			+ readFile(packageDir.resolve(PortableBenchmarkPackageReader.GOLD_PACKAGE_MANIFEST_FILE)));
	}

	private String computeRuntimeConfigSnapshotId(String embeddingModelName) {
		String splitterName = textSplitter != null ? textSplitter.getClass().getSimpleName() : "none";
		String chunkSize = splitterProperties != null ? String.valueOf(splitterProperties.getChunkSize()) : "0";
		String chunkOverlap = splitterProperties != null ? String.valueOf(splitterProperties.getChunkOverlap()) : "0";
		String retrieverPostgresEnabled = property("rag.retriever.postgres.enabled", "false");
		String vectorStorePostgresEnabled = property("rag.vector-store.postgres.enabled", "false");
		String indexerNames = chunkIndexers == null
			? ""
			: chunkIndexers.stream().filter(indexer -> indexer != null).map(indexer -> indexer.getClass().getSimpleName()).sorted().reduce((a, b) -> a + "," + b).orElse("");
		return sha256(
			"splitter=" + splitterName
				+ "\nchunk_size=" + chunkSize
				+ "\nchunk_overlap=" + chunkOverlap
				+ "\nembedding_model=" + embeddingModelName
				+ "\nretriever_postgres_enabled=" + retrieverPostgresEnabled
				+ "\nvector_store_postgres_enabled=" + vectorStorePostgresEnabled
				+ "\nindexers=" + indexerNames
		);
	}

	private String readFile(Path path) {
		try {
			return Files.readString(path);
		} catch (Exception e) {
			throw new IllegalArgumentException("failed to read package file: " + path, e);
		}
	}

	private String sha256(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder();
			for (byte b : bytes) {
				builder.append(String.format("%02x", b));
			}
			return builder.toString();
		} catch (Exception e) {
			throw new IllegalStateException("failed to hash package snapshot", e);
		}
	}

	private String property(String key, String defaultValue) {
		return environment != null ? environment.getProperty(key, defaultValue) : defaultValue;
	}
}
