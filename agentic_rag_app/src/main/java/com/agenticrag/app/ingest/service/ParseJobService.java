package com.agenticrag.app.ingest.service;

import com.agenticrag.app.ingest.config.IngestAsyncProperties;
import com.agenticrag.app.ingest.entity.KnowledgeEntity;
import com.agenticrag.app.ingest.entity.ParseJobEntity;
import com.agenticrag.app.ingest.model.JobFailureAction;
import com.agenticrag.app.ingest.model.KnowledgeEnableStatus;
import com.agenticrag.app.ingest.model.KnowledgeParseStatus;
import com.agenticrag.app.ingest.model.ParseJobStatus;
import com.agenticrag.app.ingest.repo.KnowledgeRepository;
import com.agenticrag.app.ingest.repo.ParseJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParseJobService {
	private static final List<Duration> RETRY_BACKOFF = Arrays.asList(
		Duration.ofMinutes(1),
		Duration.ofMinutes(5),
		Duration.ofMinutes(15),
		Duration.ofHours(1),
		Duration.ofHours(6)
	);

	private final ParseJobRepository parseJobRepository;
	private final KnowledgeRepository knowledgeRepository;
	private final IngestAsyncProperties asyncProperties;

	public ParseJobService(
		ParseJobRepository parseJobRepository,
		KnowledgeRepository knowledgeRepository,
		IngestAsyncProperties asyncProperties
	) {
		this.parseJobRepository = parseJobRepository;
		this.knowledgeRepository = knowledgeRepository;
		this.asyncProperties = asyncProperties;
	}

	@Transactional(readOnly = true)
	public Optional<ParseJobEntity> findById(String jobId) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return Optional.empty();
		}
		return parseJobRepository.findById(jobId.trim());
	}

	@Transactional
	public boolean tryMarkDispatched(String jobId, Instant leaseUntil) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return false;
		}
		int updated = parseJobRepository.transitionIfStatusIn(
			jobId.trim(),
			Arrays.asList(ParseJobStatus.QUEUED, ParseJobStatus.RETRY_WAIT),
			ParseJobStatus.DISPATCHED,
			leaseUntil,
			null,
			Instant.now()
		);
		if (updated > 0) {
			setKnowledgeParseStatus(jobId, KnowledgeParseStatus.PARSING);
		}
		return updated > 0;
	}

	@Transactional
	public void markParsing(String jobId, Instant leaseUntil) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return;
		}
		parseJobRepository.transitionIfStatusIn(
			jobId.trim(),
			Collections.singletonList(ParseJobStatus.DISPATCHED),
			ParseJobStatus.PARSING,
			leaseUntil,
			null,
			Instant.now()
		);
		setKnowledgeParseStatus(jobId, KnowledgeParseStatus.PARSING);
	}

	@Transactional
	public boolean markIndexing(String jobId) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return false;
		}
		int updated = parseJobRepository.transitionIfStatusIn(
			jobId.trim(),
			Collections.singletonList(ParseJobStatus.PARSING),
			ParseJobStatus.INDEXING,
			null,
			null,
			Instant.now()
		);
		return updated > 0;
	}

	@Transactional
	public void markSuccess(String jobId) {
		if (jobId == null || jobId.trim().isEmpty()) {
			return;
		}
		int updated = parseJobRepository.transitionIfStatusIn(
			jobId.trim(),
			Arrays.asList(ParseJobStatus.PARSING, ParseJobStatus.INDEXING, ParseJobStatus.DISPATCHED),
			ParseJobStatus.SUCCESS,
			null,
			null,
			Instant.now()
		);
		if (updated <= 0) {
			parseJobRepository.transitionIfStatusIn(
				jobId.trim(),
				Collections.singletonList(ParseJobStatus.SUCCESS),
				ParseJobStatus.SUCCESS,
				null,
				null,
				Instant.now()
			);
		}
		ParseJobEntity job = parseJobRepository.findById(jobId.trim()).orElse(null);
		if (job != null) {
			KnowledgeEntity knowledge = knowledgeRepository.findById(job.getKnowledgeId()).orElse(null);
			if (knowledge != null) {
				knowledge.setParseStatus(KnowledgeParseStatus.COMPLETED);
				knowledge.setEnableStatus(KnowledgeEnableStatus.ENABLED);
				knowledge.setUpdatedAt(Instant.now());
				knowledgeRepository.save(knowledge);
			}
		}
	}

	@Transactional
	public JobFailureAction registerFailure(String jobId, String errorCode, String errorMessage, boolean retryable) {
		ParseJobEntity job = parseJobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return new JobFailureAction(JobFailureAction.Decision.FAILED, null);
		}

		Instant now = Instant.now();
		job.setUpdatedAt(now);
		job.setLeaseUntil(null);
		job.setLastErrorCode(trim(errorCode, 64));
		job.setLastErrorMessage(trim(errorMessage, 2048));

		if (!retryable) {
			job.setStatus(ParseJobStatus.FAILED);
			job.setNextRetryAt(null);
			parseJobRepository.save(job);
			setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), KnowledgeParseStatus.FAILED);
			return new JobFailureAction(JobFailureAction.Decision.FAILED, null);
		}

		int nextRetryCount = job.getRetryCount() + 1;
		job.setRetryCount(nextRetryCount);
		if (nextRetryCount > job.getMaxRetry()) {
			job.setStatus(ParseJobStatus.DEAD_LETTER);
			job.setNextRetryAt(null);
			parseJobRepository.save(job);
			setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), KnowledgeParseStatus.FAILED);
			return new JobFailureAction(JobFailureAction.Decision.DEAD_LETTER, null);
		}

		Duration delay = retryDelay(nextRetryCount);
		Instant nextRetryAt = now.plus(delay);
		job.setStatus(ParseJobStatus.RETRY_WAIT);
		job.setNextRetryAt(nextRetryAt);
		parseJobRepository.save(job);
		setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), KnowledgeParseStatus.PARSING);
		return new JobFailureAction(JobFailureAction.Decision.RETRY, nextRetryAt);
	}

	@Transactional
	public boolean manualRetry(String jobId) {
		ParseJobEntity job = parseJobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return false;
		}
		if (job.getStatus() != ParseJobStatus.FAILED && job.getStatus() != ParseJobStatus.DEAD_LETTER) {
			return false;
		}
		job.setStatus(ParseJobStatus.RETRY_WAIT);
		job.setNextRetryAt(Instant.now());
		job.setLastErrorCode(null);
		job.setLastErrorMessage(null);
		job.setUpdatedAt(Instant.now());
		parseJobRepository.save(job);
		setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), KnowledgeParseStatus.QUEUED);
		return true;
	}

	@Transactional
	public List<String> recoverExpiredLeases(Instant now) {
		Instant point = now != null ? now : Instant.now();
		List<ParseJobEntity> expired = parseJobRepository.findTop100ByStatusInAndLeaseUntilLessThanEqualOrderByLeaseUntilAsc(
			Arrays.asList(ParseJobStatus.DISPATCHED, ParseJobStatus.PARSING), point);
		List<String> recovered = new ArrayList<>();
		for (ParseJobEntity job : expired) {
			job.setStatus(ParseJobStatus.RETRY_WAIT);
			job.setNextRetryAt(point);
			job.setLeaseUntil(null);
			job.setLastErrorCode("lease_expired");
			job.setLastErrorMessage("worker lease expired");
			job.setUpdatedAt(point);
			parseJobRepository.save(job);
			recovered.add(job.getId());
		}
		return recovered;
	}

	@Transactional
	public List<String> recoverStaleIndexing(Instant now) {
		Instant point = now != null ? now : Instant.now();
		long staleSeconds = Math.max(asyncProperties.getLeaseSeconds() * 3L, 600L);
		Instant cutoff = point.minusSeconds(staleSeconds);
		List<ParseJobEntity> stale = parseJobRepository.findTop100ByStatusAndUpdatedAtLessThanEqualOrderByUpdatedAtAsc(
			ParseJobStatus.INDEXING,
			cutoff
		);
		List<String> recovered = new ArrayList<>();
		for (ParseJobEntity job : stale) {
			job.setStatus(ParseJobStatus.RETRY_WAIT);
			job.setNextRetryAt(point);
			job.setLeaseUntil(null);
			job.setLastErrorCode("indexing_stale");
			job.setLastErrorMessage("indexing has no progress for " + staleSeconds + " seconds");
			job.setUpdatedAt(point);
			parseJobRepository.save(job);
			setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), KnowledgeParseStatus.PARSING);
			recovered.add(job.getId());
		}
		return recovered;
	}

	@Transactional(readOnly = true)
	public int maxRetry() {
		return asyncProperties.getMaxRetry();
	}

	private Duration retryDelay(int retryCount) {
		int idx = Math.max(1, retryCount) - 1;
		if (idx >= RETRY_BACKOFF.size()) {
			idx = RETRY_BACKOFF.size() - 1;
		}
		return RETRY_BACKOFF.get(idx);
	}

	private String trim(String s, int max) {
		if (s == null) {
			return null;
		}
		if (s.length() <= max) {
			return s;
		}
		return s.substring(0, max);
	}

	private void setKnowledgeParseStatus(String jobId, KnowledgeParseStatus status) {
		ParseJobEntity job = parseJobRepository.findById(jobId).orElse(null);
		if (job == null) {
			return;
		}
		setKnowledgeParseStatusByKnowledgeId(job.getKnowledgeId(), status);
	}

	private void setKnowledgeParseStatusByKnowledgeId(String knowledgeId, KnowledgeParseStatus status) {
		KnowledgeEntity knowledge = knowledgeRepository.findById(knowledgeId).orElse(null);
		if (knowledge == null) {
			return;
		}
		knowledge.setParseStatus(status);
		if (status == KnowledgeParseStatus.FAILED || status == KnowledgeParseStatus.PARSING || status == KnowledgeParseStatus.QUEUED) {
			knowledge.setEnableStatus(KnowledgeEnableStatus.DISABLED);
		}
		knowledge.setUpdatedAt(Instant.now());
		knowledgeRepository.save(knowledge);
	}
}
