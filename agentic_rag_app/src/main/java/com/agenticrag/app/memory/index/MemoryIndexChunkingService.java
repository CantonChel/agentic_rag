package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryBlockParser;
import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.memory.ParsedMemoryBlock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexChunkingService {
	private final MemoryProperties properties;
	private final MemoryBlockParser memoryBlockParser;

	public MemoryIndexChunkingService(MemoryProperties properties, MemoryBlockParser memoryBlockParser) {
		this.properties = properties;
		this.memoryBlockParser = memoryBlockParser;
	}

	public List<MemoryIndexChunkSeed> buildSeeds(MemoryIndexScope scope, Path file, String content) {
		List<MemoryIndexChunkSeed> seeds = new ArrayList<>();
		if (scope == null || file == null || content == null || content.trim().isEmpty()) {
			return seeds;
		}
		String parserUserId = scope.getType() == MemoryIndexScopeType.USER ? scope.getId() : MemoryIndexConstants.GLOBAL_SCOPE_ID;
		for (ParsedMemoryBlock block : memoryBlockParser.parseContent(parserUserId, file, content)) {
			seeds.addAll(splitBlock(block));
		}
		return seeds;
	}

	private List<MemoryIndexChunkSeed> splitBlock(ParsedMemoryBlock block) {
		String blockContent = block != null && block.getContent() != null ? block.getContent().trim() : "";
		if (blockContent.isEmpty()) {
			return new ArrayList<>();
		}
		int maxChars = properties.getMaxChunkChars() > 0 ? properties.getMaxChunkChars() : 800;
		int overlapChars = Math.max(0, properties.getChunkOverlap());
		List<String> lines = Arrays.asList(blockContent.split("\\r?\\n", -1));
		List<MemoryIndexChunkSeed> out = new ArrayList<>();
		int cursor = 0;
		while (cursor < lines.size()) {
			int endExclusive = cursor;
			int currentChars = 0;
			while (endExclusive < lines.size()) {
				String line = lines.get(endExclusive);
				int nextChars = line.length();
				if (currentChars > 0) {
					nextChars += 1;
				}
				if (currentChars > 0 && currentChars + nextChars > maxChars) {
					break;
				}
				currentChars += nextChars;
				endExclusive++;
			}
			if (endExclusive == cursor) {
				endExclusive = cursor + 1;
			}
			String text = joinLines(lines, cursor, endExclusive).trim();
			if (!text.isEmpty()) {
				out.add(new MemoryIndexChunkSeed(
					block.getRelativePath(),
					block.getKind(),
					block.getMetadata() != null ? block.getMetadata().getBlockId() : null,
					block.getStartLine() + cursor,
					block.getStartLine() + endExclusive - 1,
					text,
					sha256(text)
				));
			}
			if (endExclusive >= lines.size()) {
				break;
			}
			cursor = nextCursor(lines, cursor, endExclusive, overlapChars);
		}
		return out;
	}

	private int nextCursor(List<String> lines, int start, int endExclusive, int overlapChars) {
		if (overlapChars <= 0 || endExclusive - start <= 1) {
			return endExclusive;
		}
		int cursor = endExclusive;
		int chars = 0;
		for (int i = endExclusive - 1; i > start; i--) {
			int addition = lines.get(i).length();
			if (chars > 0) {
				addition += 1;
			}
			chars += addition;
			cursor = i;
			if (chars >= overlapChars) {
				break;
			}
		}
		return cursor < endExclusive ? cursor : endExclusive;
	}

	private String joinLines(List<String> lines, int startInclusive, int endExclusive) {
		StringBuilder out = new StringBuilder();
		for (int i = startInclusive; i < endExclusive && i < lines.size(); i++) {
			if (out.length() > 0) {
				out.append('\n');
			}
			out.append(lines.get(i));
		}
		return out.toString();
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
