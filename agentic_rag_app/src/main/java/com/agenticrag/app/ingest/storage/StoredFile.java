package com.agenticrag.app.ingest.storage;

public class StoredFile {
	private final String filePath;
	private final String fileHash;
	private final long fileSize;

	public StoredFile(String filePath, String fileHash, long fileSize) {
		this.filePath = filePath;
		this.fileHash = fileHash;
		this.fileSize = fileSize;
	}

	public String getFilePath() {
		return filePath;
	}

	public String getFileHash() {
		return fileHash;
	}

	public long getFileSize() {
		return fileSize;
	}
}
