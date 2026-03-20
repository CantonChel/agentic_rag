package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DocumentParseWorker {
	private static final Logger log = LoggerFactory.getLogger(DocumentParseWorker.class);

	private final IngestAsyncProperties asyncProperties;
	private final DocumentParseQueue queue;
	private final DocumentParseDispatcher dispatcher;
	private final Executor workerExecutor;
	private final ExecutorService pollerExecutor;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public DocumentParseWorker(
		IngestAsyncProperties asyncProperties,
		DocumentParseQueue queue,
		DocumentParseDispatcher dispatcher,
		@Qualifier("docParseWorkerExecutor") Executor workerExecutor
	) {
		this.asyncProperties = asyncProperties;
		this.queue = queue;
		this.dispatcher = dispatcher;
		this.workerExecutor = workerExecutor;
		this.pollerExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "doc-parse-poller");
			t.setDaemon(true);
			return t;
		});
		startIfEnabled();
	}

	private void startIfEnabled() {
		if (!asyncProperties.isEnabled()) {
			return;
		}
		if (!running.compareAndSet(false, true)) {
			return;
		}
		pollerExecutor.submit(this::pollLoop);
		log.info("document parse worker started");
	}

	private void pollLoop() {
		Duration timeout = Duration.ofSeconds(Math.max(1, asyncProperties.getPollTimeoutSeconds()));
		while (running.get()) {
			try {
				ReservedJob job = queue.reserve(timeout);
				if (job == null) {
					continue;
				}
				workerExecutor.execute(() -> dispatcher.dispatch(job));
			} catch (Exception e) {
				log.warn("queue poll failed: {}", e.getMessage());
				sleep(1000L);
			}
		}
	}

	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	@PreDestroy
	public void stop() {
		running.set(false);
		pollerExecutor.shutdownNow();
	}
}
