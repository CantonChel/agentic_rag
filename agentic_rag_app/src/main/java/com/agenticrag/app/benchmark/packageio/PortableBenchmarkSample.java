package com.agenticrag.app.benchmark.packageio;

import java.util.List;

public class PortableBenchmarkSample {
	private final String sampleId;
	private final String question;
	private final String groundTruth;
	private final List<String> groundTruthContexts;
	private final List<PortableEvidenceReference> goldEvidenceRefs;
	private final List<String> tags;
	private final String difficulty;
	private final String suiteVersion;

	public PortableBenchmarkSample(
		String sampleId,
		String question,
		String groundTruth,
		List<String> groundTruthContexts,
		List<PortableEvidenceReference> goldEvidenceRefs,
		List<String> tags,
		String difficulty,
		String suiteVersion
	) {
		this.sampleId = sampleId;
		this.question = question;
		this.groundTruth = groundTruth;
		this.groundTruthContexts = groundTruthContexts;
		this.goldEvidenceRefs = goldEvidenceRefs;
		this.tags = tags;
		this.difficulty = difficulty;
		this.suiteVersion = suiteVersion;
	}

	public String getSampleId() {
		return sampleId;
	}

	public String getQuestion() {
		return question;
	}

	public String getGroundTruth() {
		return groundTruth;
	}

	public List<String> getGroundTruthContexts() {
		return groundTruthContexts;
	}

	public List<PortableEvidenceReference> getGoldEvidenceRefs() {
		return goldEvidenceRefs;
	}

	public List<String> getTags() {
		return tags;
	}

	public String getDifficulty() {
		return difficulty;
	}

	public String getSuiteVersion() {
		return suiteVersion;
	}
}
