package com.agenticrag.app.rag.splitter;

import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RecursiveCharacterTextSplitter implements TextSplitter {
	private final TokenCounter tokenCounter;
	private final int chunkSize;
	private final int chunkOverlap;
	private final List<String> separators;

	public RecursiveCharacterTextSplitter(TokenCounter tokenCounter, RecursiveCharacterTextSplitterProperties properties) {
		this.tokenCounter = tokenCounter;
		this.chunkSize = properties != null && properties.getChunkSize() > 0 ? properties.getChunkSize() : 500;
		int overlap = properties != null ? properties.getChunkOverlap() : 50;
		this.chunkOverlap = Math.max(0, Math.min(overlap, this.chunkSize));
		this.separators = Arrays.asList("\n\n", "\n", "。", " ", "");
	}

	@Override
	public List<TextChunk> split(Document doc) {
		if (doc == null) {
			return new ArrayList<>();
		}

		String content = doc.getContent() != null ? doc.getContent() : "";
		if (content.trim().isEmpty()) {
			return new ArrayList<>();
		}

		List<String> splits = splitRecursively(content, 0);
		List<String> merged = mergeSplits(splits);

		List<TextChunk> chunks = new ArrayList<>();
		int idx = 0;
		for (String text : merged) {
			if (text == null) {
				continue;
			}
			String trimmed = text.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			Map<String, Object> md = new HashMap<>();
			if (doc.getMetadata() != null) {
				md.putAll(doc.getMetadata());
			}
			md.put("chunk_index", idx);
			String chunkId = doc.getId() + ":" + idx;
			chunks.add(new TextChunk(chunkId, doc.getId(), trimmed, null, md));
			idx++;
		}
		return chunks;
	}

	private List<String> splitRecursively(String text, int sepIndex) {
		if (text == null || text.isEmpty()) {
			return new ArrayList<>();
		}
		if (tokenCount(text) <= chunkSize) {
			return Arrays.asList(text);
		}

		if (sepIndex >= separators.size()) {
			return hardSplit(text);
		}

		String sep = separators.get(sepIndex);
		if (sep == null) {
			sep = "";
		}

		List<String> parts = splitKeepSeparator(text, sep);
		List<String> out = new ArrayList<>();
		for (String part : parts) {
			if (part == null || part.isEmpty()) {
				continue;
			}
			if (tokenCount(part) <= chunkSize) {
				out.add(part);
				continue;
			}
			if (sepIndex < separators.size() - 1) {
				out.addAll(splitRecursively(part, sepIndex + 1));
			} else {
				out.addAll(hardSplit(part));
			}
		}
		return out;
	}

	private List<String> splitKeepSeparator(String text, String sep) {
		if (sep == null || sep.isEmpty()) {
			return Arrays.asList(text);
		}
		List<String> out = new ArrayList<>();
		int idx = 0;
		while (idx < text.length()) {
			int next = text.indexOf(sep, idx);
			if (next < 0) {
				out.add(text.substring(idx));
				break;
			}
			int end = next + sep.length();
			out.add(text.substring(idx, end));
			idx = end;
		}
		return out;
	}

	private List<String> mergeSplits(List<String> splits) {
		List<String> out = new ArrayList<>();
		if (splits == null || splits.isEmpty()) {
			return out;
		}

		StringBuilder current = new StringBuilder();
		for (String part : splits) {
			if (part == null || part.isEmpty()) {
				continue;
			}
			if (current.length() == 0) {
				current.append(part);
				continue;
			}

			String candidate = current.toString() + part;
			if (tokenCount(candidate) <= chunkSize) {
				current.append(part);
				continue;
			}

			String flushed = current.toString();
			if (!flushed.trim().isEmpty()) {
				out.add(flushed);
			}

			current = new StringBuilder();
			if (chunkOverlap > 0) {
				String overlap = tailByTokens(flushed, chunkOverlap, Math.max(0, chunkSize - tokenCount(part)));
				if (overlap != null && !overlap.isEmpty()) {
					current.append(overlap);
				}
			}

			if (tokenCount(current.toString() + part) > chunkSize) {
				current = new StringBuilder();
			}
			current.append(part);
		}

		String flushed = current.toString();
		if (!flushed.trim().isEmpty()) {
			out.add(flushed);
		}
		return out;
	}

	private List<String> hardSplit(String text) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}

		int start = 0;
		while (start < text.length()) {
			int low = start + 1;
			int high = text.length();
			int best = -1;
			while (low <= high) {
				int mid = low + (high - low) / 2;
				String candidate = text.substring(start, mid);
				if (tokenCount(candidate) <= chunkSize) {
					best = mid;
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			}
			if (best <= start) {
				best = Math.min(start + 1, text.length());
			}
			out.add(text.substring(start, best));
			start = best;
		}
		return out;
	}

	private String tailByTokens(String text, int desiredTokens, int maxTokens) {
		if (text == null || text.isEmpty() || desiredTokens <= 0 || maxTokens <= 0) {
			return "";
		}
		int want = Math.min(desiredTokens, maxTokens);
		int len = text.length();
		int low = 0;
		int high = len;
		int best = len;
		while (low <= high) {
			int mid = low + (high - low) / 2;
			String candidate = text.substring(mid);
			int tokens = tokenCount(candidate);
			if (tokens > want) {
				low = mid + 1;
			} else {
				best = mid;
				high = mid - 1;
			}
		}
		String tail = text.substring(best);
		if (tokenCount(tail) > want) {
			return "";
		}
		return tail;
	}

	private int tokenCount(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return tokenCounter != null ? tokenCounter.count(text) : text.length();
	}
}

