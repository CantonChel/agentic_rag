package com.agenticrag.app.memory;

import com.agenticrag.app.chat.message.AssistantMessage;
import com.agenticrag.app.chat.message.ChatMessage;
import com.agenticrag.app.chat.message.SystemMessage;
import com.agenticrag.app.chat.message.UserMessage;
import com.agenticrag.app.memory.audit.MemoryFactOperationDecisionSource;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogEntity;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogService;
import com.agenticrag.app.memory.audit.MemoryFactOperationWriteOutcome;
import com.agenticrag.app.memory.audit.repo.MemoryFactOperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DailyDurableFlushServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void flushWritesFactBlockToBucketFileAndCreatesDirectAddAudit() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		MemoryFactRecord fact = new MemoryFactRecord(
			MemoryFactBucket.USER_PREFERENCE,
			"user",
			"response_language",
			"中文",
			"用户偏好中文输出",
			keyFactory.build(MemoryFactBucket.USER_PREFERENCE, "user", "response_language")
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(fact));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		Path bucketFile = tempDir.resolve("memory/users/u1/facts/user.preference.md");
		Assertions.assertTrue(Files.exists(bucketFile));
		String content = Files.readString(bucketFile, StandardCharsets.UTF_8);
		Assertions.assertTrue(content.contains("\"kind\":\"fact\""));
		Assertions.assertTrue(content.contains("\"bucket\":\"user.preference\""));
		Assertions.assertTrue(content.contains("\"fact_key\":\"" + fact.getFactKey() + "\""));
		Assertions.assertTrue(content.contains("- statement: 用户偏好中文输出"));
		Assertions.assertTrue(content.contains("- attribute: response_language"));

		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.ADD, entity.getDecision());
		Assertions.assertEquals(MemoryFactOperationDecisionSource.DIRECT_ADD_NO_CANDIDATES, entity.getDecisionSource());
		Assertions.assertEquals(MemoryFactOperationWriteOutcome.APPLIED, entity.getWriteOutcome());
		Assertions.assertEquals(0, entity.getCandidateCount());
		Assertions.assertEquals("memory/users/u1/facts/user.preference.md", entity.getFilePath());
		Assertions.assertTrue(entity.getIncomingFactJson().contains("\"bucket\":\"user.preference\""));
		Assertions.assertEquals("[]", entity.getCandidateFactsJson());
	}

	@Test
	void flushUpdatesExistingFactWhenCompareReturnsUpdateAndLogsMatchedBlock() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		String factKey = keyFactory.build(MemoryFactBucket.PROJECT_DECISION, "release_plan", "deadline");
		Path bucketFile = tempDir.resolve("memory/users/u1/facts/project.decision.md");
		Files.createDirectories(bucketFile.getParent());
		Files.writeString(
			bucketFile,
			"<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\"b1\",\"user_id\":\"u1\",\"session_id\":\"s0\",\"created_at\":\"2026-04-06T00:00:00Z\",\"updated_at\":\"2026-04-06T00:00:00Z\",\"trigger\":\"preflight_compact\",\"bucket\":\"project.decision\",\"fact_key\":\""
				+ factKey
				+ "\"} -->\n"
				+ "- statement: 计划本周发布\n"
				+ "- subject: release_plan\n"
				+ "- attribute: deadline\n"
				+ "- value: 本周\n"
				+ "<!-- /MEMORY_BLOCK -->\n\n",
			StandardCharsets.UTF_8
		);

		MemoryFactRecord updatedFact = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_DECISION,
			"release_plan",
			"deadline",
			"下周一",
			"计划改为下周一发布",
			factKey
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(updatedFact));
		Mockito.when(extractor.compareFact(Mockito.eq("u1"), Mockito.eq("s1"), Mockito.eq(updatedFact), Mockito.anyList()))
			.thenReturn(new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, 0));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		String content = Files.readString(bucketFile, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, countOccurrences(content, "<!-- MEMORY_BLOCK "));
		Assertions.assertTrue(content.contains("\"block_id\":\"b1\""));
		Assertions.assertTrue(content.contains("计划改为下周一发布"));
		Assertions.assertTrue(content.contains("- value: 下周一"));
		Assertions.assertFalse(content.contains("- value: 本周"));

		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.UPDATE, entity.getDecision());
		Assertions.assertEquals(MemoryFactOperationDecisionSource.LLM_COMPARE, entity.getDecisionSource());
		Assertions.assertEquals(MemoryFactOperationWriteOutcome.APPLIED, entity.getWriteOutcome());
		Assertions.assertEquals("b1", entity.getMatchedBlockId());
		Assertions.assertEquals("b1", entity.getTargetBlockId());
		Assertions.assertTrue(entity.getMatchedFactJson().contains("计划本周发布"));
		Assertions.assertTrue(entity.getCandidateFactsJson().contains("计划本周发布"));
	}

	@Test
	void flushSkipsWriteWhenCompareReturnsNoneAndLogsSkippedNone() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		String factKey = keyFactory.build(MemoryFactBucket.PROJECT_REMINDER, "task", "next_step");
		Path bucketFile = tempDir.resolve("memory/users/u1/facts/project.reminder.md");
		Files.createDirectories(bucketFile.getParent());
		String existingContent =
			"<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\"b2\",\"user_id\":\"u1\",\"session_id\":\"s0\",\"created_at\":\"2026-04-06T00:00:00Z\",\"updated_at\":\"2026-04-06T00:00:00Z\",\"trigger\":\"preflight_compact\",\"bucket\":\"project.reminder\",\"fact_key\":\""
				+ factKey
				+ "\"} -->\n"
				+ "- statement: 下一步补测试\n"
				+ "- subject: task\n"
				+ "- attribute: next_step\n"
				+ "- value: 补测试\n"
				+ "<!-- /MEMORY_BLOCK -->\n\n";
		Files.writeString(bucketFile, existingContent, StandardCharsets.UTF_8);

		MemoryFactRecord sameFact = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_REMINDER,
			"task",
			"next_step",
			"补测试",
			"下一步补测试",
			factKey
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(sameFact));
		Mockito.when(extractor.compareFact(Mockito.eq("u1"), Mockito.eq("s1"), Mockito.eq(sameFact), Mockito.anyList()))
			.thenReturn(new MemoryFactCompareResult(MemoryFactCompareResult.Decision.NONE, -1));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		Assertions.assertEquals(existingContent, Files.readString(bucketFile, StandardCharsets.UTF_8));

		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.NONE, entity.getDecision());
		Assertions.assertEquals(MemoryFactOperationWriteOutcome.SKIPPED_NONE, entity.getWriteOutcome());
		Assertions.assertEquals("b2", entity.getMatchedBlockId());
		Assertions.assertNull(entity.getTargetBlockId());
		Assertions.assertTrue(entity.getMatchedFactJson().contains("下一步补测试"));
	}

	@Test
	void flushLogsWriteFailedWhenRewriteFails() {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		MemoryFactRecord fact = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_POLICY,
			"delivery",
			"mode",
			"incremental",
			"项目采用增量交付",
			keyFactory.build(MemoryFactBucket.PROJECT_POLICY, "delivery", "mode")
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(fact));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		) {
			@Override
			protected boolean writeMarkdown(Path file, String markdown) {
				return false;
			}
		};
		service.flush("u1::s1", sampleMessages());

		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.ADD, entity.getDecision());
		Assertions.assertEquals(MemoryFactOperationWriteOutcome.WRITE_FAILED, entity.getWriteOutcome());
		Assertions.assertNotNull(entity.getTargetBlockId());
		Assertions.assertFalse(Files.exists(tempDir.resolve("memory/users/u1/facts/project.policy.md")));
	}

	@Test
	void flushUsesSameFlushIdAcrossMultipleFactsInSameRound() {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		MemoryFactRecord fact1 = new MemoryFactRecord(
			MemoryFactBucket.USER_PREFERENCE,
			"user",
			"response_language",
			"中文",
			"用户偏好中文输出",
			keyFactory.build(MemoryFactBucket.USER_PREFERENCE, "user", "response_language")
		);
		MemoryFactRecord fact2 = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_REMINDER,
			"task",
			"next_step",
			"补充联调",
			"下一步补充联调",
			keyFactory.build(MemoryFactBucket.PROJECT_REMINDER, "task", "next_step")
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(fact1, fact2));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		ArgumentCaptor<MemoryFactOperationLogEntity> captor = ArgumentCaptor.forClass(MemoryFactOperationLogEntity.class);
		Mockito.verify(logRepository, Mockito.times(2)).save(captor.capture());
		List<String> flushIds = captor.getAllValues().stream()
			.map(MemoryFactOperationLogEntity::getFlushId)
			.distinct()
			.collect(Collectors.toList());
		Assertions.assertEquals(1, flushIds.size());
	}

	@Test
	void flushTreatsIdenticalExactFactAsNoneWhenCompareReturnsAdd() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		String factKey = keyFactory.build(MemoryFactBucket.PROJECT_DECISION, "发布计划", "截止日期");
		Path bucketFile = tempDir.resolve("memory/users/u1/facts/project.decision.md");
		Files.createDirectories(bucketFile.getParent());
		String existingContent =
			"<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\"b1\",\"user_id\":\"u1\",\"session_id\":\"s0\",\"created_at\":\"2026-04-06T00:00:00Z\",\"updated_at\":\"2026-04-06T00:00:00Z\",\"trigger\":\"preflight_compact\",\"bucket\":\"project.decision\",\"fact_key\":\""
				+ factKey
				+ "\"} -->\n"
				+ "- statement: 发布计划的截止日期是本周五\n"
				+ "- subject: 发布计划\n"
				+ "- attribute: 截止日期\n"
				+ "- value: 本周五\n"
				+ "<!-- /MEMORY_BLOCK -->\n\n";
		Files.writeString(bucketFile, existingContent, StandardCharsets.UTF_8);

		MemoryFactRecord sameFact = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_DECISION,
			"发布计划",
			"截止日期",
			"本周五",
			"发布计划的截止日期是本周五",
			factKey
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(sameFact));
		Mockito.when(extractor.compareFact(Mockito.eq("u1"), Mockito.eq("s1"), Mockito.eq(sameFact), Mockito.anyList()))
			.thenReturn(new MemoryFactCompareResult(MemoryFactCompareResult.Decision.ADD, -1));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		Assertions.assertEquals(existingContent, Files.readString(bucketFile, StandardCharsets.UTF_8));
		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.NONE, entity.getDecision());
		Assertions.assertEquals(MemoryFactOperationWriteOutcome.SKIPPED_NONE, entity.getWriteOutcome());
		Assertions.assertEquals("b1", entity.getMatchedBlockId());
		Assertions.assertNull(entity.getTargetBlockId());
	}

	@Test
	void flushUpdateRemovesDuplicateExactFactBlocks() throws Exception {
		MemoryProperties props = props(tempDir);
		MemoryFileService fileService = new MemoryFileService(props);
		MemoryBlockParser blockParser = new MemoryBlockParser(fileService, new ObjectMapper());
		MemoryFactMarkdownCodec factCodec = new MemoryFactMarkdownCodec();
		MemoryLlmExtractor extractor = Mockito.mock(MemoryLlmExtractor.class);
		MemoryFactOperationLogRepository logRepository = auditRepository();
		MemoryFactOperationLogService logService = auditService(logRepository);
		MemoryFactKeyFactory keyFactory = new MemoryFactKeyFactory();
		String factKey = keyFactory.build(MemoryFactBucket.PROJECT_DECISION, "发布计划", "截止日期");
		Path bucketFile = tempDir.resolve("memory/users/u1/facts/project.decision.md");
		Files.createDirectories(bucketFile.getParent());
		Files.writeString(
			bucketFile,
			"<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\"b1\",\"user_id\":\"u1\",\"session_id\":\"s0\",\"created_at\":\"2026-04-06T00:00:00Z\",\"updated_at\":\"2026-04-06T00:00:00Z\",\"trigger\":\"preflight_compact\",\"bucket\":\"project.decision\",\"fact_key\":\""
				+ factKey
				+ "\"} -->\n"
				+ "- statement: 发布计划的截止日期是本周五\n"
				+ "- subject: 发布计划\n"
				+ "- attribute: 截止日期\n"
				+ "- value: 本周五\n"
				+ "<!-- /MEMORY_BLOCK -->\n\n"
				+ "<!-- MEMORY_BLOCK {\"schema\":\"memory.v2\",\"kind\":\"fact\",\"block_id\":\"b2\",\"user_id\":\"u1\",\"session_id\":\"s0\",\"created_at\":\"2026-04-06T00:01:00Z\",\"updated_at\":\"2026-04-06T00:01:00Z\",\"trigger\":\"preflight_compact\",\"bucket\":\"project.decision\",\"fact_key\":\""
				+ factKey
				+ "\"} -->\n"
				+ "- statement: 发布计划的截止日期是本周五\n"
				+ "- subject: 发布计划\n"
				+ "- attribute: 截止日期\n"
				+ "- value: 本周五\n"
				+ "<!-- /MEMORY_BLOCK -->\n\n",
			StandardCharsets.UTF_8
		);

		MemoryFactRecord updatedFact = new MemoryFactRecord(
			MemoryFactBucket.PROJECT_DECISION,
			"发布计划",
			"截止日期",
			"下周一",
			"发布计划的截止日期改为下周一",
			factKey
		);
		Mockito.when(extractor.extractDurableFacts(
			Mockito.eq("u1"),
			Mockito.eq("s1"),
			Mockito.eq("preflight-compact"),
			Mockito.anyList()
		)).thenReturn(List.of(updatedFact));
		Mockito.when(extractor.compareFact(Mockito.eq("u1"), Mockito.eq("s1"), Mockito.eq(updatedFact), Mockito.anyList()))
			.thenReturn(new MemoryFactCompareResult(MemoryFactCompareResult.Decision.UPDATE, 0));

		DailyDurableFlushService service = new DailyDurableFlushService(
			props,
			extractor,
			fileService,
			blockParser,
			factCodec,
			logService
		);
		service.flush("u1::s1", sampleMessages());

		String content = Files.readString(bucketFile, StandardCharsets.UTF_8);
		Assertions.assertEquals(1, countOccurrences(content, "<!-- MEMORY_BLOCK "));
		Assertions.assertTrue(content.contains("\"block_id\":\"b1\""));
		Assertions.assertFalse(content.contains("\"block_id\":\"b2\""));
		Assertions.assertTrue(content.contains("- value: 下周一"));
		MemoryFactOperationLogEntity entity = captureSingleAudit(logRepository);
		Assertions.assertEquals(MemoryFactCompareResult.Decision.UPDATE, entity.getDecision());
		Assertions.assertEquals("b1", entity.getMatchedBlockId());
		Assertions.assertEquals("b1", entity.getTargetBlockId());
	}

	private List<ChatMessage> sampleMessages() {
		return Arrays.asList(
			new SystemMessage("system"),
			new UserMessage("用户提到了一个长期偏好"),
			new AssistantMessage("我会记住这条 durable fact")
		);
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}

	private MemoryFactOperationLogRepository auditRepository() {
		MemoryFactOperationLogRepository repository = Mockito.mock(MemoryFactOperationLogRepository.class);
		Mockito.when(repository.save(Mockito.any(MemoryFactOperationLogEntity.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));
		return repository;
	}

	private MemoryFactOperationLogService auditService(MemoryFactOperationLogRepository repository) {
		return new MemoryFactOperationLogService(repository, new ObjectMapper());
	}

	private MemoryFactOperationLogEntity captureSingleAudit(MemoryFactOperationLogRepository repository) {
		ArgumentCaptor<MemoryFactOperationLogEntity> captor = ArgumentCaptor.forClass(MemoryFactOperationLogEntity.class);
		Mockito.verify(repository).save(captor.capture());
		return captor.getValue();
	}

	private int countOccurrences(String text, String needle) {
		int count = 0;
		int from = 0;
		while (true) {
			int idx = text.indexOf(needle, from);
			if (idx < 0) {
				return count;
			}
			count++;
			from = idx + needle.length();
		}
	}
}
