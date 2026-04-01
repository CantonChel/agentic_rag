package com.agenticrag.app.memory.index;

public class MemoryIndexChunkSeed {
	private final String path;
	private final String kind;
	private final String blockId;
	private final int lineStart;
	private final int lineEnd;
	private final String content;
	private final String chunkHash;

	public MemoryIndexChunkSeed(String path, String kind, String blockId, int lineStart, int lineEnd, String content, String chunkHash) {
		this.path = path;
		this.kind = kind;
		this.blockId = blockId;
		this.lineStart = lineStart;
		this.lineEnd = lineEnd;
		this.content = content;
		this.chunkHash = chunkHash;
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

	public String getContent() {
		return content;
	}

	public String getChunkHash() {
		return chunkHash;
	}
}
