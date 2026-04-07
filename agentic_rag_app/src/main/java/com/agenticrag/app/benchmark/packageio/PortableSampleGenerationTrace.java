package com.agenticrag.app.benchmark.packageio;

import java.util.List;

public class PortableSampleGenerationTrace {
	private final String sampleId;
	private final String generationMethod;
	private final List<String> inputBlockIds;
	private final String generatorVersion;
	private final String modelOrRuleName;
	private final String validationStatus;

	public PortableSampleGenerationTrace(
		String sampleId,
		String generationMethod,
		List<String> inputBlockIds,
		String generatorVersion,
		String modelOrRuleName,
		String validationStatus
	) {
		this.sampleId = sampleId;
		this.generationMethod = generationMethod;
		this.inputBlockIds = inputBlockIds;
		this.generatorVersion = generatorVersion;
		this.modelOrRuleName = modelOrRuleName;
		this.validationStatus = validationStatus;
	}

	public String getSampleId() {
		return sampleId;
	}

	public String getGenerationMethod() {
		return generationMethod;
	}

	public List<String> getInputBlockIds() {
		return inputBlockIds;
	}

	public String getGeneratorVersion() {
		return generatorVersion;
	}

	public String getModelOrRuleName() {
		return modelOrRuleName;
	}

	public String getValidationStatus() {
		return validationStatus;
	}
}
