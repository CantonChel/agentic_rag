package com.agenticrag.app.loadtest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("loadtest")
public class LoadTestMetricsCollector {

	private final LongAdder submissions = new LongAdder();
	private final LongAdder started = new LongAdder();
	private final LongAdder completed = new LongAdder();
	private final LongAdder failed = new LongAdder();
	private final LongAdder inflight = new LongAdder();

	private final AtomicLong peakInflight = new AtomicLong();

	private final LongAdder queueWaitMsTotal = new LongAdder();
	private final AtomicLong queueWaitMsMax = new AtomicLong();

	private final LongAdder executionMsTotal = new LongAdder();
	private final AtomicLong executionMsMax = new AtomicLong();

	private final LongAdder firstEventLatencyMsTotal = new LongAdder();
	private final AtomicLong firstEventLatencyMsMax = new AtomicLong();

	private final LongAdder firstDeltaLatencyMsTotal = new LongAdder();
	private final AtomicLong firstDeltaLatencyMsMax = new AtomicLong();

	private final LongAdder llmThinkingMsTotal = new LongAdder();
	private final AtomicLong llmThinkingMsMax = new AtomicLong();

	private final LongAdder toolDurationMsTotal = new LongAdder();
	private final AtomicLong toolDurationMsMax = new AtomicLong();

	private final LongAdder toolCalls = new LongAdder();
	private final LongAdder turns = new LongAdder();
	private final LongAdder rounds = new LongAdder();
	private final LongAdder emittedEvents = new LongAdder();

	public void recordSubmission() {
		submissions.increment();
	}

	public void recordStart(long queueWaitMs) {
		started.increment();
		inflight.increment();
		queueWaitMsTotal.add(queueWaitMs);
		updateMax(queueWaitMsMax, queueWaitMs);
		updatePeakInflight();
	}

	public void recordCompletion(
		long executionMs,
		long firstEventLatencyMs,
		long firstDeltaLatencyMs,
		int roundCount,
		int toolCallCount,
		int emittedEventCount,
		boolean success
	) {
		inflight.decrement();
		completed.increment();
		if (!success) {
			failed.increment();
		}
		turns.increment();
		rounds.add(Math.max(roundCount, 0));
		toolCalls.add(Math.max(toolCallCount, 0));
		emittedEvents.add(Math.max(emittedEventCount, 0));
		executionMsTotal.add(executionMs);
		updateMax(executionMsMax, executionMs);
		if (firstEventLatencyMs >= 0) {
			firstEventLatencyMsTotal.add(firstEventLatencyMs);
			updateMax(firstEventLatencyMsMax, firstEventLatencyMs);
		}
		if (firstDeltaLatencyMs >= 0) {
			firstDeltaLatencyMsTotal.add(firstDeltaLatencyMs);
			updateMax(firstDeltaLatencyMsMax, firstDeltaLatencyMs);
		}
	}

	public void recordToolDuration(long durationMs) {
		toolDurationMsTotal.add(durationMs);
		updateMax(toolDurationMsMax, durationMs);
	}

	public void recordThinkingDuration(long durationMs) {
		llmThinkingMsTotal.add(durationMs);
		updateMax(llmThinkingMsMax, durationMs);
	}

	public synchronized Map<String, Object> snapshot() {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("submissions", submissions.sum());
		root.put("started", started.sum());
		root.put("completed", completed.sum());
		root.put("failed", failed.sum());
		root.put("inflight", inflight.sum());
		root.put("peakInflight", peakInflight.get());
		root.put("queueWait", summary(queueWaitMsTotal.sum(), started.sum(), queueWaitMsMax.get()));
		root.put("execution", summary(executionMsTotal.sum(), turns.sum(), executionMsMax.get()));
		root.put("firstEventLatency", summary(firstEventLatencyMsTotal.sum(), turns.sum(), firstEventLatencyMsMax.get()));
		root.put("firstDeltaLatency", summary(firstDeltaLatencyMsTotal.sum(), turns.sum(), firstDeltaLatencyMsMax.get()));
		root.put("llmThinking", summary(llmThinkingMsTotal.sum(), rounds.sum(), llmThinkingMsMax.get()));
		root.put("toolDuration", summary(toolDurationMsTotal.sum(), toolCalls.sum(), toolDurationMsMax.get()));
		root.put("turns", turns.sum());
		root.put("rounds", rounds.sum());
		root.put("toolCalls", toolCalls.sum());
		root.put("emittedEvents", emittedEvents.sum());
		return root;
	}

	public synchronized void reset() {
		submissions.reset();
		started.reset();
		completed.reset();
		failed.reset();
		inflight.reset();
		queueWaitMsTotal.reset();
		executionMsTotal.reset();
		firstEventLatencyMsTotal.reset();
		firstDeltaLatencyMsTotal.reset();
		llmThinkingMsTotal.reset();
		toolDurationMsTotal.reset();
		toolCalls.reset();
		turns.reset();
		rounds.reset();
		emittedEvents.reset();
		peakInflight.set(0);
		queueWaitMsMax.set(0);
		executionMsMax.set(0);
		firstEventLatencyMsMax.set(0);
		firstDeltaLatencyMsMax.set(0);
		llmThinkingMsMax.set(0);
		toolDurationMsMax.set(0);
	}

	private Map<String, Object> summary(long total, long count, long max) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("count", count);
		values.put("avgMs", count > 0 ? (double) total / count : 0.0d);
		values.put("maxMs", max);
		return values;
	}

	private void updatePeakInflight() {
		long current = inflight.sum();
		updateMax(peakInflight, current);
	}

	private void updateMax(AtomicLong target, long candidate) {
		long observed = target.get();
		while (candidate > observed && !target.compareAndSet(observed, candidate)) {
			observed = target.get();
		}
	}
}
