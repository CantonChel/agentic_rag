package com.agenticrag.app.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
	private boolean enabled = true;
	private String workspaceRoot = "..";
	private String userMemoryBaseDir = "memory/users";
	private boolean includeTranscripts = true;
	private int transcriptMaxMessagesPerSession = 30;
	private boolean flushEnabled = true;
	private boolean preCompactionFlushEnabled = true;
	private int flushRecentMessages = 12;
	private int maxChunkChars = 800;
	private int chunkOverlapChars = 80;
	private int topKCandidates = 20;
	private int topK = 5;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getWorkspaceRoot() {
		return workspaceRoot;
	}

	public void setWorkspaceRoot(String workspaceRoot) {
		this.workspaceRoot = workspaceRoot;
	}

	public String getUserMemoryBaseDir() {
		return userMemoryBaseDir;
	}

	public void setUserMemoryBaseDir(String userMemoryBaseDir) {
		this.userMemoryBaseDir = userMemoryBaseDir;
	}

	public boolean isIncludeTranscripts() {
		return includeTranscripts;
	}

	public void setIncludeTranscripts(boolean includeTranscripts) {
		this.includeTranscripts = includeTranscripts;
	}

	public int getTranscriptMaxMessagesPerSession() {
		return transcriptMaxMessagesPerSession;
	}

	public void setTranscriptMaxMessagesPerSession(int transcriptMaxMessagesPerSession) {
		this.transcriptMaxMessagesPerSession = transcriptMaxMessagesPerSession;
	}

	public boolean isFlushEnabled() {
		return flushEnabled;
	}

	public void setFlushEnabled(boolean flushEnabled) {
		this.flushEnabled = flushEnabled;
	}

	public boolean isPreCompactionFlushEnabled() {
		return preCompactionFlushEnabled;
	}

	public void setPreCompactionFlushEnabled(boolean preCompactionFlushEnabled) {
		this.preCompactionFlushEnabled = preCompactionFlushEnabled;
	}

	public int getFlushRecentMessages() {
		return flushRecentMessages;
	}

	public void setFlushRecentMessages(int flushRecentMessages) {
		this.flushRecentMessages = flushRecentMessages;
	}

	public int getMaxChunkChars() {
		return maxChunkChars;
	}

	public void setMaxChunkChars(int maxChunkChars) {
		this.maxChunkChars = maxChunkChars;
	}

	public int getChunkOverlapChars() {
		return chunkOverlapChars;
	}

	public void setChunkOverlapChars(int chunkOverlapChars) {
		this.chunkOverlapChars = chunkOverlapChars;
	}

	public int getTopKCandidates() {
		return topKCandidates;
	}

	public void setTopKCandidates(int topKCandidates) {
		this.topKCandidates = topKCandidates;
	}

	public int getTopK() {
		return topK;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}
}
