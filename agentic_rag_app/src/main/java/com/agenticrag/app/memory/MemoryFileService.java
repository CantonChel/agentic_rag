package com.agenticrag.app.memory;

import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class MemoryFileService {
	private final MemoryProperties properties;

	public MemoryFileService(MemoryProperties properties) {
		this.properties = properties;
	}

	public Path workspaceRoot() {
		String configured = properties.getWorkspaceRoot();
		if (configured == null || configured.trim().isEmpty()) {
			return Paths.get("").toAbsolutePath().normalize();
		}
		return Paths.get(configured).toAbsolutePath().normalize();
	}

	public Path globalMemoryFile() {
		return workspaceRoot().resolve("MEMORY.md").normalize();
	}

	public Path userRoot(String userId) {
		return workspaceRoot()
			.resolve(properties.getUserMemoryBaseDir())
			.resolve(SessionScope.normalizeUserId(userId))
			.normalize();
	}

	public Path dailyDir(String userId) {
		return userRoot(userId).resolve("daily").normalize();
	}

	public Path sessionsDir(String userId) {
		return userRoot(userId).resolve("sessions").normalize();
	}

	public Path embeddingCacheDir() {
		String configured = properties.getEmbeddingCacheDir();
		if (configured == null || configured.trim().isEmpty()) {
			configured = "memory/.cache/embeddings";
		}
		return workspaceRoot().resolve(configured).normalize();
	}

	public List<Path> discoverMemoryFiles(String userId, boolean includeGlobal) {
		String normalizedUserId = SessionScope.normalizeUserId(userId);
		List<Path> files = new ArrayList<>();
		if (includeGlobal) {
			Path global = globalMemoryFile();
			if (Files.exists(global) && Files.isRegularFile(global)) {
				files.add(global);
			}
		}
		Path userDir = userRoot(normalizedUserId);
		if (!Files.exists(userDir) || !Files.isDirectory(userDir)) {
			return files;
		}
		try (Stream<Path> walk = Files.walk(userDir)) {
			walk.filter(Files::isRegularFile)
				.filter(this::isMarkdownFile)
				.sorted()
				.forEach(files::add);
		} catch (IOException ignored) {
			// ignore broken paths
		}
		return files;
	}

	public boolean isAllowedForUser(String userId, Path path) {
		if (path == null) {
			return false;
		}
		Path abs = path.toAbsolutePath().normalize();
		Path global = globalMemoryFile().toAbsolutePath().normalize();
		if (global.equals(abs)) {
			return true;
		}
		Path userDir = userRoot(userId).toAbsolutePath().normalize();
		return abs.startsWith(userDir) && isMarkdownFile(abs);
	}

	public Path resolveReadablePath(String userId, String relativePath) {
		if (relativePath == null || relativePath.trim().isEmpty()) {
			return null;
		}
		Path resolved = workspaceRoot().resolve(relativePath).normalize();
		if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
			return null;
		}
		if (!isAllowedForUser(userId, resolved)) {
			return null;
		}
		return resolved;
	}

	public String kindOf(String userId, Path path) {
		Path abs = path.toAbsolutePath().normalize();
		if (abs.equals(globalMemoryFile().toAbsolutePath().normalize())) {
			return "global";
		}
		Path sessionsDir = sessionsDir(userId).toAbsolutePath().normalize();
		if (abs.startsWith(sessionsDir)) {
			return "session_archive";
		}
		return "daily_durable";
	}

	public String relPath(Path path) {
		try {
			return workspaceRoot().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
		} catch (Exception ignored) {
			return path.toAbsolutePath().normalize().toString().replace('\\', '/');
		}
	}

	private boolean isMarkdownFile(Path path) {
		String name = path.getFileName() != null ? path.getFileName().toString() : "";
		return name.toLowerCase(Locale.ROOT).endsWith(".md");
	}
}
