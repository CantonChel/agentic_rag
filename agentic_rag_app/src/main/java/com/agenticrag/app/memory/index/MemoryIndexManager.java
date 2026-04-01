package com.agenticrag.app.memory.index;

import com.agenticrag.app.memory.MemoryProperties;
import com.agenticrag.app.memory.index.repo.MemoryIndexMetaRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class MemoryIndexManager {
	private static final Logger log = LoggerFactory.getLogger(MemoryIndexManager.class);

	private final MemoryProperties properties;
	private final MemoryIndexScopeService scopeService;
	private final MemoryIndexSyncService syncService;
	private final MemoryIndexMetaRepository metaRepository;
	private final ExecutorService executor;
	private final ConcurrentMap<String, MemoryIndexScope> scopesByKey = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Boolean> pending = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, AtomicBoolean> running = new ConcurrentHashMap<>();

	public MemoryIndexManager(
		MemoryProperties properties,
		MemoryIndexScopeService scopeService,
		MemoryIndexSyncService syncService,
		MemoryIndexMetaRepository metaRepository
	) {
		this.properties = properties;
		this.scopeService = scopeService;
		this.syncService = syncService;
		this.metaRepository = metaRepository;
		this.executor = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable);
			thread.setName("memory-index-sync");
			thread.setDaemon(true);
			return thread;
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!properties.isEnabled() || !properties.isIndexStartupSyncEnabled()) {
			return;
		}
		markAllKnownScopesDirty();
		for (MemoryIndexScope scope : collectKnownScopes()) {
			requestSync(scope);
		}
	}

	public void markAllKnownScopesDirty() {
		Instant now = Instant.now();
		metaRepository.findAll().forEach(meta -> {
			meta.setDirty(true);
			meta.setUpdatedAt(now);
			metaRepository.save(meta);
		});
		for (MemoryIndexScope scope : collectKnownScopes()) {
			syncService.markDirty(scope, null);
		}
	}

	public void requestSync(MemoryIndexScope scope) {
		if (scope == null || !properties.isEnabled()) {
			return;
		}
		syncService.markDirty(scope, null);
		String key = scope.toKey();
		scopesByKey.put(key, scope);
		pending.put(key, Boolean.TRUE);
		executor.submit(() -> drainScope(scope));
	}

	public void requestUserScope(String userId) {
		requestSync(scopeService.userScope(userId));
	}

	public void requestGlobalScope() {
		requestSync(scopeService.globalScope());
	}

	private void drainScope(MemoryIndexScope scope) {
		String key = scope.toKey();
		AtomicBoolean flag = running.computeIfAbsent(key, ignored -> new AtomicBoolean(false));
		if (!flag.compareAndSet(false, true)) {
			return;
		}
		try {
			while (pending.remove(key) != null) {
				try {
					syncService.runSync(scope);
				} catch (Exception e) {
					syncService.markDirty(scope, e.getMessage());
					log.warn("event=memory_index_manager_sync_failed scope={} type={} message={}", key, e.getClass().getSimpleName(), e.getMessage());
				}
			}
		} finally {
			flag.set(false);
			if (pending.containsKey(key)) {
				executor.submit(() -> drainScope(scopesByKey.getOrDefault(key, scope)));
			}
		}
	}

	private Set<MemoryIndexScope> collectKnownScopes() {
		Set<MemoryIndexScope> scopes = new LinkedHashSet<>(scopeService.discoverScopesFromDisk());
		metaRepository.findAll().forEach(meta -> scopes.add(scopeService.fromStored(meta.getId().getScopeType(), meta.getId().getScopeId())));
		return scopes;
	}

	@PreDestroy
	public void shutdown() {
		executor.shutdownNow();
	}
}
