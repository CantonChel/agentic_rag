package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryFileService;
import com.agenticrag.app.memory.MemoryProperties;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class MemoryFileWatchService {
	private static final Logger log = LoggerFactory.getLogger(MemoryFileWatchService.class);

	private final MemoryProperties properties;
	private final MemoryFileService memoryFileService;
	private final MemoryIndexScopeService scopeService;
	private final MemoryIndexManager indexManager;
	private final Map<WatchKey, Path> watchedDirectories = new ConcurrentHashMap<>();
	private final Map<String, Long> lastScheduledAtByScope = new ConcurrentHashMap<>();
	private final AtomicBoolean running = new AtomicBoolean(false);

	private WatchService watchService;
	private ExecutorService executor;

	public MemoryFileWatchService(
		MemoryProperties properties,
		MemoryFileService memoryFileService,
		MemoryIndexScopeService scopeService,
		MemoryIndexManager indexManager
	) {
		this.properties = properties;
		this.memoryFileService = memoryFileService;
		this.scopeService = scopeService;
		this.indexManager = indexManager;
	}

	@PostConstruct
	public synchronized void start() {
		if (!properties.isEnabled() || !properties.isWatcherEnabled() || running.get()) {
			return;
		}
		try {
			this.watchService = FileSystems.getDefault().newWatchService();
			this.executor = Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable);
				thread.setName("memory-file-watch");
				thread.setDaemon(true);
				return thread;
			});
			registerInitialDirectories();
			running.set(true);
			executor.submit(this::watchLoop);
		} catch (IOException e) {
			log.warn("event=memory_watch_start_failed type={} message={}", e.getClass().getSimpleName(), e.getMessage());
			stop();
		}
	}

	public void processOverflow() {
		indexManager.markAllKnownScopesDirtyAndRequestSync();
	}

	public void processPathEvent(Path path, WatchEvent.Kind<?> kind) {
		if (path == null || kind == null) {
			return;
		}
		if (StandardWatchEventKinds.ENTRY_CREATE.equals(kind) && Files.isDirectory(path)) {
			registerRecursively(path);
			scheduleScope(path);
			return;
		}
		if (isIgnored(path)) {
			return;
		}
		if (Files.isDirectory(path) && !StandardWatchEventKinds.ENTRY_DELETE.equals(kind)) {
			return;
		}
		if (!isRelevantMarkdownEvent(path, kind)) {
			return;
		}
		scheduleScope(path);
	}

	private void watchLoop() {
		while (running.get()) {
			try {
				WatchKey key = watchService.take();
				Path directory = watchedDirectories.get(key);
				if (directory == null) {
					key.reset();
					continue;
				}
				for (WatchEvent<?> rawEvent : key.pollEvents()) {
					WatchEvent.Kind<?> kind = rawEvent.kind();
					if (StandardWatchEventKinds.OVERFLOW.equals(kind)) {
						processOverflow();
						continue;
					}
					Object context = rawEvent.context();
					if (!(context instanceof Path)) {
						continue;
					}
					Path affected = directory.resolve((Path) context).normalize();
					processPathEvent(affected, kind);
				}
				boolean valid = key.reset();
				if (!valid) {
					watchedDirectories.remove(key);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.warn("event=memory_watch_loop_error type={} message={}", e.getClass().getSimpleName(), e.getMessage());
			}
		}
	}

	private void registerInitialDirectories() throws IOException {
		registerDirectory(memoryFileService.workspaceRoot());
		Path memoryRoot = scopeService.memoryRootDir();
		if (memoryRoot != null && Files.exists(memoryRoot)) {
			registerDirectory(memoryRoot);
		}
		Path usersBase = scopeService.usersBaseDir();
		if (usersBase != null && Files.exists(usersBase)) {
			registerRecursively(usersBase);
		}
	}

	private void registerRecursively(Path root) {
		if (root == null || !Files.exists(root) || !Files.isDirectory(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isDirectory).forEach(this::registerDirectory);
		} catch (IOException e) {
			log.warn("event=memory_watch_register_recursive_failed path={} message={}", root, e.getMessage());
		}
	}

	private void registerDirectory(Path directory) {
		if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory) || watchService == null) {
			return;
		}
		try {
			WatchKey key = directory.register(
				watchService,
				StandardWatchEventKinds.ENTRY_CREATE,
				StandardWatchEventKinds.ENTRY_MODIFY,
				StandardWatchEventKinds.ENTRY_DELETE
			);
			watchedDirectories.put(key, directory);
		} catch (IOException e) {
			log.warn("event=memory_watch_register_failed path={} message={}", directory, e.getMessage());
		}
	}

	private boolean isIgnored(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path cacheDir = memoryFileService.embeddingCacheDir().toAbsolutePath().normalize();
		return absolute.startsWith(cacheDir);
	}

	private boolean isRelevantMarkdownEvent(Path path, WatchEvent.Kind<?> kind) {
		Path absolute = path.toAbsolutePath().normalize();
		Path global = memoryFileService.globalMemoryFile().toAbsolutePath().normalize();
		if (absolute.equals(global)) {
			return true;
		}
		String name = absolute.getFileName() != null ? absolute.getFileName().toString().toLowerCase() : "";
		if (StandardWatchEventKinds.ENTRY_DELETE.equals(kind) && Files.notExists(absolute)) {
			return name.endsWith(".md") || scopeService.resolveScopeForPath(absolute) != null;
		}
		return name.endsWith(".md");
	}

	private void scheduleScope(Path path) {
		MemoryIndexScope scope = scopeService.resolveScopeForPath(path);
		if (scope == null) {
			return;
		}
		String key = scope.toKey();
		long now = System.currentTimeMillis();
		long previous = lastScheduledAtByScope.getOrDefault(key, 0L);
		int debounceMs = properties.getWatcherDebounceMs() > 0 ? properties.getWatcherDebounceMs() : 300;
		if (now - previous < debounceMs) {
			return;
		}
		lastScheduledAtByScope.put(key, now);
		if (scope.getType() == MemoryIndexScopeType.GLOBAL) {
			indexManager.requestGlobalScope();
		} else {
			indexManager.requestUserScope(scope.getId());
		}
	}

	@PreDestroy
	public synchronized void stop() {
		running.set(false);
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		if (watchService != null) {
			try {
				watchService.close();
			} catch (IOException ignored) {
			}
			watchService = null;
		}
		watchedDirectories.clear();
	}
}
