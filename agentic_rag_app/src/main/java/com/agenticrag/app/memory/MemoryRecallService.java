package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexSearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MemoryRecallService {
	private final MemoryProperties properties;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;
	private final MemoryIndexSearchService searchService;

	public MemoryRecallService(
		MemoryProperties properties,
		MemoryFileService memoryFileService,
		MemoryBlockParser memoryBlockParser,
		MemoryIndexSearchService searchService
	) {
		this.properties = properties;
		this.memoryFileService = memoryFileService;
		this.memoryBlockParser = memoryBlockParser;
		this.searchService = searchService;
	}

	public List<MemorySearchHit> search(String userId, String query, Integer requestedTopK) {
		if (!properties.isEnabled()) {
			return new ArrayList<>();
		}
		return searchService.search(userId, query, requestedTopK);
	}

	public MemoryReadResult get(String userId, String path, Integer lineStart, Integer lineEnd) {
		Path file = memoryFileService.resolveReadablePath(userId, path);
		if (file == null) {
			return null;
		}
		int start = lineStart != null ? lineStart.intValue() : 1;
		int end = lineEnd != null ? lineEnd.intValue() : start;
		if (start < 1 || end < start) {
			return null;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
		if (lines.isEmpty() || start > lines.size()) {
			return null;
		}
		int boundedEnd = Math.min(end, lines.size());
		StringBuilder content = new StringBuilder();
		for (int i = start; i <= boundedEnd; i++) {
			if (content.length() > 0) {
				content.append('\n');
			}
			content.append(lines.get(i - 1));
		}

		String kind = memoryFileService.kindOf(userId, file);
		String blockId = null;
		for (ParsedMemoryBlock block : memoryBlockParser.parse(userId, file)) {
			if (start >= block.getStartLine() && boundedEnd <= block.getEndLine()) {
				blockId = block.getMetadata() != null ? block.getMetadata().getBlockId() : null;
				kind = block.getKind();
				break;
			}
		}
		return new MemoryReadResult(
			memoryFileService.relPath(file),
			kind,
			blockId,
			start,
			boundedEnd,
			content.toString()
		);
	}
}
