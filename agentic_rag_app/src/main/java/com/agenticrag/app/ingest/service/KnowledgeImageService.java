package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.storage.KnowledgeFileStorageService;
import com.agenticrag.app.ingest.storage.StoredBinary;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeImageService {
	private final KnowledgeFileStorageService fileStorageService;

	public KnowledgeImageService(KnowledgeFileStorageService fileStorageService) {
		this.fileStorageService = fileStorageService;
	}

	public StoredBinary loadImage(String filePath) {
		String safeFilePath = filePath != null ? filePath.trim() : "";
		if (safeFilePath.isEmpty()) {
			throw new IllegalArgumentException("filePath is required");
		}
		return fileStorageService.load(safeFilePath);
	}
}
