package com.agenticrag.app.ingest.storage;

import com.agenticrag.app.ingest.config.FileStorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "ingest.file-storage.backend", havingValue = "local", matchIfMissing = true)
public class LocalKnowledgeFileStorageService implements KnowledgeFileStorageService {
	private final FileStorageProperties properties;

	public LocalKnowledgeFileStorageService(FileStorageProperties properties) {
		this.properties = properties;
	}

	@Override
	public StoredFile store(String userId, String knowledgeId, String originalFileName, byte[] bytes) {
		String safeName = sanitizeFileName(originalFileName);
		Path root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
		Path dir = root.resolve(sanitizePathToken(userId)).resolve(knowledgeId);
		try {
			Files.createDirectories(dir);
			Path file = dir.resolve(safeName);
			Files.write(file, bytes != null ? bytes : new byte[0]);
			String hash = md5(bytes != null ? bytes : new byte[0]);
			long size = bytes != null ? bytes.length : 0;
			return new StoredFile(file.toString(), hash, size);
		} catch (IOException e) {
			throw new IllegalStateException("failed to store knowledge file", e);
		}
	}

	@Override
	public String resolveReadUrl(String filePath) {
		return filePath;
	}

	@Override
	public void delete(String filePath) {
		if (filePath == null || filePath.trim().isEmpty()) {
			return;
		}
		try {
			Path file = Paths.get(filePath).toAbsolutePath().normalize();
			Files.deleteIfExists(file);
		} catch (Exception ignored) {
		}
	}

	private String sanitizeFileName(String input) {
		if (input == null || input.trim().isEmpty()) {
			return "upload.bin";
		}
		String cleaned = input.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
		if (cleaned.isEmpty()) {
			return "upload.bin";
		}
		return cleaned;
	}

	private String sanitizePathToken(String input) {
		if (input == null || input.trim().isEmpty()) {
			return "anonymous";
		}
		String cleaned = input.replaceAll("[^a-zA-Z0-9._-]", "_").trim();
		return cleaned.isEmpty() ? "anonymous" : cleaned;
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
}
