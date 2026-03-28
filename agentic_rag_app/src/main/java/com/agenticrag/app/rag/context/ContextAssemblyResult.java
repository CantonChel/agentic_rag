package com.agenticrag.app.rag.context;

import com.fasterxml.jackson.databind.JsonNode;

public class ContextAssemblyResult {
	private final String contextText;
	private final JsonNode sidecar;

	public ContextAssemblyResult(String contextText, JsonNode sidecar) {
		this.contextText = contextText;
		this.sidecar = sidecar;
	}

	public String getContextText() {
		return contextText;
	}

	public JsonNode getSidecar() {
		return sidecar;
	}
}
