package com.agenticrag.app.ingest.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ingest.file-storage.backend", havingValue = "minio")
public class MinioKnowledgeFileStorageService implements KnowledgeFileStorageService {
	private final MinioObjectService minioObjectService;

	public MinioKnowledgeFileStorageService(MinioObjectService minioObjectService) {
		this.minioObjectService = minioObjectService;
	}

	@Override
	public StoredFile store(String userId, String knowledgeId, String originalFileName, byte[] bytes) {
		String bucket = minioObjectService.getDefaultBucket();
		String key = sanitizePathToken(userId)
			+ "/"
			+ sanitizePathToken(knowledgeId)
			+ "/original/"
			+ sanitizeFileName(originalFileName);
		String contentType = detectContentType(originalFileName);
		byte[] safe = bytes != null ? bytes : new byte[0];
		minioObjectService.putBytes(bucket, key, safe, contentType);
		return new StoredFile(toMinioUri(bucket, key), md5(safe), safe.length);
	}

	@Override
	public String resolveReadUrl(String filePath) {
		MinioPath parsed = parseMinioUri(filePath);
		return minioObjectService.getPresignedGetUrl(parsed.bucket, parsed.key);
	}

	private String toMinioUri(String bucket, String key) {
		return "minio://" + bucket + "/" + key;
	}

	private MinioPath parseMinioUri(String value) {
		if (value == null || !value.startsWith("minio://")) {
			throw new IllegalArgumentException("invalid minio file path");
		}
		String raw = value.substring("minio://".length());
		int split = raw.indexOf('/');
		if (split <= 0 || split >= raw.length() - 1) {
			throw new IllegalArgumentException("invalid minio file path");
		}
		return new MinioPath(raw.substring(0, split), raw.substring(split + 1));
	}

	private String sanitizeFileName(String input) {
		if (input == null || input.trim().isEmpty()) {
			return "upload.bin";
		}
		String cleaned = input.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		return cleaned.isEmpty() ? "upload.bin" : cleaned;
	}

	private String sanitizePathToken(String input) {
		if (input == null || input.trim().isEmpty()) {
			return "anonymous";
		}
		String cleaned = input.replaceAll("[^a-zA-Z0-9._-]", "_").trim();
		return cleaned.isEmpty() ? "anonymous" : cleaned;
	}

	private String detectContentType(String fileName) {
		String ext = "";
		if (fileName != null) {
			int idx = fileName.lastIndexOf('.');
			if (idx >= 0 && idx < fileName.length() - 1) {
				ext = fileName.substring(idx + 1).toLowerCase();
			}
		}
		switch (ext) {
			case "pdf":
				return "application/pdf";
			case "txt":
				return "text/plain";
			case "md":
				return "text/markdown";
			case "json":
				return "application/json";
			default:
				return "application/octet-stream";
		}
	}

	private String md5(byte[] input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			byte[] bytes = digest.digest(input);
			StringBuilder sb = new StringBuilder();
			for (byte b : bytes) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("md5 not available", e);
		}
	}

	private static class MinioPath {
		private final String bucket;
		private final String key;

		private MinioPath(String bucket, String key) {
			this.bucket = bucket;
			this.key = key;
		}
	}
}
