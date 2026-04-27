package com.agenticrag.app.loadtest;

import com.agenticrag.app.agent.AgentProperties;
import com.agenticrag.app.agent.AgentStreamingService;
import com.agenticrag.app.benchmark.execution.BenchmarkTurnExecutionSummaryService;
import com.agenticrag.app.chat.context.ContextManager;
import com.agenticrag.app.chat.context.SessionContextPreflightCompactor;
import com.agenticrag.app.chat.context.SessionContextProjector;
import com.agenticrag.app.chat.context.TurnContextAutoCompactor;
import com.agenticrag.app.chat.store.PersistentMessageStore;
import com.agenticrag.app.chat.store.SessionReplayStore;
import com.agenticrag.app.config.MinimaxClientProperties;
import com.agenticrag.app.config.OpenAiClientProperties;
import com.agenticrag.app.ingest.queue.DocumentParseQueue;
import com.agenticrag.app.ingest.queue.RedisListDocumentParseQueue;
import com.agenticrag.app.ingest.queue.ReservedJob;
import com.agenticrag.app.memory.MemoryLifecycleOrchestrator;
import com.agenticrag.app.prompt.SystemPromptManager;
import com.agenticrag.app.session.SessionManager;
import com.agenticrag.app.tool.ToolArgumentValidator;
import com.agenticrag.app.tool.ToolRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Load test configuration that replaces real LLM services with mock implementations.
 * Activate with: --spring.profiles.active=loadtest
 * <p>
 * Environment variables for tuning:
 * - LOADTEST_LLM_DELAY_MS: simulated LLM latency in ms (default 200)
 * - LOADTEST_LLM_STUCK: set to "true" to simulate LLM never returning (default false)
 * - LOADTEST_AGENT_TOOL_ROUNDS: number of mock tool rounds (default 1)
 * - LOADTEST_TOOL_MODE: "real" to call real tools, "fake" to just emit events (default "fake")
 * - LOADTEST_THREAD_POOL_SIZE: mock stream thread pool max size (default CPU*2)
 */
@Configuration
@Profile("loadtest")
public class LoadTestConfiguration {

	private static final Logger log = LoggerFactory.getLogger(LoadTestConfiguration.class);

	public LoadTestConfiguration() {
		long delayMs = Long.parseLong(System.getenv().getOrDefault("LOADTEST_LLM_DELAY_MS", "2000"));
		boolean stuck = Boolean.parseBoolean(System.getenv().getOrDefault("LOADTEST_LLM_STUCK", "false"));
		int toolRounds = Integer.parseInt(System.getenv().getOrDefault("LOADTEST_AGENT_TOOL_ROUNDS", "3"));
		String toolMode = System.getenv().getOrDefault("LOADTEST_TOOL_MODE", "fake");
		String streamMode = System.getenv().getOrDefault("LOADTEST_STREAM_MODE", "final_answer_only");
		log.info("event=loadtest_config delayMs={} stuck={} toolRounds={} toolMode={} streamMode={}",
			delayMs, stuck, toolRounds, toolMode, streamMode);
	}

	/**
	 * No-op DocumentParseQueue — 替代 Redis 实现，压测环境不需要 Redis。
	 */
	@Bean
	@Primary
	public DocumentParseQueue noopDocumentParseQueue() {
		log.info("event=loadtest_bean bean=NoOpDocumentParseQueue");
		return new DocumentParseQueue() {
			@Override public void enqueue(String jobId) {}
			@Override public ReservedJob reserve(Duration timeout) { return null; }
			@Override public void ack(ReservedJob job) {}
			@Override public void retry(ReservedJob job, Instant nextRetryAt) {}
			@Override public void deadLetter(ReservedJob job) {}
			@Override public int replayDueRetries(Instant now, int batchSize) { return 0; }
		};
	}

	@Bean
	@Primary
	public AgentStreamingService mockAgentStreamingService(
		OpenAIClient openAiClient,
		OpenAIClient minimaxClient,
		OpenAiClientProperties openAiProperties,
		MinimaxClientProperties minimaxProperties,
		ToolRouter toolRouter,
		ObjectMapper objectMapper,
		SystemPromptManager systemPromptManager,
		ContextManager contextManager,
		PersistentMessageStore persistentMessageStore,
		SessionReplayStore sessionReplayStore,
		com.agenticrag.app.chat.context.LocalExecutionContextRecorder localExecutionContextRecorder,
		AgentProperties agentProperties,
		ToolArgumentValidator toolArgumentValidator,
		SessionManager sessionManager,
		MemoryLifecycleOrchestrator memoryLifecycleOrchestrator,
		BenchmarkTurnExecutionSummaryService benchmarkTurnExecutionSummaryService,
		SessionContextProjector sessionContextProjector,
		SessionContextPreflightCompactor sessionContextPreflightCompactor,
		TurnContextAutoCompactor turnContextAutoCompactor,
		LoadTestMetricsCollector metricsCollector
	) {
		log.info("event=loadtest_bean bean=MockAgentStreamingService");

		int poolSize = Integer.parseInt(
			System.getenv().getOrDefault("LOADTEST_THREAD_POOL_SIZE",
				String.valueOf(Runtime.getRuntime().availableProcessors() * 2)));

		ExecutorService mockStreamExecutor = new ThreadPoolExecutor(
			poolSize,
			poolSize,
			60L, TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(200),
			new ThreadPoolExecutor.CallerRunsPolicy()
		);

		return new MockAgentStreamingService(
			openAiClient, minimaxClient, openAiProperties, minimaxProperties,
			toolRouter, objectMapper, systemPromptManager, contextManager,
			persistentMessageStore, sessionReplayStore, localExecutionContextRecorder,
			agentProperties, toolArgumentValidator, sessionManager,
			memoryLifecycleOrchestrator, benchmarkTurnExecutionSummaryService,
			sessionContextProjector, sessionContextPreflightCompactor,
			turnContextAutoCompactor, mockStreamExecutor, metricsCollector
		);
	}
}
