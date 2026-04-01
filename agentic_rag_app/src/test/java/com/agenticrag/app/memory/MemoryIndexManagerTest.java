package com.agenticrag.app.memory;

import com.agenticrag.app.memory.index.MemoryIndexManager;
import com.agenticrag.app.memory.index.MemoryIndexScopeService;
import com.agenticrag.app.memory.index.MemoryIndexSyncService;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MemoryIndexManagerTest {
	@TempDir
	Path tempDir;

	@Test
	void drainsPendingRequestsWithoutConcurrentSyncForSameScope() throws Exception {
		MemoryIndexSyncService syncService = Mockito.mock(MemoryIndexSyncService.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		Mockito.when(metaRepository.findAll()).thenReturn(Collections.emptyList());
		MemoryIndexManager manager = newManager(syncService, metaRepository);
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(2);
		AtomicInteger running = new AtomicInteger();
		AtomicInteger maxRunning = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();

		Mockito.doAnswer(invocation -> {
			int current = running.incrementAndGet();
			maxRunning.updateAndGet(previous -> Math.max(previous, current));
			int call = calls.incrementAndGet();
			firstStarted.countDown();
			try {
				if (call == 1) {
					Assertions.assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
				}
			} finally {
				running.decrementAndGet();
				finished.countDown();
			}
			return null;
		}).when(syncService).runSync(Mockito.any());

		try {
			manager.requestUserScope("u1");
			Assertions.assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

			manager.requestUserScope("u1");
			Thread.sleep(80);

			Assertions.assertEquals(1, calls.get());
			releaseFirst.countDown();
			Assertions.assertTrue(finished.await(2, TimeUnit.SECONDS));
			Assertions.assertEquals(2, calls.get());
			Assertions.assertEquals(1, maxRunning.get());
		} finally {
			releaseFirst.countDown();
			manager.shutdown();
		}
	}

	@Test
	void marksScopeDirtyAgainWhenAsyncSyncFails() {
		MemoryIndexSyncService syncService = Mockito.mock(MemoryIndexSyncService.class);
		MemoryIndexMetaRepository metaRepository = Mockito.mock(MemoryIndexMetaRepository.class);
		Mockito.when(metaRepository.findAll()).thenReturn(Collections.emptyList());
		MemoryIndexManager manager = newManager(syncService, metaRepository);
		Mockito.doThrow(new IllegalStateException("boom")).when(syncService).runSync(Mockito.any());

		try {
			manager.requestUserScope("u2");

			Mockito.verify(syncService, Mockito.timeout(1000))
				.markDirty(Mockito.argThat(scope -> scope != null && "user".equals(scope.getTypeValue()) && "u2".equals(scope.getId())), Mockito.isNull());
			Mockito.verify(syncService, Mockito.timeout(1000))
				.markDirty(
					Mockito.argThat(scope -> scope != null && "user".equals(scope.getTypeValue()) && "u2".equals(scope.getId())),
					Mockito.argThat(message -> message != null && message.contains("boom"))
				);
		} finally {
			manager.shutdown();
		}
	}

	private MemoryIndexManager newManager(MemoryIndexSyncService syncService, MemoryIndexMetaRepository metaRepository) {
		MemoryProperties properties = new MemoryProperties();
		properties.setWorkspaceRoot(tempDir.toString());
		properties.setUserMemoryBaseDir("memory/users");
		properties.setIndexStartupSyncEnabled(false);
		MemoryFileService fileService = new MemoryFileService(properties);
		MemoryIndexScopeService scopeService = new MemoryIndexScopeService(fileService, new ObjectMapper());
		return new MemoryIndexManager(properties, scopeService, syncService, metaRepository);
	}
}
