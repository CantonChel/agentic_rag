package com.agenticrag.app.ingest.model;

import java.time.Instant;

public class JobFailureAction {
	public enum Decision {
		RETRY,
		DEAD_LETTER,
		FAILED
	}

	private final Decision decision;
	private final Instant nextRetryAt;

	public JobFailureAction(Decision decision, Instant nextRetryAt) {
		this.decision = decision;
		this.nextRetryAt = nextRetryAt;
	}

	public Decision getDecision() {
		return decision;
	}

	public Instant getNextRetryAt() {
		return nextRetryAt;
	}
}
