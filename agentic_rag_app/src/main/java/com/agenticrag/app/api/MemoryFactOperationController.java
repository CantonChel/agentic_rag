package com.agenticrag.app.api;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/memory")
public class MemoryFactOperationController {
	private final MemoryFileService memoryFileService;
	private final MemoryFactOperationLogService memoryFactOperationLogService;

	public MemoryFactOperationController(
		MemoryFileService memoryFileService,
		MemoryFactOperationLogService memoryFactOperationLogService
	) {
		this.memoryFileService = memoryFileService;
		this.memoryFactOperationLogService = memoryFactOperationLogService;
	}

	@GetMapping(value = "/fact-operations", produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<List<MemoryFactOperationLogService.FactOperationView>> factOperations(
		@RequestParam(value = "userId", defaultValue = "anonymous") String userId,
		@RequestParam("path") String path,
		@RequestParam(value = "limit", defaultValue = "50") int limit
	) {
		return Mono.fromCallable(() -> loadOperations(userId, path, limit))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private List<MemoryFactOperationLogService.FactOperationView> loadOperations(
		String userId,
		String path,
		int limit
	) {
		if (path == null || path.trim().isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
		}
		Path file = memoryFileService.workspaceRoot().resolve(path).normalize();
		if (!Files.exists(file) || !Files.isRegularFile(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory file not found");
		}
		if (!memoryFileService.isAllowedForUser(userId, file)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory file access denied");
		}
		if (!"fact".equals(memoryFileService.kindOf(userId, file))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fact operations only support fact files");
		}
		return memoryFactOperationLogService.list(userId, memoryFileService.relPath(file), limit);
	}
}
