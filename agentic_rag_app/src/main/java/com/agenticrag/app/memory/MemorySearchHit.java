package com.agenticrag.app.memory;

public class MemorySearchHit {
	private final String path;
	private final String kind;
	private final String blockId;
	private final int lineStart;
	private final int lineEnd;
	private final double score;
	private final String snippet;

	public MemorySearchHit(
		String path,
		String kind,
		String blockId,
		int lineStart,
		int lineEnd,
		double score,
		String snippet
	) {
		this.path = path;
		this.kind = kind;
		this.blockId = blockId;
		this.lineStart = lineStart;
		this.lineEnd = lineEnd;
		this.score = score;
		this.snippet = snippet;
	}

	public String getPath() {
		return path;
	}

	public String getKind() {
		return kind;
	}

	public String getBlockId() {
		return blockId;
	}

	public int getLineStart() {
		return lineStart;
	}

	public int getLineEnd() {
		return lineEnd;
	}

	public double getScore() {
		return score;
	}

	public String getSnippet() {
		return snippet;
	}
}
