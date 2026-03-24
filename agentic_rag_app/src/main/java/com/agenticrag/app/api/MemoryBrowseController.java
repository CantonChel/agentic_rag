package com.agenticrag.app.api;

import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/memory")
public class MemoryBrowseController {
	private final MemoryProperties memoryProperties;

	public MemoryBrowseController(MemoryProperties memoryProperties) {
		this.memoryProperties = memoryProperties;
	}

	@GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<MemoryFileView>> listFiles(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam(value = "includeGlobal", defaultValue = "true") boolean includeGlobal
	) {
		return Mono.fromCallable(() -> doListFiles(userId, includeGlobal))
			.subscribeOn(Schedulers.boundedElastic());
	}

	@GetMapping(value = "/file", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<MemoryFileContentView> readFile(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam("id") String id
	) {
		return Mono.fromCallable(() -> doReadFile(userId, id))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private List<MemoryFileView> doListFiles(String userId, boolean includeGlobal) {
		String uid = SessionScope.normalizeUserId(userId);
		Path root = workspaceRoot();
		List<Path> files = new ArrayList<>();

		if (includeGlobal) {
			Path global = root.resolve("MEMORY.md").normalize();
			if (Files.exists(global) && Files.isRegularFile(global)) {
				files.add(global);
			}
		}

		Path userDir = userMemoryDir(root, uid);
		if (Files.exists(userDir) && Files.isDirectory(userDir)) {
			try (Stream<Path> walk = Files.walk(userDir)) {
				walk.filter(Files::isRegularFile)
					.filter(this::isMarkdownFile)
					.forEach(files::add);
			} catch (IOException ignored) {
				// ignore broken memory path
			}
		}

		return files.stream()
			.map(path -> toFileView(root, uid, path))
			.sorted(Comparator.comparing(MemoryFileView::getUpdatedAt).reversed())
			.collect(Collectors.toList());
	}

	private MemoryFileContentView doReadFile(String userId, String id) {
		if (id == null || id.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
		}
		String uid = SessionScope.normalizeUserId(userId);
		Path root = workspaceRoot();
		Path file = root.resolve(id).normalize();
		if (!Files.exists(file) || !Files.isRegularFile(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory file not found");
		}
		if (!isAllowedForUser(root, uid, file)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory file access denied");
		}
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			return new MemoryFileContentView(
				relPath(root, file),
				relPath(root, file),
				kindOf(root, uid, file),
				content,
				Files.size(file),
				Files.getLastModifiedTime(file).toInstant().toString()
			);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read memory file");
		}
	}

	private boolean isAllowedForUser(Path root, String userId, Path path) {
		Path abs = path.toAbsolutePath().normalize();
		Path global = root.resolve("MEMORY.md").toAbsolutePath().normalize();
		if (global.equals(abs)) {
			return true;
		}
		Path userDir = userMemoryDir(root, userId).toAbsolutePath().normalize();
		return abs.startsWith(userDir) && isMarkdownFile(abs);
	}

	private MemoryFileView toFileView(Path root, String userId, Path path) {
		String rel = relPath(root, path);
		String kind = kindOf(root, userId, path);
		long size = 0L;
		String updatedAt = "";
		try {
			size = Files.size(path);
			updatedAt = Files.getLastModifiedTime(path).toInstant().toString();
		} catch (IOException ignored) {
			// keep defaults
		}
		return new MemoryFileView(rel, rel, kind, size, updatedAt);
	}

	private String kindOf(Path root, String userId, Path path) {
		Path abs = path.toAbsolutePath().normalize();
		if (abs.equals(root.resolve("MEMORY.md").toAbsolutePath().normalize())) {
			return "global";
		}
		Path sessions = userMemoryDir(root, userId).resolve("sessions").toAbsolutePath().normalize();
		if (abs.startsWith(sessions)) {
			return "session_snapshot";
		}
		return "daily_memory";
	}

	private boolean isMarkdownFile(Path path) {
		String name = path.getFileName() != null ? path.getFileName().toString() : "";
		return name.toLowerCase(Locale.ROOT).endsWith(".md");
	}

	private Path userMemoryDir(Path root, String userId) {
		return root.resolve(memoryProperties.getUserMemoryBaseDir()).resolve(SessionScope.normalizeUserId(userId)).normalize();
	}

	private Path workspaceRoot() {
		String configured = memoryProperties.getWorkspaceRoot();
		if (configured == null || configured.trim().isEmpty()) {
			return Paths.get("").toAbsolutePath().normalize();
		}
		return Paths.get(configured).toAbsolutePath().normalize();
	}

	private String relPath(Path root, Path path) {
		try {
			return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
		} catch (Exception ignored) {
			return path.toAbsolutePath().normalize().toString().replace('\\', '/');
		}
	}

	public static class MemoryFileView {
		private final String id;
		private final String path;
		private final String kind;
		private final long size;
		private final String updatedAt;

		public MemoryFileView(String id, String path, String kind, long size, String updatedAt) {
			this.id = id;
			this.path = path;
			this.kind = kind;
			this.size = size;
			this.updatedAt = updatedAt == null ? Instant.EPOCH.toString() : updatedAt;
		}

		public String getId() {
			return id;
		}

		public String getPath() {
			return path;
		}

		public String getKind() {
			return kind;
		}

		public long getSize() {
			return size;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}

	public static class MemoryFileContentView {
		private final String id;
		private final String path;
		private final String kind;
		private final String content;
		private final long size;
		private final String updatedAt;

		public MemoryFileContentView(
			String id,
			String path,
			String kind,
			String content,
			long size,
			String updatedAt
		) {
			this.id = id;
			this.path = path;
			this.kind = kind;
			this.content = content;
			this.size = size;
			this.updatedAt = updatedAt;
		}

		public String getId() {
			return id;
		}

		public String getPath() {
			return path;
		}

		public String getKind() {
			return kind;
		}

		public String getContent() {
			return content;
		}

		public long getSize() {
			return size;
		}

		public String getUpdatedAt() {
			return updatedAt;
		}
	}
}
