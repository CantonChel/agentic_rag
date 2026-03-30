package com.agenticrag.app.api;

import com.agenticrag.app.memory.MemoryFileService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
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
	private final MemoryFileService memoryFileService;

	public MemoryBrowseController(MemoryFileService memoryFileService) {
		this.memoryFileService = memoryFileService;
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
		List<Path> files = new ArrayList<>(memoryFileService.discoverMemoryFiles(userId, includeGlobal));
		return files.stream()
			.map(path -> toFileView(userId, path))
			.sorted(Comparator.comparing(MemoryFileView::getUpdatedAt).reversed())
			.collect(Collectors.toList());
	}

	private MemoryFileContentView doReadFile(String userId, String id) {
		if (id == null || id.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id is required");
		}
		Path file = memoryFileService.workspaceRoot().resolve(id).normalize();
		if (!Files.exists(file) || !Files.isRegularFile(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory file not found");
		}
		if (!memoryFileService.isAllowedForUser(userId, file)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory file access denied");
		}
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			return new MemoryFileContentView(
				memoryFileService.relPath(file),
				memoryFileService.relPath(file),
				memoryFileService.kindOf(userId, file),
				content,
				Files.size(file),
				Files.getLastModifiedTime(file).toInstant().toString()
			);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "failed to read memory file");
		}
	}

	private MemoryFileView toFileView(String userId, Path path) {
		String rel = memoryFileService.relPath(path);
		String kind = memoryFileService.kindOf(userId, path);
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
