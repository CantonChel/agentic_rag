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

	public Path factsDir(String userId) {
		return userRoot(userId).resolve("facts").normalize();
	}

	public Path summariesDir(String userId) {
		return userRoot(userId).resolve("summaries").normalize();
	}

	@Deprecated
	public Path dailyDir(String userId) {
		return factsDir(userId);
	}

	@Deprecated
	public Path sessionsDir(String userId) {
		return summariesDir(userId);
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
		addMarkdownFiles(factsDir(normalizedUserId), files);
		addMarkdownFiles(summariesDir(normalizedUserId), files);
		files.sort(Path::compareTo);
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
		return isManagedUserMarkdown(userId, abs);
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
		Path summariesDir = summariesDir(userId).toAbsolutePath().normalize();
		if (abs.startsWith(summariesDir)) {
			return "session_summary";
		}
		Path factsDir = factsDir(userId).toAbsolutePath().normalize();
		if (abs.startsWith(factsDir)) {
			return "fact";
		}
		return "unknown";
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

	private boolean isManagedUserMarkdown(String userId, Path path) {
		if (path == null || !isMarkdownFile(path)) {
			return false;
		}
		Path abs = path.toAbsolutePath().normalize();
		Path factsDir = factsDir(userId).toAbsolutePath().normalize();
		if (abs.startsWith(factsDir)) {
			return true;
		}
		Path summariesDir = summariesDir(userId).toAbsolutePath().normalize();
		return abs.startsWith(summariesDir);
	}

	private void addMarkdownFiles(Path directory, List<Path> out) {
		if (directory == null || out == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(directory)) {
			walk.filter(Files::isRegularFile)
				.filter(this::isMarkdownFile)
				.forEach(out::add);
		} catch (IOException ignored) {
			// ignore broken paths
		}
	}
}
