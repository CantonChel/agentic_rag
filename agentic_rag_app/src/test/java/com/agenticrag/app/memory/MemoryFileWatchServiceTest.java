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
	void watchesExistingDailyFilesAndSchedulesUserScopeOnCreateDeleteAndGlobalModify() throws Exception {
		MemoryProperties properties = properties();
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		MemoryIndexManager manager = Mockito.mock(MemoryIndexManager.class);
		Files.createDirectories(tempDir.resolve("memory/users/u1/daily"));
		Files.writeString(tempDir.resolve("MEMORY.md"), "global", StandardCharsets.UTF_8);

		MemoryFileWatchService watcher = new MemoryFileWatchService(properties, fileService, scopeService, manager);

		Path daily = tempDir.resolve("memory/users/u1/daily/2026-04-01.md");
		Files.writeString(daily, "第一版", StandardCharsets.UTF_8);
		watcher.processPathEvent(daily, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Mockito.verify(manager, Mockito.atLeastOnce()).requestUserScope("u1");

		Thread.sleep(15);
		Files.deleteIfExists(daily);
		watcher.processPathEvent(daily, java.nio.file.StandardWatchEventKinds.ENTRY_DELETE);
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

		Path dailyDir = tempDir.resolve("memory/users/u2/daily");
		Files.createDirectories(dailyDir);
		watcher.processPathEvent(dailyDir, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Thread.sleep(15);
		Files.writeString(dailyDir.resolve("2026-04-01.md"), "新目录里的记忆", StandardCharsets.UTF_8);
		watcher.processPathEvent(dailyDir.resolve("2026-04-01.md"), java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
		Mockito.verify(manager, Mockito.atLeastOnce()).requestUserScope("u2");

		watcher.processOverflow();
		Mockito.verify(manager, Mockito.atLeastOnce()).markAllKnownScopesDirtyAndRequestSync();
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
