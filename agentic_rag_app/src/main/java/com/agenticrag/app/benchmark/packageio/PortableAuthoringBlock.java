package com.agenticrag.app.benchmark.packageio;

public class PortableAuthoringBlock {
	private final String blockId;
	private final String docPath;
	private final String sectionKey;
	private final String sectionTitle;
	private final String blockType;
	private final int headingLevel;
	private final String text;
	private final String anchor;
	private final String sourceHash;
	private final int startLine;
	private final int endLine;

	public PortableAuthoringBlock(
		String blockId,
		String docPath,
		String sectionKey,
		String sectionTitle,
		String blockType,
		int headingLevel,
		String text,
		String anchor,
		String sourceHash,
		int startLine,
		int endLine
	) {
		this.blockId = blockId;
		this.docPath = docPath;
		this.sectionKey = sectionKey;
		this.sectionTitle = sectionTitle;
		this.blockType = blockType;
		this.headingLevel = headingLevel;
		this.text = text;
		this.anchor = anchor;
		this.sourceHash = sourceHash;
		this.startLine = startLine;
		this.endLine = endLine;
	}

	public String getBlockId() {
		return blockId;
	}

	public String getDocPath() {
		return docPath;
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

	public String getText() {
		return text;
	}

	public String getAnchor() {
		return anchor;
	}

	public String getSourceHash() {
		return sourceHash;
	}

	public int getStartLine() {
		return startLine;
	}

	public int getEndLine() {
		return endLine;
	}
}
