package com.agenticrag.app.api;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.MemoryFactCompareResult;
import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.memory.audit.MemoryFactOperationDecisionSource;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogEntity;
import com.agenticrag.app.memory.audit.MemoryFactOperationLogService;
import com.agenticrag.app.memory.audit.MemoryFactOperationWriteOutcome;
import com.agenticrag.app.memory.audit.repo.MemoryFactOperationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

class MemoryFactOperationControllerTest {
	@TempDir
	Path tempDir;

	@Test
	void factOperationsShouldReturnStructuredAuditForAllowedFactFile() throws Exception {
		Path factFile = tempDir.resolve("memory/users/u1/facts/project.policy.md");
		Files.createDirectories(factFile.getParent());
		Files.writeString(factFile, "fact", StandardCharsets.UTF_8);

		MemoryFactOperationLogEntity entity = new MemoryFactOperationLogEntity();
		entity.setFlushId("flush-1");
		entity.setUserId("u1");
		entity.setSessionId("s1");
		entity.setTrigger("preflight_compact");
		entity.setFilePath("memory/users/u1/facts/project.policy.md");
		entity.setBucket("project.policy");
		entity.setDecision(MemoryFactCompareResult.Decision.ADD);
		entity.setDecisionSource(MemoryFactOperationDecisionSource.DIRECT_ADD_NO_CANDIDATES);
		entity.setWriteOutcome(MemoryFactOperationWriteOutcome.APPLIED);
		entity.setCandidateCount(0);
		entity.setIncomingFactJson("{\"bucket\":\"project.policy\",\"subject\":\"delivery\",\"attribute\":\"mode\",\"value\":\"incremental\",\"statement\":\"项目采用增量交付\"}");
		entity.setCandidateFactsJson("[]");
		entity.setCreatedAt(Instant.parse("2026-04-07T10:00:00Z"));

		MemoryFactOperationLogRepository repository = Mockito.mock(MemoryFactOperationLogRepository.class);
		Mockito.when(repository.findByUserIdAndFilePathOrderByCreatedAtDescIdDesc(
			"u1",
			"memory/users/u1/facts/project.policy.md"
		)).thenReturn(List.of(entity));

		MemoryFactOperationController controller = new MemoryFactOperationController(
			new MemoryFileService(props(tempDir)),
			new MemoryFactOperationLogService(repository, new ObjectMapper())
		);
		List<MemoryFactOperationLogService.FactOperationView> views = controller
			.factOperations("u1", "memory/users/u1/facts/project.policy.md", 20)
			.block();

		Assertions.assertNotNull(views);
		Assertions.assertEquals(1, views.size());
		Assertions.assertEquals("flush-1", views.get(0).getFlushId());
		Assertions.assertEquals("ADD", views.get(0).getDecision());
		Assertions.assertEquals("project.policy", views.get(0).getBucket());
		Assertions.assertEquals("delivery", views.get(0).getIncomingFact().get("subject"));
	}

	@Test
	void factOperationsShouldRejectOtherUserFactFile() throws Exception {
		Path factFile = tempDir.resolve("memory/users/u2/facts/project.policy.md");
		Files.createDirectories(factFile.getParent());
		Files.writeString(factFile, "fact", StandardCharsets.UTF_8);

		MemoryFactOperationController controller = new MemoryFactOperationController(
			new MemoryFileService(props(tempDir)),
			new MemoryFactOperationLogService(Mockito.mock(MemoryFactOperationLogRepository.class), new ObjectMapper())
		);

		ResponseStatusException error = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> controller.factOperations("u1", "memory/users/u2/facts/project.policy.md", 20).block()
		);
		Assertions.assertEquals(403, error.getStatus().value());
	}

	@Test
	void factOperationsShouldRejectNonFactFile() throws Exception {
		Path summaryFile = tempDir.resolve("memory/users/u1/summaries/demo.md");
		Files.createDirectories(summaryFile.getParent());
		Files.writeString(summaryFile, "summary", StandardCharsets.UTF_8);

		MemoryFactOperationController controller = new MemoryFactOperationController(
			new MemoryFileService(props(tempDir)),
			new MemoryFactOperationLogService(Mockito.mock(MemoryFactOperationLogRepository.class), new ObjectMapper())
		);

		ResponseStatusException error = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> controller.factOperations("u1", "memory/users/u1/summaries/demo.md", 20).block()
		);
		Assertions.assertEquals(400, error.getStatus().value());
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}
}
