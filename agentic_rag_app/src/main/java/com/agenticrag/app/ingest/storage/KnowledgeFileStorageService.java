package com.agenticrag.app.ingest.storage;

public interface KnowledgeFileStorageService {
	StoredFile store(String userId, String knowledgeId, String originalFileName, byte[] bytes);

	String resolveReadUrl(String filePath);

	default void delete(String filePath) {
	}
}
