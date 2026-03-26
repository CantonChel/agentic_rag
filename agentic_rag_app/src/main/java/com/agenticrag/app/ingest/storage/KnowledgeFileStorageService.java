package com.agenticrag.app.ingest.storage;

public interface KnowledgeFileStorageService {
	StoredFile store(String userId, String knowledgeId, String originalFileName, byte[] bytes);

	String resolveReadUrl(String filePath);

	StoredBinary load(String filePath);

	default boolean deleteAndReport(String filePath) {
		delete(filePath);
		return true;
	}

	default void delete(String filePath) {
		deleteAndReport(filePath);
	}
}
