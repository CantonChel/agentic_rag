package com.agenticrag.app.benchmark.packageio;

import java.nio.file.Path;
import java.util.List;

public class PortableBenchmarkPackage {
	private final Path packageDir;
	private final PortableGoldPackageManifest manifest;
	private final PortableSourceManifest sourceManifest;
	private final List<PortableNormalizedDocument> normalizedDocuments;
	private final List<PortableAuthoringBlock> authoringBlocks;
	private final List<PortableBlockLink> blockLinks;
	private final List<PortableBenchmarkSample> benchmarkSamples;
	private final List<PortableSampleGenerationTrace> sampleGenerationTraces;

	public PortableBenchmarkPackage(
		Path packageDir,
		PortableGoldPackageManifest manifest,
		PortableSourceManifest sourceManifest,
		List<PortableNormalizedDocument> normalizedDocuments,
		List<PortableAuthoringBlock> authoringBlocks,
		List<PortableBlockLink> blockLinks,
		List<PortableBenchmarkSample> benchmarkSamples,
		List<PortableSampleGenerationTrace> sampleGenerationTraces
	) {
		this.packageDir = packageDir;
		this.manifest = manifest;
		this.sourceManifest = sourceManifest;
		this.normalizedDocuments = normalizedDocuments;
		this.authoringBlocks = authoringBlocks;
		this.blockLinks = blockLinks;
		this.benchmarkSamples = benchmarkSamples;
		this.sampleGenerationTraces = sampleGenerationTraces;
	}

	public Path getPackageDir() {
		return packageDir;
	}

	public PortableGoldPackageManifest getManifest() {
		return manifest;
	}

	public PortableSourceManifest getSourceManifest() {
		return sourceManifest;
	}

	public List<PortableNormalizedDocument> getNormalizedDocuments() {
		return normalizedDocuments;
	}

	public List<PortableAuthoringBlock> getAuthoringBlocks() {
		return authoringBlocks;
	}

	public List<PortableBlockLink> getBlockLinks() {
		return blockLinks;
	}

	public List<PortableBenchmarkSample> getBenchmarkSamples() {
		return benchmarkSamples;
	}

	public List<PortableSampleGenerationTrace> getSampleGenerationTraces() {
		return sampleGenerationTraces;
	}
}
