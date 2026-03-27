package com.agenticrag.app.benchmark.packageio;

public class PortableEvidenceReference {
	private final String evidenceId;
	private final String docPath;
	private final String sectionKey;

	public PortableEvidenceReference(String evidenceId, String docPath, String sectionKey) {
		this.evidenceId = evidenceId;
		this.docPath = docPath;
		this.sectionKey = sectionKey;
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
}
