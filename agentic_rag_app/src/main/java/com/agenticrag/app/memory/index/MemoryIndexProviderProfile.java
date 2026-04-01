package com.agenticrag.app.memory.index;

public class MemoryIndexProviderProfile {
	private final String provider;
	private final String model;
	private final String providerKeyFingerprint;
	private final int vectorDims;

	public MemoryIndexProviderProfile(String provider, String model, String providerKeyFingerprint, int vectorDims) {
		this.provider = provider;
		this.model = model;
		this.providerKeyFingerprint = providerKeyFingerprint;
		this.vectorDims = vectorDims;
	}

	public String getProvider() {
		return provider;
	}

	public String getModel() {
		return model;
	}

	public String getProviderKeyFingerprint() {
		return providerKeyFingerprint;
	}

	public int getVectorDims() {
		return vectorDims;
	}
}
