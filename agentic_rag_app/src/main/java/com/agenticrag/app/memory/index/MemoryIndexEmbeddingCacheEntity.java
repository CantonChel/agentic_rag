package com.agenticrag.app.memory.index;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(
	name = "memory_index_embedding_cache",
	uniqueConstraints = {
		@UniqueConstraint(
			name = "uk_memory_index_embedding_cache_provider_model_key_hash",
			columnNames = {"provider", "model", "provider_key_fingerprint", "chunk_hash"}
		)
	},
	indexes = {
		@Index(name = "idx_memory_index_embedding_cache_lookup", columnList = "provider,model,provider_key_fingerprint,chunk_hash"),
		@Index(name = "idx_memory_index_embedding_cache_content_hash", columnList = "content_hash")
	}
)
public class MemoryIndexEmbeddingCacheEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "provider", nullable = false, length = 64)
	private String provider;

	@Column(name = "model", nullable = false, length = 128)
	private String model;

	@Column(name = "provider_key_fingerprint", nullable = false, length = 128)
	private String providerKeyFingerprint;

	@Column(name = "chunk_hash", nullable = false, length = 128)
	private String chunkHash;

	@Column(name = "dimension", nullable = false)
	private int dimension;

	@Column(name = "vector_json", nullable = false, columnDefinition = "text")
	private String vectorJson;

	@Column(name = "content_hash", nullable = false, length = 128)
	private String contentHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
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

	public String getChunkHash() {
		return chunkHash;
	}

	public void setChunkHash(String chunkHash) {
		this.chunkHash = chunkHash;
	}

	public int getDimension() {
		return dimension;
	}

	public void setDimension(int dimension) {
		this.dimension = dimension;
	}

	public String getVectorJson() {
		return vectorJson;
	}

	public void setVectorJson(String vectorJson) {
		this.vectorJson = vectorJson;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
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
