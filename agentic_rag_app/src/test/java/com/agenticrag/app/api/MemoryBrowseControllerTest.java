package com.agenticrag.app.api;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.MemoryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class MemoryBrowseControllerTest {
	@TempDir
	Path tempDir;

	@Test
	void listFilesShouldIncludeGlobalAndUserFiles() throws Exception {
		Files.writeString(tempDir.resolve("MEMORY.md"), "global memory", StandardCharsets.UTF_8);
		Files.createDirectories(tempDir.resolve("memory/users/u1/facts"));
		Files.createDirectories(tempDir.resolve("memory/users/u1/summaries"));
		Files.createDirectories(tempDir.resolve("memory/users/u1/daily"));
		Files.writeString(tempDir.resolve("memory/users/u1/facts/project.reminder.md"), "facts", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u1/summaries/2026-03-23-note.md"), "summary", StandardCharsets.UTF_8);
		Files.writeString(tempDir.resolve("memory/users/u1/daily/2026-03-23.md"), "legacy daily", StandardCharsets.UTF_8);

		MemoryBrowseController controller = new MemoryBrowseController(new MemoryFileService(props(tempDir)));
		List<MemoryBrowseController.MemoryFileView> files = controller.listFiles("u1", true).block();

		Assertions.assertNotNull(files);
		Assertions.assertTrue(files.stream().anyMatch(f -> "global".equals(f.getKind())));
		Assertions.assertTrue(files.stream().anyMatch(f -> "fact".equals(f.getKind())));
		Assertions.assertTrue(files.stream().anyMatch(f -> "session_summary".equals(f.getKind())));
		Assertions.assertFalse(files.stream().anyMatch(f -> f.getPath().contains("/daily/")));
	}

	@Test
	void readFileShouldRejectAccessToOtherUserFiles() throws Exception {
		Files.createDirectories(tempDir.resolve("memory/users/u2"));
		Files.writeString(tempDir.resolve("memory/users/u2/secret.md"), "secret", StandardCharsets.UTF_8);

		MemoryBrowseController controller = new MemoryBrowseController(new MemoryFileService(props(tempDir)));
		ResponseStatusException e = Assertions.assertThrows(
			ResponseStatusException.class,
			() -> controller.readFile("u1", "memory/users/u2/secret.md").block()
		);
		Assertions.assertEquals(403, e.getStatus().value());
	}

	private MemoryProperties props(Path workspaceRoot) {
		MemoryProperties props = new MemoryProperties();
		props.setWorkspaceRoot(workspaceRoot.toString());
		props.setUserMemoryBaseDir("memory/users");
		return props;
	}
}
