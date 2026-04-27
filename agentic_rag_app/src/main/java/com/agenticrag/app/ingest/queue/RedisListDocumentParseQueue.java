package com.agenticrag.app.ingest.queue;

import com.agenticrag.app.ingest.config.RedisQueueProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ingest.redis-queue.enabled", havingValue = "true", matchIfMissing = true)
public class RedisListDocumentParseQueue implements DocumentParseQueue {
	private final StringRedisTemplate redisTemplate;
	private final RedisQueueProperties properties;

	public RedisListDocumentParseQueue(StringRedisTemplate redisTemplate, RedisQueueProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	@Override
	public void enqueue(String jobId) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return;
		}
		redisTemplate.opsForList().leftPush(properties.getReadyKey(), jobId.trim());
	}

	@Override
	public ReservedJob reserve(Duration timeout) {
		Duration t = timeout != null ? timeout : Duration.ofSeconds(2);
		String payload = redisTemplate.opsForList()
			.rightPopAndLeftPush(properties.getReadyKey(), properties.getProcessingKey(), t);
		if (payload == null || payload.trim().isEmpty()) {
			return null;
		}
		String jobId = payload.trim();
		return new ReservedJob(jobId, payload);
	}

	@Override
	public void ack(ReservedJob job) {
		if (job == null || job.getPayload() == null) {
			return;
		}
		redisTemplate.opsForList().remove(properties.getProcessingKey(), 1, job.getPayload());
	}

	@Override
	public void retry(ReservedJob job, Instant nextRetryAt) {
		if (job == null || job.getPayload() == null) {
			return;
		}
		redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
			byte[] processing = properties.getProcessingKey().getBytes();
			byte[] retry = properties.getRetryKey().getBytes();
			byte[] payload = job.getPayload().getBytes();
			long score = nextRetryAt != null ? nextRetryAt.toEpochMilli() : Instant.now().toEpochMilli();
			connection.lRem(processing, 1, payload);
			connection.zAdd(retry, score, payload);
			return null;
		});
	}

	@Override
	public void deadLetter(ReservedJob job) {
		if (job == null || job.getPayload() == null) {
			return;
		}
		redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
			byte[] processing = properties.getProcessingKey().getBytes();
			byte[] dlq = properties.getDlqKey().getBytes();
			byte[] payload = job.getPayload().getBytes();
			connection.lRem(processing, 1, payload);
			connection.lPush(dlq, payload);
			return null;
		});
	}

	@Override
	public int replayDueRetries(Instant now, int batchSize) {
		Instant due = now != null ? now : Instant.now();
		int size = batchSize > 0 ? batchSize : 100;
		Set<String> dueItems = redisTemplate.opsForZSet().rangeByScore(properties.getRetryKey(), 0, due.toEpochMilli(), 0, size);
		if (dueItems == null || dueItems.isEmpty()) {
			return 0;
		}
		List<String> moved = new ArrayList<>();
		for (String item : dueItems) {
			if (item == null || item.trim().isEmpty()) {
				continue;
			}
			Long removed = redisTemplate.opsForZSet().remove(properties.getRetryKey(), item);
			if (removed != null && removed > 0) {
				redisTemplate.opsForList().leftPush(properties.getReadyKey(), item);
				moved.add(item);
			}
		}
		return moved.size();
	}
}
