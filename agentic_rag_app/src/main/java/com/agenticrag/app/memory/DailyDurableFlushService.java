package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class DailyDurableFlushService {
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final MemoryProperties properties;
	private final MemoryLlmExtractor memoryLlmExtractor;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;
	private final MemoryFactMarkdownCodec factMarkdownCodec;

	public DailyDurableFlushService(
		MemoryProperties properties,
		MemoryLlmExtractor memoryLlmExtractor,
		MemoryFileService memoryFileService,
		MemoryBlockParser memoryBlockParser,
		MemoryFactMarkdownCodec factMarkdownCodec
	) {
		this.properties = properties;
		this.memoryLlmExtractor = memoryLlmExtractor;
		this.memoryFileService = memoryFileService;
		this.memoryBlockParser = memoryBlockParser;
		this.factMarkdownCodec = factMarkdownCodec;
	}

	public void flush(String scopedSessionId, List<ChatMessage> messages) {
		if (!properties.isEnabled() || !properties.isFlushEnabled()) {
			return;
		}
		if (messages == null || messages.isEmpty()) {
			return;
		}
		String userId = SessionScope.userIdFromScopedSessionId(scopedSessionId);
		String sessionId = SessionScope.sessionIdFromScopedSessionId(scopedSessionId);
		List<String> lines = extractContextLines(messages);
		if (lines.isEmpty()) {
			return;
		}
		List<MemoryFactRecord> facts = memoryLlmExtractor.extractDurableFacts(userId, sessionId, "preflight-compact", lines);
		if (facts == null || facts.isEmpty()) {
			return;
		}
		Map<MemoryFactBucket, List<MemoryFactRecord>> grouped = new LinkedHashMap<>();
		for (MemoryFactRecord fact : facts) {
			if (fact == null || fact.getBucket() == null || fact.getFactKey() == null || fact.getFactKey().trim().isEmpty()) {
				continue;
			}
			grouped.computeIfAbsent(fact.getBucket(), ignored -> new ArrayList<>()).add(fact);
		}
		for (Map.Entry<MemoryFactBucket, List<MemoryFactRecord>> entry : grouped.entrySet()) {
			upsertBucketFacts(userId, sessionId, entry.getKey(), entry.getValue());
		}
	}

	private void upsertBucketFacts(String userId, String sessionId, MemoryFactBucket bucket, List<MemoryFactRecord> facts) {
		if (bucket == null || facts == null || facts.isEmpty()) {
			return;
		}
		Path file = memoryFileService.factsDir(userId).resolve(bucket.fileName());
		List<StoredFactBlock> blocks = loadBlocks(userId, file);
		boolean dirty = false;
		for (MemoryFactRecord fact : facts) {
			if (fact == null) {
				continue;
			}
			List<Integer> candidateIndexes = resolveCandidateIndexes(fact, blocks);
			List<MemoryFactRecord> candidateFacts = new ArrayList<>();
			for (Integer index : candidateIndexes) {
				if (index == null || index.intValue() < 0 || index.intValue() >= blocks.size()) {
					continue;
				}
				candidateFacts.add(blocks.get(index.intValue()).fact);
			}
			MemoryFactCompareResult compareResult = candidateFacts.isEmpty()
				? new MemoryFactCompareResult(MemoryFactCompareResult.Decision.ADD, -1)
				: memoryLlmExtractor.compareFact(userId, sessionId, fact, candidateFacts);
			if (compareResult == null || compareResult.getDecision() == MemoryFactCompareResult.Decision.NONE) {
				continue;
			}
			String now = OffsetDateTime.now().toString();
			if (compareResult.getDecision() == MemoryFactCompareResult.Decision.UPDATE
				&& compareResult.getMatchIndex() >= 0
				&& compareResult.getMatchIndex() < candidateIndexes.size()) {
				int targetIndex = candidateIndexes.get(compareResult.getMatchIndex()).intValue();
				if (targetIndex >= 0 && targetIndex < blocks.size()) {
					StoredFactBlock existing = blocks.get(targetIndex);
					blocks.set(targetIndex, buildStoredBlock(userId, sessionId, fact, now, existing.metadata));
					dirty = true;
					continue;
				}
			}
			if (compareResult.getDecision() == MemoryFactCompareResult.Decision.ADD
				|| compareResult.getDecision() == MemoryFactCompareResult.Decision.UPDATE) {
				blocks.add(buildStoredBlock(userId, sessionId, fact, now, null));
				dirty = true;
			}
		}
		if (dirty) {
			rewriteFile(file, blocks);
		}
	}

	private List<Integer> resolveCandidateIndexes(MemoryFactRecord fact, List<StoredFactBlock> blocks) {
		List<Integer> exact = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			StoredFactBlock block = blocks.get(i);
			if (block == null || block.metadata == null || block.fact == null) {
				continue;
			}
			if (fact.getFactKey().equals(block.metadata.getFactKey())) {
				exact.add(i);
				return exact;
			}
		}

		int limit = properties.getMaxFactCandidates() > 0 ? properties.getMaxFactCandidates() : 2;
		List<ScoredCandidate> scored = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			StoredFactBlock block = blocks.get(i);
			if (block == null || block.fact == null) {
				continue;
			}
			int score = scoreCandidate(fact, block.fact);
			if (score > 0) {
				scored.add(new ScoredCandidate(i, score));
			}
		}
		scored.sort(Comparator.comparingInt(ScoredCandidate::score).reversed().thenComparingInt(ScoredCandidate::index));
		List<Integer> out = new ArrayList<>();
		for (ScoredCandidate candidate : scored) {
			if (out.size() >= limit) {
				break;
			}
			out.add(candidate.index());
		}
		return out;
	}

	private int scoreCandidate(MemoryFactRecord incoming, MemoryFactRecord stored) {
		if (incoming == null || stored == null) {
			return 0;
		}
		int score = 0;
		if (incoming.getBucket() == stored.getBucket()) {
			score += 6;
		}
		if (normalizedEquals(incoming.getSubject(), stored.getSubject())) {
			score += 8;
		}
		if (normalizedEquals(incoming.getAttribute(), stored.getAttribute())) {
			score += 10;
		}
		if (!safe(incoming.getValue()).isEmpty() && normalizedEquals(incoming.getValue(), stored.getValue())) {
			score += 6;
		}
		score += overlappingTokens(incoming.getStatement(), stored.getStatement());
		return score;
	}

	private StoredFactBlock buildStoredBlock(
		String userId,
		String sessionId,
		MemoryFactRecord fact,
		String now,
		MemoryBlockMetadata existing
	) {
		MemoryBlockMetadata metadata = new MemoryBlockMetadata(
			MemoryBlockMetadata.SCHEMA_V2,
			MemoryBlockMetadata.KIND_FACT,
			existing != null && existing.getBlockId() != null ? existing.getBlockId() : UUID.randomUUID().toString(),
			SessionScope.normalizeUserId(userId),
			SessionScope.normalizeSessionId(sessionId),
			existing != null && existing.getCreatedAt() != null ? existing.getCreatedAt() : now,
			now,
			"preflight_compact",
			fact.getBucket() != null ? fact.getBucket().getValue() : null,
			fact.getFactKey(),
			null,
			null
		);
		return new StoredFactBlock(metadata, factMarkdownCodec.render(fact), fact);
	}

	private List<StoredFactBlock> loadBlocks(String userId, Path file) {
		List<StoredFactBlock> out = new ArrayList<>();
		for (ParsedMemoryBlock block : memoryBlockParser.parse(userId, file)) {
			if (block == null) {
				continue;
			}
			MemoryFactRecord fact = factMarkdownCodec.parse(block);
			out.add(new StoredFactBlock(block.getMetadata(), block.getContent(), fact));
		}
		return out;
	}

	private void rewriteFile(Path file, List<StoredFactBlock> blocks) {
		StringBuilder markdown = new StringBuilder();
		for (StoredFactBlock block : blocks) {
			if (block == null || block.metadata == null) {
				continue;
			}
			markdown.append(memoryBlockParser.renderBlock(block.metadata, block.body));
		}
		try {
			Files.createDirectories(file.getParent());
			Path tempFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			Files.writeString(
				tempFile,
				markdown.toString(),
				StandardCharsets.UTF_8,
				StandardOpenOption.TRUNCATE_EXISTING
			);
			moveAtomically(tempFile, file);
		} catch (IOException ignored) {
			// avoid blocking chat flow on durable memory write failure
		}
	}

	private void moveAtomically(Path tempFile, Path target) throws IOException {
		try {
			Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private List<String> extractContextLines(List<ChatMessage> messages) {
		List<String> lines = new ArrayList<>();
		for (ChatMessage message : messages) {
			if (message == null || message.getType() == null || message.getType() == ChatMessageType.SYSTEM) {
				continue;
			}
			String content = sanitize(message.getContent());
			if (content.isEmpty()) {
				continue;
			}
			lines.add(message.getType().name() + ": " + content);
		}
		return lines;
	}

	private String sanitize(String content) {
		if (content == null) {
			return "";
		}
		String normalized = WHITESPACE.matcher(content).replaceAll(" ").trim();
		int maxChars = properties.getFlushInputMaxChars() > 0 ? properties.getFlushInputMaxChars() : 6000;
		if (normalized.length() <= maxChars) {
			return normalized;
		}
		return normalized.substring(normalized.length() - maxChars);
	}

	private boolean normalizedEquals(String left, String right) {
		return normalize(left).equals(normalize(right));
	}

	private int overlappingTokens(String left, String right) {
		List<String> leftTokens = tokenize(left);
		List<String> rightTokens = tokenize(right);
		int score = 0;
		for (String token : leftTokens) {
			if (token.isEmpty()) {
				continue;
			}
			if (rightTokens.contains(token)) {
				score++;
			}
		}
		return score;
	}

	private List<String> tokenize(String text) {
		List<String> tokens = new ArrayList<>();
		for (String token : normalize(text).split(" ")) {
			if (!token.isEmpty()) {
				tokens.add(token);
			}
		}
		return tokens;
	}

	private String normalize(String text) {
		return WHITESPACE.matcher(safe(text).toLowerCase(Locale.ROOT)).replaceAll(" ");
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private record StoredFactBlock(MemoryBlockMetadata metadata, String body, MemoryFactRecord fact) {}

	private record ScoredCandidate(int index, int score) {}
}
