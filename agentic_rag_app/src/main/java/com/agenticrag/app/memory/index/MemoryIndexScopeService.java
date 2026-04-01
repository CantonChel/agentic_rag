package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.session.SessionScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexScopeService {
	private final MemoryFileService memoryFileService;
	private final ObjectMapper objectMapper;

	public MemoryIndexScopeService(MemoryFileService memoryFileService, ObjectMapper objectMapper) {
		this.memoryFileService = memoryFileService;
		this.objectMapper = objectMapper;
	}

	public MemoryIndexScope globalScope() {
		return new MemoryIndexScope(MemoryIndexScopeType.GLOBAL, MemoryIndexConstants.GLOBAL_SCOPE_ID);
	}

	public MemoryIndexScope userScope(String userId) {
		return new MemoryIndexScope(MemoryIndexScopeType.USER, SessionScope.normalizeUserId(userId));
	}

	public MemoryIndexScope fromStored(String scopeType, String scopeId) {
		return new MemoryIndexScope(MemoryIndexScopeType.fromValue(scopeType), scopeId);
	}

	public List<MemoryIndexScope> discoverScopesFromDisk() {
		Set<MemoryIndexScope> scopes = new LinkedHashSet<>();
		if (Files.exists(memoryFileService.globalMemoryFile())) {
			scopes.add(globalScope());
		}
		Path usersBase = memoryFileService.userRoot("placeholder").getParent();
		if (usersBase == null || !Files.exists(usersBase) || !Files.isDirectory(usersBase)) {
			return new ArrayList<>(scopes);
		}
		try (java.util.stream.Stream<Path> stream = Files.list(usersBase)) {
			stream
				.filter(Files::isDirectory)
				.forEach(path -> scopes.add(userScope(path.getFileName() != null ? path.getFileName().toString() : "")));
		} catch (IOException ignored) {
			// ignore broken directories while discovering scopes
		}
		return new ArrayList<>(scopes);
	}

	public List<Path> filesForScope(MemoryIndexScope scope) {
		List<Path> files = new ArrayList<>();
		if (scope == null) {
			return files;
		}
		if (scope.getType() == MemoryIndexScopeType.GLOBAL) {
			Path global = memoryFileService.globalMemoryFile();
			if (Files.exists(global) && Files.isRegularFile(global)) {
				files.add(global);
			}
			return files;
		}
		return memoryFileService.discoverMemoryFiles(scope.getId(), false);
	}

	public String sourcesJson(MemoryIndexScope scope) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("scopeType", scope.getTypeValue());
		if (scope.getType() == MemoryIndexScopeType.GLOBAL) {
			payload.put("root", relative(memoryFileService.globalMemoryFile()));
			payload.put("includePatterns", new String[] {"MEMORY.md"});
		} else {
			Path userRoot = memoryFileService.userRoot(scope.getId());
			payload.put("root", relative(userRoot));
			payload.put("includePatterns", new String[] {"**/*.md"});
			payload.put("excludePatterns", new String[] {"memory/.cache/**"});
		}
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (Exception e) {
			return "{\"scopeType\":\"" + scope.getTypeValue() + "\"}";
		}
	}

	public String scopeHash(MemoryIndexScope scope) {
		return sha256(scope.getTypeValue() + "|" + scope.getId() + "|" + sourcesJson(scope));
	}

	private String relative(Path path) {
		return memoryFileService.relPath(path);
	}

	private String sha256(String raw) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((raw != null ? raw : "").getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (byte value : bytes) {
				out.append(String.format("%02x", value));
			}
			return out.toString();
		} catch (Exception e) {
			return Integer.toHexString(raw != null ? raw.hashCode() : 0);
		}
	}
}
