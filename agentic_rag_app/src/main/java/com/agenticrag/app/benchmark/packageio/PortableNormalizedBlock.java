package com.agenticrag.app.benchmark.packageio;

public class PortableNormalizedBlock {
	private final String blockId;
	private final String sectionKey;
	private final String sectionTitle;
	private final String blockType;
	private final int headingLevel;
	private final String content;
	private final int startLine;
	private final int endLine;

	public PortableNormalizedBlock(
		String blockId,
		String sectionKey,
		String sectionTitle,
		String blockType,
		int headingLevel,
		String content,
		int startLine,
		int endLine
	) {
		this.blockId = blockId;
		this.sectionKey = sectionKey;
		this.sectionTitle = sectionTitle;
		this.blockType = blockType;
		this.headingLevel = headingLevel;
		this.content = content;
		this.startLine = startLine;
		this.endLine = endLine;
	}

	public String getBlockId() {
		return blockId;
	}

	public String getSectionKey() {
		return sectionKey;
	}

	public String getSectionTitle() {
		return sectionTitle;
	}

	public String getBlockType() {
		return blockType;
	}

	public int getHeadingLevel() {
		return headingLevel;
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
}
