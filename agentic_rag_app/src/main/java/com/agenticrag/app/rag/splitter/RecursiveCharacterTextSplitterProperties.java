package com.agenticrag.app.rag.splitter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rag.splitter.recursive")
public class RecursiveCharacterTextSplitterProperties {
	private int chunkSize = 500;
	private int chunkOverlap = 50;

	public int getChunkSize() {
		return chunkSize;
	}

	public void setChunkSize(int chunkSize) {
		this.chunkSize = chunkSize;
	}

	public int getChunkOverlap() {
		return chunkOverlap;
	}

	public void setChunkOverlap(int chunkOverlap) {
		this.chunkOverlap = chunkOverlap;
	}
}

