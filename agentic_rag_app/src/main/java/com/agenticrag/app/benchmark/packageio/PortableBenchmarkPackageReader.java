package com.agenticrag.app.benchmark.packageio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PortableBenchmarkPackageReader {
	public static final String SOURCE_MANIFEST_FILE = "source_manifest.json";
	public static final String NORMALIZED_DOCUMENTS_FILE = "normalized_documents.jsonl";
	public static final String AUTHORING_BLOCKS_FILE = "authoring_blocks.jsonl";
	public static final String BLOCK_LINKS_FILE = "block_links.jsonl";
	public static final String BENCHMARK_SAMPLES_FILE = "samples.jsonl";
	public static final String SAMPLE_GENERATION_TRACE_FILE = "sample_generation_trace.jsonl";
	public static final String GOLD_PACKAGE_MANIFEST_FILE = "gold_package_manifest.json";
	public static final String REVIEW_MARKDOWN_FILE = "review.md";

	private final ObjectMapper objectMapper;

	public PortableBenchmarkPackageReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public PortableBenchmarkPackage readPackage(Path packageDir) {
		Path resolvedDir = resolveDirectory(packageDir);
		Path sourceManifestPath = requireFile(resolvedDir, SOURCE_MANIFEST_FILE);
		Path normalizedDocumentsPath = requireFile(resolvedDir, NORMALIZED_DOCUMENTS_FILE);
		Path authoringBlocksPath = requireFile(resolvedDir, AUTHORING_BLOCKS_FILE);
		Path blockLinksPath = requireFile(resolvedDir, BLOCK_LINKS_FILE);
		Path samplePath = requireFile(resolvedDir, BENCHMARK_SAMPLES_FILE);
		Path sampleGenerationTracePath = requireFile(resolvedDir, SAMPLE_GENERATION_TRACE_FILE);
		Path manifestPath = requireFile(resolvedDir, GOLD_PACKAGE_MANIFEST_FILE);
		requireFile(resolvedDir, REVIEW_MARKDOWN_FILE);

		try {
			PortableGoldPackageManifest manifest = parseManifest(manifestPath);
			PortableSourceManifest sourceManifest = parseSourceManifest(sourceManifestPath);
			List<PortableNormalizedDocument> normalizedDocuments = parseNormalizedDocuments(normalizedDocumentsPath);
			List<PortableAuthoringBlock> authoringBlocks = parseAuthoringBlocks(authoringBlocksPath);
			List<PortableBlockLink> blockLinks = parseBlockLinks(blockLinksPath);
			List<PortableBenchmarkSample> benchmarkSamples = parseBenchmarkSamples(samplePath);
			List<PortableSampleGenerationTrace> sampleGenerationTraces = parseSampleGenerationTraces(sampleGenerationTracePath);
			return new PortableBenchmarkPackage(
				resolvedDir,
				manifest,
				sourceManifest,
				normalizedDocuments,
				authoringBlocks,
				blockLinks,
				benchmarkSamples,
				sampleGenerationTraces
			);
		} catch (IOException e) {
			throw new IllegalArgumentException("failed to read package: " + resolvedDir + ": " + e.getMessage(), e);
		}
	}

	private Path resolveDirectory(Path packageDir) {
		if (packageDir == null) {
			throw new IllegalArgumentException("packageDir is required");
		}
		Path resolved = packageDir.toAbsolutePath().normalize();
		if (!Files.isDirectory(resolved)) {
			throw new IllegalArgumentException("packageDir must be an existing directory: " + resolved);
		}
		return resolved;
	}

	private Path requireFile(Path packageDir, String name) {
		Path path = packageDir.resolve(name);
		if (!Files.isRegularFile(path)) {
			throw new IllegalArgumentException("missing required package file: " + name);
		}
		return path;
	}

	private PortableGoldPackageManifest parseManifest(Path manifestPath) throws IOException {
		JsonNode root = objectMapper.readTree(Files.readString(manifestPath));
		return new PortableGoldPackageManifest(
			text(root, "package_version"),
			text(root, "project_key"),
			text(root, "suite_version"),
			text(root, "created_at"),
			text(root, "generator_version"),
			parseStringMap(root.path("files"))
		);
	}

	private PortableSourceManifest parseSourceManifest(Path sourceManifestPath) throws IOException {
		JsonNode root = objectMapper.readTree(Files.readString(sourceManifestPath));
		return new PortableSourceManifest(
			text(root, "source_set_id"),
			text(root, "project_key"),
			text(root, "source_root"),
			intValue(root, "file_count"),
			parseSourceFileRecords(root.path("files")),
			text(root, "created_at")
		);
	}

	private List<PortableNormalizedDocument> parseNormalizedDocuments(Path normalizedDocumentsPath) throws IOException {
		List<PortableNormalizedDocument> out = new ArrayList<>();
		for (String line : Files.readAllLines(normalizedDocumentsPath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableNormalizedDocument(
				text(node, "doc_path"),
				text(node, "title"),
				text(node, "normalized_text"),
				parseStringMap(node.path("metadata")),
				parseNormalizedBlocks(node.path("blocks"))
			));
		}
		return out;
	}

	private List<PortableAuthoringBlock> parseAuthoringBlocks(Path authoringBlocksPath) throws IOException {
		List<PortableAuthoringBlock> out = new ArrayList<>();
		for (String line : Files.readAllLines(authoringBlocksPath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableAuthoringBlock(
				text(node, "block_id"),
				text(node, "doc_path"),
				text(node, "section_key"),
				text(node, "section_title"),
				text(node, "block_type"),
				intValue(node, "heading_level"),
				text(node, "text"),
				text(node, "anchor"),
				text(node, "source_hash"),
				intValue(node, "start_line"),
				intValue(node, "end_line")
			));
		}
		return out;
	}

	private List<PortableBlockLink> parseBlockLinks(Path blockLinksPath) throws IOException {
		List<PortableBlockLink> out = new ArrayList<>();
		for (String line : Files.readAllLines(blockLinksPath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableBlockLink(
				text(node, "from_block_id"),
				text(node, "to_block_id"),
				text(node, "link_type")
			));
		}
		return out;
	}

	private List<PortableBenchmarkSample> parseBenchmarkSamples(Path samplePath) throws IOException {
		List<PortableBenchmarkSample> out = new ArrayList<>();
		for (String line : Files.readAllLines(samplePath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableBenchmarkSample(
				text(node, "sample_id"),
				text(node, "question"),
				text(node, "ground_truth"),
				parseStringList(node.path("ground_truth_contexts")),
				parseGoldBlockRefs(node.path("gold_block_refs")),
				parseStringList(node.path("tags")),
				text(node, "difficulty"),
				text(node, "suite_version")
			));
		}
		return out;
	}

	private List<PortableSampleGenerationTrace> parseSampleGenerationTraces(Path sampleGenerationTracePath) throws IOException {
		List<PortableSampleGenerationTrace> out = new ArrayList<>();
		for (String line : Files.readAllLines(sampleGenerationTracePath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableSampleGenerationTrace(
				text(node, "sample_id"),
				text(node, "generation_method"),
				parseStringList(node.path("input_block_ids")),
				text(node, "generator_version"),
				text(node, "model_or_rule_name"),
				text(node, "validation_status")
			));
		}
		return out;
	}

	private List<PortableNormalizedBlock> parseNormalizedBlocks(JsonNode blocksNode) {
		if (blocksNode == null || !blocksNode.isArray()) {
			return Collections.emptyList();
		}
		List<PortableNormalizedBlock> out = new ArrayList<>();
		for (JsonNode node : blocksNode) {
			out.add(new PortableNormalizedBlock(
				text(node, "block_id"),
				text(node, "section_key"),
				text(node, "section_title"),
				text(node, "block_type"),
				intValue(node, "heading_level"),
				text(node, "content"),
				intValue(node, "start_line"),
				intValue(node, "end_line")
			));
		}
		return out;
	}

	private List<PortableSourceFileRecord> parseSourceFileRecords(JsonNode filesNode) {
		if (filesNode == null || !filesNode.isArray()) {
			return Collections.emptyList();
		}
		List<PortableSourceFileRecord> out = new ArrayList<>();
		for (JsonNode node : filesNode) {
			out.add(new PortableSourceFileRecord(
				text(node, "path"),
				longValue(node, "size_bytes"),
				text(node, "sha256")
			));
		}
		return out;
	}

	private List<PortableGoldBlockReference> parseGoldBlockRefs(JsonNode refsNode) {
		if (refsNode == null || !refsNode.isArray()) {
			return Collections.emptyList();
		}
		List<PortableGoldBlockReference> out = new ArrayList<>();
		for (JsonNode node : refsNode) {
			out.add(new PortableGoldBlockReference(
				text(node, "block_id"),
				text(node, "doc_path"),
				text(node, "section_key")
			));
		}
		return out;
	}

	private List<String> parseStringList(JsonNode node) {
		if (node == null || !node.isArray()) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for (JsonNode item : node) {
			out.add(item != null && !item.isNull() ? item.asText("") : "");
		}
		return out;
	}

	private Map<String, String> parseStringMap(JsonNode node) {
		if (node == null || !node.isObject()) {
			return Collections.emptyMap();
		}
		Map<String, String> out = new LinkedHashMap<>();
		Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
		while (iterator.hasNext()) {
			Map.Entry<String, JsonNode> entry = iterator.next();
			out.put(entry.getKey(), entry.getValue() != null && !entry.getValue().isNull() ? entry.getValue().asText("") : "");
		}
		return out;
	}

	private String text(JsonNode node, String fieldName) {
		JsonNode value = node != null ? node.get(fieldName) : null;
		return value != null && !value.isNull() ? value.asText("") : "";
	}

	private int intValue(JsonNode node, String fieldName) {
		JsonNode value = node != null ? node.get(fieldName) : null;
		return value != null && value.isNumber() ? value.asInt() : 0;
	}

	private long longValue(JsonNode node, String fieldName) {
		JsonNode value = node != null ? node.get(fieldName) : null;
		return value != null && value.isNumber() ? value.asLong() : 0L;
	}
}
