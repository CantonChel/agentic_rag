package com.agenticrag.app.memory;

public class MemoryFactCompareResult {
	public enum Decision {
		ADD,
		UPDATE,
		NONE
	}

	private final Decision decision;
	private final int matchIndex;

	public MemoryFactCompareResult(Decision decision, int matchIndex) {
		this.decision = decision != null ? decision : Decision.ADD;
		this.matchIndex = matchIndex;
	}

	public Decision getDecision() {
		return decision;
	}

	public int getMatchIndex() {
		return matchIndex;
	}
}
