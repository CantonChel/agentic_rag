package com.agenticrag.app.benchmark.packageio;

import java.util.Map;

public class PortableGoldPackageManifest {
	private final String packageVersion;
	private final String projectKey;
	private final String suiteVersion;
	private final String createdAt;
	private final String generatorVersion;
	private final Map<String, String> files;

	public PortableGoldPackageManifest(
		String packageVersion,
		String projectKey,
		String suiteVersion,
		String createdAt,
		String generatorVersion,
		Map<String, String> files
	) {
		this.packageVersion = packageVersion;
		this.projectKey = projectKey;
		this.suiteVersion = suiteVersion;
		this.createdAt = createdAt;
		this.generatorVersion = generatorVersion;
		this.files = files;
	}

	public String getPackageVersion() {
		return packageVersion;
	}

	public String getProjectKey() {
		return projectKey;
	}

	public String getSuiteVersion() {
		return suiteVersion;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public String getGeneratorVersion() {
		return generatorVersion;
	}

	public Map<String, String> getFiles() {
		return files;
	}
}
