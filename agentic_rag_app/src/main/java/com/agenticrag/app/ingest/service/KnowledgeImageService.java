package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.storage.MinioObjectService;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeImageService {
	private final Optional<MinioObjectService> minioObjectService;

	public KnowledgeImageService(Optional<MinioObjectService> minioObjectService) {
		this.minioObjectService = minioObjectService;
	}

	public MinioObjectService.StoredObject loadImage(String bucket, String key) {
		String safeBucket = bucket != null ? bucket.trim() : "";
		String safeKey = key != null ? key.trim() : "";
		if (safeBucket.isEmpty() || safeKey.isEmpty()) {
			throw new IllegalArgumentException("bucket and key are required");
		}
		MinioObjectService service = minioObjectService.orElseThrow(() -> new IllegalStateException("minio storage unavailable"));
		return service.getObject(safeBucket, safeKey);
	}
}
