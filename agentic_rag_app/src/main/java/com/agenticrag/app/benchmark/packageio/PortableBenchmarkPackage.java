package com.agenticrag.app.benchmark.packageio;

import java.nio.file.Path;
import java.util.List;

public class PortableBenchmarkPackage {
	private final Path packageDir;
	private final PortableSuiteManifest manifest;
	private final List<PortableEvidenceUnit> evidenceUnits;
	private final List<PortableBenchmarkSample> benchmarkSamples;

	public PortableBenchmarkPackage(
		Path packageDir,
		PortableSuiteManifest manifest,
		List<PortableEvidenceUnit> evidenceUnits,
		List<PortableBenchmarkSample> benchmarkSamples
	) {
		this.packageDir = packageDir;
		this.manifest = manifest;
		this.evidenceUnits = evidenceUnits;
		this.benchmarkSamples = benchmarkSamples;
	}

	public Path getPackageDir() {
		return packageDir;
	}

	public PortableSuiteManifest getManifest() {
		return manifest;
	}

	public List<PortableEvidenceUnit> getEvidenceUnits() {
		return evidenceUnits;
	}

	public List<PortableBenchmarkSample> getBenchmarkSamples() {
		return benchmarkSamples;
	}
}
