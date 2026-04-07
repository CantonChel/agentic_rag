package com.agenticrag.app.benchmark.packageio;

public class PortableSourceFileRecord {
	private final String path;
	private final long sizeBytes;
	private final String sha256;

	public PortableSourceFileRecord(String path, long sizeBytes, String sha256) {
		this.path = path;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
	}

	public String getPath() {
		return path;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public String getSha256() {
		return sha256;
	}
}
