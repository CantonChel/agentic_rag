package com.agenticrag.app.benchmark.packageio;

import java.util.List;
import java.util.Map;

public class PortableNormalizedDocument {
	private final String docPath;
	private final String title;
	private final String normalizedText;
	private final Map<String, String> metadata;
	private final List<PortableNormalizedBlock> blocks;

	public PortableNormalizedDocument(
		String docPath,
		String title,
		String normalizedText,
		Map<String, String> metadata,
		List<PortableNormalizedBlock> blocks
	) {
		this.docPath = docPath;
		this.title = title;
		this.normalizedText = normalizedText;
		this.metadata = metadata;
		this.blocks = blocks;
	}

	public String getDocPath() {
		return docPath;
	}

	public String getTitle() {
		return title;
	}

	public String getNormalizedText() {
		return normalizedText;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public List<PortableNormalizedBlock> getBlocks() {
		return blocks;
	}
}
