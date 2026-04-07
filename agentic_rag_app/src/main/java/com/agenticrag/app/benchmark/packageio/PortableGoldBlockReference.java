package com.agenticrag.app.benchmark.packageio;

public class PortableGoldBlockReference {
	private final String blockId;
	private final String docPath;
	private final String sectionKey;

	public PortableGoldBlockReference(String blockId, String docPath, String sectionKey) {
		this.blockId = blockId;
		this.docPath = docPath;
		this.sectionKey = sectionKey;
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
}
