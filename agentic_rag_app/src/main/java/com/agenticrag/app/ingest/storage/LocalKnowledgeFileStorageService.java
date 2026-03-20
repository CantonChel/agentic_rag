package com.agenticrag.app.ingest.storage;

import com.agenticrag.app.ingest.config.FileStorageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

@Service
public class LocalKnowledgeFileStorageService {
	private final FileStorageProperties properties;

	public LocalKnowledgeFileStorageService(FileStorageProperties properties) {
		this.properties = properties;
	}

	public StoredFile store(String knowledgeId, String originalFileName, byte[] bytes) {
		String safeName = sanitizeFileName(originalFileName);
		Path root = Paths.get(properties.getRootDir()).toAbsolutePath().normalize();
		Path dir = root.resolve(knowledgeId);
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
