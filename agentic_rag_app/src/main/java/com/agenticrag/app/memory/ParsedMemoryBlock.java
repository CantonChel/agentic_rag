package com.agenticrag.app.memory;

import java.nio.file.Path;

public class ParsedMemoryBlock {
	private final Path path;
	private final String relativePath;
	private final String kind;
	private final MemoryBlockMetadata metadata;
	private final String content;
	private final int startLine;
	private final int endLine;
	private final boolean legacy;

	public ParsedMemoryBlock(
		Path path,
		String relativePath,
		String kind,
		MemoryBlockMetadata metadata,
		String content,
		int startLine,
		int endLine,
		boolean legacy
	) {
		this.path = path;
		this.relativePath = relativePath;
		this.kind = kind;
		this.metadata = metadata;
		this.content = content;
		this.startLine = startLine;
		this.endLine = endLine;
		this.legacy = legacy;
	}

	public Path getPath() {
		return path;
	}

	public String getRelativePath() {
		return relativePath;
	}

	public String getKind() {
		return kind;
	}

	public MemoryBlockMetadata getMetadata() {
		return metadata;
	}

	public String getContent() {
		return content;
	}

	public int getStartLine() {
		return startLine;
	}

	public int getEndLine() {
		return endLine;
	}

	public boolean isLegacy() {
		return legacy;
	}
}
