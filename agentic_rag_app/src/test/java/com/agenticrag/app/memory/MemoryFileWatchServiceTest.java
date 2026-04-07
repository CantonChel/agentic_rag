package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryFileWatchService;
import com.agenticrag.app.memory.index.MemoryIndexManager;
import com.agenticrag.app.memory.index.MemoryIndexScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MemoryFileWatchServiceTest {
	@TempDir
	Path tempDir;

	@Test
	void watchesExistingFactFilesAndSchedulesUserScopeOnCreateDeleteAndGlobalModify() throws Exception {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexManager manager = Mockito.mock(MemoryIndexManager.class);
		Files.createDirectories(tempDir.resolve("memory/users/u1/facts"));
		Files.writeString(tempDir.resolve("MEMORY.md"), "global", StandardCharsets.UTF_8);

		MemoryFileWatchService watcher = new MemoryFileWatchService(properties, fileService, scopeService, manager);

		Path factFile = tempDir.resolve("memory/users/u1/facts/project.reminder.md");
		Files.writeString(factFile, "第一版", StandardCharsets.UTF_8);
		watcher.processPathEvent(factFile, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Mockito.verify(manager, Mockito.atLeastOnce()).requestUserScope("u1");

		Thread.sleep(15);
		Files.deleteIfExists(factFile);
		watcher.processPathEvent(factFile, java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
		Mockito.verify(manager, Mockito.atLeast(2)).requestUserScope("u1");

		Files.writeString(tempDir.resolve("MEMORY.md"), "global updated", StandardCharsets.UTF_8);
		watcher.processPathEvent(tempDir.resolve("MEMORY.md"), java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
		Mockito.verify(manager, Mockito.atLeastOnce()).requestGlobalScope();
	}

	@Test
	void registersNewDirectoriesAndHandlesOverflow() throws Exception {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexManager manager = Mockito.mock(MemoryIndexManager.class);

		MemoryFileWatchService watcher = new MemoryFileWatchService(properties, fileService, scopeService, manager);

		Path factsDir = tempDir.resolve("memory/users/u2/facts");
		Files.createDirectories(factsDir);
		watcher.processPathEvent(factsDir, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Thread.sleep(15);
		Files.writeString(factsDir.resolve("project.reminder.md"), "新目录里的记忆", StandardCharsets.UTF_8);
		watcher.processPathEvent(factsDir.resolve("project.reminder.md"), java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Mockito.verify(manager, Mockito.atLeastOnce()).requestUserScope("u2");

		watcher.processOverflow();
		Mockito.verify(manager, Mockito.atLeastOnce()).markAllKnownScopesDirtyAndRequestSync();
	}

	@Test
	void ignoresCacheFilesAndNonMarkdownChanges() throws Exception {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexManager manager = Mockito.mock(MemoryIndexManager.class);

		MemoryFileWatchService watcher = new MemoryFileWatchService(properties, fileService, scopeService, manager);

		Path cacheFile = tempDir.resolve("memory/.cache/embeddings/u1.json");
		Files.createDirectories(cacheFile.getParent());
		Files.writeString(cacheFile, "{}", StandardCharsets.UTF_8);
		watcher.processPathEvent(cacheFile, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);

		Path textFile = tempDir.resolve("memory/users/u1/facts/notes.txt");
		Files.createDirectories(textFile.getParent());
		Files.writeString(textFile, "不是 markdown", StandardCharsets.UTF_8);
		watcher.processPathEvent(textFile, java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);

		Mockito.verifyNoInteractions(manager);
	}

	private MemoryProperties properties() {
		MemoryProperties properties = new MemoryProperties();
		properties.setWorkspaceRoot(tempDir.toString());
		properties.setUserMemoryBaseDir("memory/users");
		properties.setWatcherEnabled(true);
		properties.setWatcherDebounceMs(1);
		properties.setIndexStartupSyncEnabled(false);
		return properties;
	}
}
