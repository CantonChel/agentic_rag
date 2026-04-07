package com.agenticrag.app.benchmark.packageio;

import java.util.List;

public class PortableSourceManifest {
	private final String sourceSetId;
	private final String projectKey;
	private final String sourceRoot;
	private final int fileCount;
	private final List<PortableSourceFileRecord> files;
	private final String createdAt;

	public PortableSourceManifest(
		String sourceSetId,
		String projectKey,
		String sourceRoot,
		int fileCount,
		List<PortableSourceFileRecord> files,
		String createdAt
	) {
		this.sourceSetId = sourceSetId;
		this.projectKey = projectKey;
		this.sourceRoot = sourceRoot;
		this.fileCount = fileCount;
		this.files = files;
		this.createdAt = createdAt;
	}

	public String getSourceSetId() {
		return sourceSetId;
	}

	public String getProjectKey() {
		return projectKey;
	}

	public String getSourceRoot() {
		return sourceRoot;
	}

	public int getFileCount() {
		return fileCount;
	}

	public List<PortableSourceFileRecord> getFiles() {
		return files;
	}

	public String getCreatedAt() {
		return createdAt;
	}
}
