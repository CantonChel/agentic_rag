package com.agenticrag.app.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memory")
public class MemoryProperties {
	private boolean enabled = true;
	private String workspaceRoot = "..";
	private String userMemoryBaseDir = "memory/users";
	private boolean flushEnabled = true;
	private String flushProvider = "minimax";
	private String flushModel = "";
	private int flushMaxCompletionTokens = 700;
	private int flushInputMaxChars = 6000;
	private int slugMaxLength = 64;
	private int maxFactsPerFlush = 3;
	private int maxFactCandidates = 2;
	private int maxChunkChars = 800;
	private int chunkOverlap = 120;
	private int topKCandidates = 20;
	private int topK = 5;
	private String embeddingCacheDir = "memory/.cache/embeddings";
	private boolean indexStartupSyncEnabled = true;
	private boolean watcherEnabled = true;
	private int watcherDebounceMs = 300;

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

	public boolean isFlushEnabled() {
		return flushEnabled;
	}

	public void setFlushEnabled(boolean flushEnabled) {
		this.flushEnabled = flushEnabled;
	}

	public String getFlushProvider() {
		return flushProvider;
	}

	public void setFlushProvider(String flushProvider) {
		this.flushProvider = flushProvider;
	}

	public String getFlushModel() {
		return flushModel;
	}

	public void setFlushModel(String flushModel) {
		this.flushModel = flushModel;
	}

	public int getFlushMaxCompletionTokens() {
		return flushMaxCompletionTokens;
	}

	public void setFlushMaxCompletionTokens(int flushMaxCompletionTokens) {
		this.flushMaxCompletionTokens = flushMaxCompletionTokens;
	}

	public int getFlushInputMaxChars() {
		return flushInputMaxChars;
	}

	public void setFlushInputMaxChars(int flushInputMaxChars) {
		this.flushInputMaxChars = flushInputMaxChars;
	}

	public int getSlugMaxLength() {
		return slugMaxLength;
	}

	public void setSlugMaxLength(int slugMaxLength) {
		this.slugMaxLength = slugMaxLength;
	}

	public int getMaxFactsPerFlush() {
		return maxFactsPerFlush;
	}

	public void setMaxFactsPerFlush(int maxFactsPerFlush) {
		this.maxFactsPerFlush = maxFactsPerFlush;
	}

	public int getMaxFactCandidates() {
		return maxFactCandidates;
	}

	public void setMaxFactCandidates(int maxFactCandidates) {
		this.maxFactCandidates = maxFactCandidates;
	}

	public int getMaxChunkChars() {
		return maxChunkChars;
	}

	public void setMaxChunkChars(int maxChunkChars) {
		this.maxChunkChars = maxChunkChars;
	}

	public int getChunkOverlap() {
		return chunkOverlap;
	}

	public void setChunkOverlap(int chunkOverlap) {
		this.chunkOverlap = chunkOverlap;
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

	public String getEmbeddingCacheDir() {
		return embeddingCacheDir;
	}

	public void setEmbeddingCacheDir(String embeddingCacheDir) {
		this.embeddingCacheDir = embeddingCacheDir;
	}

	public boolean isIndexStartupSyncEnabled() {
		return indexStartupSyncEnabled;
	}

	public void setIndexStartupSyncEnabled(boolean indexStartupSyncEnabled) {
		this.indexStartupSyncEnabled = indexStartupSyncEnabled;
	}

	public boolean isWatcherEnabled() {
		return watcherEnabled;
	}

	public void setWatcherEnabled(boolean watcherEnabled) {
		this.watcherEnabled = watcherEnabled;
	}

	public int getWatcherDebounceMs() {
		return watcherDebounceMs;
	}

	public void setWatcherDebounceMs(int watcherDebounceMs) {
		this.watcherDebounceMs = watcherDebounceMs;
	}
}
