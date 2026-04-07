package com.agenticrag.app.benchmark.packageio;

public class PortableBlockLink {
	private final String fromBlockId;
	private final String toBlockId;
	private final String linkType;

	public PortableBlockLink(String fromBlockId, String toBlockId, String linkType) {
		this.fromBlockId = fromBlockId;
		this.toBlockId = toBlockId;
		this.linkType = linkType;
	}

	public String getFromBlockId() {
		return fromBlockId;
	}

	public String getToBlockId() {
		return toBlockId;
	}

	public String getLinkType() {
		return linkType;
	}
}
