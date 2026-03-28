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
	public static final String EVIDENCE_UNITS_FILE = "evidence_units.jsonl";
	public static final String BENCHMARK_SUITE_FILE = "benchmark_suite.jsonl";
	public static final String SUITE_MANIFEST_FILE = "suite_manifest.json";
	public static final String REVIEW_MARKDOWN_FILE = "benchmark_suite.md";

	private final ObjectMapper objectMapper;

	public PortableBenchmarkPackageReader(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public PortableBenchmarkPackage readPackage(Path packageDir) {
		Path resolvedDir = resolveDirectory(packageDir);
		Path manifestPath = requireFile(resolvedDir, SUITE_MANIFEST_FILE);
		Path evidencePath = requireFile(resolvedDir, EVIDENCE_UNITS_FILE);
		Path samplePath = requireFile(resolvedDir, BENCHMARK_SUITE_FILE);
		requireFile(resolvedDir, REVIEW_MARKDOWN_FILE);

		try {
			PortableSuiteManifest manifest = parseManifest(manifestPath);
			List<PortableEvidenceUnit> evidenceUnits = parseEvidenceUnits(evidencePath);
			List<PortableBenchmarkSample> benchmarkSamples = parseBenchmarkSamples(samplePath);
			return new PortableBenchmarkPackage(resolvedDir, manifest, evidenceUnits, benchmarkSamples);
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

	private PortableSuiteManifest parseManifest(Path manifestPath) throws IOException {
		JsonNode root = objectMapper.readTree(Files.readString(manifestPath));
		return new PortableSuiteManifest(
			text(root, "package_version"),
			text(root, "project_key"),
			text(root, "suite_version"),
			text(root, "created_at"),
			text(root, "generator_version"),
			parseStringMap(root.path("files"))
		);
	}

	private List<PortableEvidenceUnit> parseEvidenceUnits(Path evidencePath) throws IOException {
		List<PortableEvidenceUnit> out = new ArrayList<>();
		for (String line : Files.readAllLines(evidencePath)) {
			if (line == null || line.trim().isEmpty()) {
				continue;
			}
			JsonNode node = objectMapper.readTree(line);
			out.add(new PortableEvidenceUnit(
				text(node, "evidence_id"),
				text(node, "doc_path"),
				text(node, "section_key"),
				text(node, "section_title"),
				text(node, "canonical_text"),
				text(node, "anchor"),
				text(node, "source_hash"),
				text(node, "extractor_version")
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
				parseEvidenceRefs(node.path("gold_evidence_refs")),
				parseStringList(node.path("tags")),
				text(node, "difficulty"),
				text(node, "suite_version")
			));
		}
		return out;
	}

	private List<PortableEvidenceReference> parseEvidenceRefs(JsonNode refsNode) {
		if (refsNode == null || !refsNode.isArray()) {
			return Collections.emptyList();
		}
		List<PortableEvidenceReference> out = new ArrayList<>();
		for (JsonNode node : refsNode) {
			out.add(new PortableEvidenceReference(
				text(node, "evidence_id"),
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
}
