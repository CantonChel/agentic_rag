package com.agenticrag.app.memory.index;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;

@Entity
@Table(name = "memory_index_meta")
public class MemoryIndexMetaEntity {
	@EmbeddedId
	private MemoryIndexMetaId id;

	@Column(name = "index_version", nullable = false)
	private int indexVersion;

	@Column(name = "provider", nullable = false, length = 64)
	private String provider;

	@Column(name = "model", nullable = false, length = 128)
	private String model;

	@Column(name = "provider_key_fingerprint", nullable = false, length = 128)
	private String providerKeyFingerprint;

	@Column(name = "sources_json", nullable = false, columnDefinition = "text")
	private String sourcesJson;

	@Column(name = "scope_hash", nullable = false, length = 128)
	private String scopeHash;

	@Column(name = "chunk_chars", nullable = false)
	private int chunkChars;

	@Column(name = "chunk_overlap", nullable = false)
	private int chunkOverlap;

	@Column(name = "vector_dims", nullable = false)
	private int vectorDims;

	@Column(name = "dirty", nullable = false)
	private boolean dirty;

	@Column(name = "last_sync_at")
	private Instant lastSyncAt;

	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public MemoryIndexMetaId getId() {
		return id;
	}

	public void setId(MemoryIndexMetaId id) {
		this.id = id;
	}

	public int getIndexVersion() {
		return indexVersion;
	}

	public void setIndexVersion(int indexVersion) {
		this.indexVersion = indexVersion;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getProviderKeyFingerprint() {
		return providerKeyFingerprint;
	}

	public void setProviderKeyFingerprint(String providerKeyFingerprint) {
		this.providerKeyFingerprint = providerKeyFingerprint;
	}

	public String getSourcesJson() {
		return sourcesJson;
	}

	public void setSourcesJson(String sourcesJson) {
		this.sourcesJson = sourcesJson;
	}

	public String getScopeHash() {
		return scopeHash;
	}

	public void setScopeHash(String scopeHash) {
		this.scopeHash = scopeHash;
	}

	public int getChunkChars() {
		return chunkChars;
	}

	public void setChunkChars(int chunkChars) {
		this.chunkChars = chunkChars;
	}

	public int getChunkOverlap() {
		return chunkOverlap;
	}

	public void setChunkOverlap(int chunkOverlap) {
		this.chunkOverlap = chunkOverlap;
	}

	public int getVectorDims() {
		return vectorDims;
	}

	public void setVectorDims(int vectorDims) {
		this.vectorDims = vectorDims;
	}

	public boolean isDirty() {
		return dirty;
	}

	public void setDirty(boolean dirty) {
		this.dirty = dirty;
	}

	public Instant getLastSyncAt() {
		return lastSyncAt;
	}

	public void setLastSyncAt(Instant lastSyncAt) {
		this.lastSyncAt = lastSyncAt;
	}

	public String getLastError() {
		return lastError;
	}

	public void setLastError(String lastError) {
		this.lastError = lastError;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
