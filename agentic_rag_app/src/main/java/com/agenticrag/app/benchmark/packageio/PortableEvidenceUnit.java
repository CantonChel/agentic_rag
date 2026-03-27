package com.agenticrag.app.benchmark.packageio;

public class PortableEvidenceUnit {
	private final String evidenceId;
	private final String docPath;
	private final String sectionKey;
	private final String sectionTitle;
	private final String canonicalText;
	private final String anchor;
	private final String sourceHash;
	private final String extractorVersion;

	public PortableEvidenceUnit(
		String evidenceId,
		String docPath,
		String sectionKey,
		String sectionTitle,
		String canonicalText,
		String anchor,
		String sourceHash,
		String extractorVersion
	) {
		this.evidenceId = evidenceId;
		this.docPath = docPath;
		this.sectionKey = sectionKey;
		this.sectionTitle = sectionTitle;
		this.canonicalText = canonicalText;
		this.anchor = anchor;
		this.sourceHash = sourceHash;
		this.extractorVersion = extractorVersion;
	}

	public String getEvidenceId() {
		return evidenceId;
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

	public String getCanonicalText() {
		return canonicalText;
	}

	public String getAnchor() {
		return anchor;
	}

	public String getSourceHash() {
		return sourceHash;
	}

	public String getExtractorVersion() {
		return extractorVersion;
	}
}
