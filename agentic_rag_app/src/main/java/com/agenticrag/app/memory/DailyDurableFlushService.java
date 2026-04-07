package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.ChatMessageType;
import com.agenticrag.app.memory.audit.MemoryFactOperationDecisionSource;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogService;
import com.agenticrag.app.memory.audit.MemoryFactOperationWriteOutcome;
import com.agenticrag.app.session.SessionScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
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
	private static final String FLUSH_REASON = "preflight-compact";
	private static final String FACT_TRIGGER = "preflight_compact";

	private final MemoryProperties properties;
	private final MemoryLlmExtractor memoryLlmExtractor;
	private final MemoryFileService memoryFileService;
	private final MemoryBlockParser memoryBlockParser;
	private final MemoryFactMarkdownCodec factMarkdownCodec;
	private final MemoryFactOperationLogService memoryFactOperationLogService;

	public DailyDurableFlushService(
		MemoryProperties properties,
		MemoryLlmExtractor memoryLlmExtractor,
		MemoryFileService memoryFileService,
		MemoryBlockParser memoryBlockParser,
		MemoryFactMarkdownCodec factMarkdownCodec,
		MemoryFactOperationLogService memoryFactOperationLogService
	) {
		this.properties = properties;
		this.memoryLlmExtractor = memoryLlmExtractor;
		this.memoryFileService = memoryFileService;
		this.memoryBlockParser = memoryBlockParser;
		this.factMarkdownCodec = factMarkdownCodec;
		this.memoryFactOperationLogService = memoryFactOperationLogService;
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
		List<MemoryFactRecord> facts = memoryLlmExtractor.extractDurableFacts(userId, sessionId, FLUSH_REASON, lines);
		if (facts == null || facts.isEmpty()) {
			return;
		}
		String flushId = UUID.randomUUID().toString();
		Map<MemoryFactBucket, List<MemoryFactRecord>> grouped = new LinkedHashMap<>();
		for (MemoryFactRecord fact : facts) {
			if (fact == null || fact.getBucket() == null || fact.getFactKey() == null || fact.getFactKey().trim().isEmpty()) {
				continue;
			}
			grouped.computeIfAbsent(fact.getBucket(), ignored -> new ArrayList<>()).add(fact);
		}
		for (Map.Entry<MemoryFactBucket, List<MemoryFactRecord>> entry : grouped.entrySet()) {
			upsertBucketFacts(flushId, userId, sessionId, entry.getKey(), entry.getValue());
		}
	}

	private void upsertBucketFacts(
		String flushId,
		String userId,
		String sessionId,
		MemoryFactBucket bucket,
		List<MemoryFactRecord> facts
	) {
		if (bucket == null || facts == null || facts.isEmpty()) {
			return;
		}
		Path file = memoryFileService.factsDir(userId).resolve(bucket.fileName());
		String filePath = memoryFileService.relPath(file);
		List<StoredFactBlock> blocks = loadBlocks(userId, file);
		boolean dirty = false;
		List<PendingAuditRecord> pendingAuditRecords = new ArrayList<>();
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
			MemoryFactOperationDecisionSource decisionSource = candidateFacts.isEmpty()
				? MemoryFactOperationDecisionSource.DIRECT_ADD_NO_CANDIDATES
				: MemoryFactOperationDecisionSource.LLM_COMPARE;
			MemoryFactCompareResult compareResult = candidateFacts.isEmpty()
				? new MemoryFactCompareResult(MemoryFactCompareResult.Decision.ADD, -1)
				: memoryLlmExtractor.compareFact(userId, sessionId, fact, candidateFacts);
			if (compareResult == null) {
				compareResult = new MemoryFactCompareResult(MemoryFactCompareResult.Decision.NONE, -1);
			}
			compareResult = reconcileCompareResult(fact, candidateIndexes, blocks, compareResult);
			MatchedCandidate matchedCandidate = resolveMatchedCandidate(compareResult, candidateIndexes, blocks);
			Instant createdAt = Instant.now();
			if (compareResult.getDecision() == MemoryFactCompareResult.Decision.NONE) {
				appendAuditRecord(
					flushId,
					userId,
					sessionId,
					filePath,
					bucket,
					compareResult.getDecision(),
					decisionSource,
					MemoryFactOperationWriteOutcome.SKIPPED_NONE,
					candidateFacts,
					matchedCandidate,
					null,
					fact,
					createdAt
				);
				continue;
			}
			String now = createdAt.toString();
			if (compareResult.getDecision() == MemoryFactCompareResult.Decision.UPDATE
				&& compareResult.getMatchIndex() >= 0
				&& compareResult.getMatchIndex() < candidateIndexes.size()) {
				int targetIndex = candidateIndexes.get(compareResult.getMatchIndex()).intValue();
				if (targetIndex >= 0 && targetIndex < blocks.size()) {
					StoredFactBlock existing = blocks.get(targetIndex);
					StoredFactBlock updated = buildStoredBlock(userId, sessionId, fact, now, existing.metadata);
					blocks.set(targetIndex, updated);
					removeDuplicateExactBlocks(fact, candidateIndexes, blocks, targetIndex);
					dirty = true;
					pendingAuditRecords.add(new PendingAuditRecord(
						createdAt,
						compareResult.getDecision(),
						decisionSource,
						candidateFacts,
						matchedCandidate != null ? matchedCandidate : new MatchedCandidate(targetIndex, existing),
						updated.metadata != null ? updated.metadata.getBlockId() : null,
						fact
					));
					continue;
				}
			}
			if (compareResult.getDecision() == MemoryFactCompareResult.Decision.ADD
				|| compareResult.getDecision() == MemoryFactCompareResult.Decision.UPDATE) {
				StoredFactBlock added = buildStoredBlock(userId, sessionId, fact, now, null);
				blocks.add(added);
				dirty = true;
				pendingAuditRecords.add(new PendingAuditRecord(
					createdAt,
					compareResult.getDecision(),
					decisionSource,
					candidateFacts,
					matchedCandidate,
					added.metadata != null ? added.metadata.getBlockId() : null,
					fact
				));
			}
		}
		if (!pendingAuditRecords.isEmpty()) {
			boolean applied = dirty && rewriteFile(file, blocks);
			MemoryFactOperationWriteOutcome writeOutcome = applied
				? MemoryFactOperationWriteOutcome.APPLIED
				: MemoryFactOperationWriteOutcome.WRITE_FAILED;
			for (PendingAuditRecord pending : pendingAuditRecords) {
				appendAuditRecord(
					flushId,
					userId,
					sessionId,
					filePath,
					bucket,
					pending.decision(),
					pending.decisionSource(),
					writeOutcome,
					pending.candidateFacts(),
					pending.matchedCandidate(),
					pending.targetBlockId(),
					pending.incomingFact(),
					pending.createdAt()
				);
			}
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
			}
		}
		if (!exact.isEmpty()) {
			return exact;
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

	protected boolean rewriteFile(Path file, List<StoredFactBlock> blocks) {
		return writeMarkdown(file, renderMarkdown(blocks));
	}

	private String renderMarkdown(List<StoredFactBlock> blocks) {
		StringBuilder markdown = new StringBuilder();
		for (StoredFactBlock block : blocks) {
			if (block == null || block.metadata == null) {
				continue;
			}
			markdown.append(memoryBlockParser.renderBlock(block.metadata, block.body));
		}
		return markdown.toString();
	}

	protected boolean writeMarkdown(Path file, String markdown) {
		try {
			Files.createDirectories(file.getParent());
			Path tempFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			Files.writeString(
				tempFile,
				markdown == null ? "" : markdown,
				StandardCharsets.UTF_8,
				StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
			moveAtomically(tempFile, file);
			return true;
		} catch (IOException ignored) {
			// avoid blocking chat flow on durable memory write failure
			return false;
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

	private MemoryFactCompareResult reconcileCompareResult(
		MemoryFactRecord incoming,
		List<Integer> candidateIndexes,
		List<StoredFactBlock> blocks,
		MemoryFactCompareResult compareResult
	) {
		if (incoming == null || candidateIndexes == null || candidateIndexes.isEmpty() || blocks == null || blocks.isEmpty()) {
			return compareResult;
		}
		List<Integer> exactIndexes = resolveExactFactKeyIndexes(incoming, candidateIndexes, blocks);
		if (exactIndexes.isEmpty()) {
			return compareResult;
		}
		int identicalIndex = findIdenticalExactCandidateIndex(incoming, exactIndexes, blocks);
		if (identicalIndex >= 0) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.NONE, identicalIndex);
		}
		if (compareResult == null) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, 0);
		}
		if (compareResult.getDecision() == MemoryFactCompareResult.Decision.ADD) {
			return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, 0);
		}
		if (compareResult.getDecision() == MemoryFactCompareResult.Decision.UPDATE
			&& compareResult.getMatchIndex() >= 0
			&& compareResult.getMatchIndex() < candidateIndexes.size()) {
			return compareResult;
		}
		return new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, 0);
	}

	private List<Integer> resolveExactFactKeyIndexes(
		MemoryFactRecord incoming,
		List<Integer> candidateIndexes,
		List<StoredFactBlock> blocks
	) {
		List<Integer> exactIndexes = new ArrayList<>();
		if (incoming == null || candidateIndexes == null || blocks == null) {
			return exactIndexes;
		}
		for (Integer candidateIndex : candidateIndexes) {
			if (candidateIndex == null || candidateIndex.intValue() < 0 || candidateIndex.intValue() >= blocks.size()) {
				continue;
			}
			StoredFactBlock block = blocks.get(candidateIndex.intValue());
			if (block == null || block.metadata == null) {
				continue;
			}
			if (safe(incoming.getFactKey()).equals(safe(block.metadata.getFactKey()))) {
				exactIndexes.add(candidateIndex.intValue());
			}
		}
		return exactIndexes;
	}

	private int findIdenticalExactCandidateIndex(
		MemoryFactRecord incoming,
		List<Integer> exactIndexes,
		List<StoredFactBlock> blocks
	) {
		if (incoming == null || exactIndexes == null || blocks == null) {
			return -1;
		}
		for (int i = 0; i < exactIndexes.size(); i++) {
			int blockIndex = exactIndexes.get(i).intValue();
			if (blockIndex < 0 || blockIndex >= blocks.size()) {
				continue;
			}
			StoredFactBlock block = blocks.get(blockIndex);
			if (block == null || block.fact == null) {
				continue;
			}
			if (isSameExactFact(incoming, block.fact)) {
				return i;
			}
		}
		return -1;
	}

	private boolean isSameExactFact(MemoryFactRecord incoming, MemoryFactRecord stored) {
		if (incoming == null || stored == null) {
			return false;
		}
		if (incoming.getBucket() != stored.getBucket()) {
			return false;
		}
		return normalizedEquals(incoming.getSubject(), stored.getSubject())
			&& normalizedEquals(incoming.getAttribute(), stored.getAttribute())
			&& normalizedEquals(incoming.getValue(), stored.getValue());
	}

	private void removeDuplicateExactBlocks(
		MemoryFactRecord incoming,
		List<Integer> candidateIndexes,
		List<StoredFactBlock> blocks,
		int keepIndex
	) {
		List<Integer> exactIndexes = resolveExactFactKeyIndexes(incoming, candidateIndexes, blocks);
		if (exactIndexes.size() <= 1) {
			return;
		}
		List<Integer> indexesToRemove = new ArrayList<>();
		for (Integer exactIndex : exactIndexes) {
			if (exactIndex == null) {
				continue;
			}
			int index = exactIndex.intValue();
			if (index == keepIndex) {
				continue;
			}
			indexesToRemove.add(index);
		}
		indexesToRemove.sort(Comparator.reverseOrder());
		for (Integer index : indexesToRemove) {
			if (index == null || index.intValue() < 0 || index.intValue() >= blocks.size()) {
				continue;
			}
			blocks.remove(index.intValue());
		}
	}

	private MatchedCandidate resolveMatchedCandidate(
		MemoryFactCompareResult compareResult,
		List<Integer> candidateIndexes,
		List<StoredFactBlock> blocks
	) {
		if (candidateIndexes == null || candidateIndexes.isEmpty() || blocks == null || blocks.isEmpty()) {
			return null;
		}
		int matchedCandidateIndex = -1;
		if (compareResult != null
			&& compareResult.getMatchIndex() >= 0
			&& compareResult.getMatchIndex() < candidateIndexes.size()) {
			matchedCandidateIndex = compareResult.getMatchIndex();
		} else if (candidateIndexes.size() == 1) {
			matchedCandidateIndex = 0;
		}
		if (matchedCandidateIndex < 0) {
			return null;
		}
		int blockIndex = candidateIndexes.get(matchedCandidateIndex).intValue();
		if (blockIndex < 0 || blockIndex >= blocks.size()) {
			return null;
		}
		return new MatchedCandidate(blockIndex, blocks.get(blockIndex));
	}

	private void appendAuditRecord(
		String flushId,
		String userId,
		String sessionId,
		String filePath,
		MemoryFactBucket bucket,
		MemoryFactCompareResult.Decision decision,
		MemoryFactOperationDecisionSource decisionSource,
		MemoryFactOperationWriteOutcome writeOutcome,
		List<MemoryFactRecord> candidateFacts,
		MatchedCandidate matchedCandidate,
		String targetBlockId,
		MemoryFactRecord incomingFact,
		Instant createdAt
	) {
		memoryFactOperationLogService.append(new MemoryFactOperationLogService.AppendRequest(
			flushId,
			userId,
			sessionId,
			FACT_TRIGGER,
			filePath,
			bucket,
			decision,
			decisionSource,
			writeOutcome,
			candidateFacts != null ? candidateFacts.size() : 0,
			matchedCandidate != null && matchedCandidate.block() != null && matchedCandidate.block().metadata != null
				? matchedCandidate.block().metadata.getBlockId()
				: null,
			targetBlockId,
			incomingFact,
			matchedCandidate != null && matchedCandidate.block() != null ? matchedCandidate.block().fact : null,
			candidateFacts,
			createdAt
		));
	}

	private record StoredFactBlock(MemoryBlockMetadata metadata, String body, MemoryFactRecord fact) {}

	private record ScoredCandidate(int index, int score) {}

	private record PendingAuditRecord(
		Instant createdAt,
		MemoryFactCompareResult.Decision decision,
		MemoryFactOperationDecisionSource decisionSource,
		List<MemoryFactRecord> candidateFacts,
		MatchedCandidate matchedCandidate,
		String targetBlockId,
		MemoryFactRecord incomingFact
	) {}

	private record MatchedCandidate(int blockIndex, StoredFactBlock block) {}
}
