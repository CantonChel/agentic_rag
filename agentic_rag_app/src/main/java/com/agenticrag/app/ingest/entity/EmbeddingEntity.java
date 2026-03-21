package com.agenticrag.app.ingest.entity;

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
	name = "embedding",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_embedding_chunk_model", columnNames = {"chunkId", "modelName"})
	},
	indexes = {
		@Index(name = "idx_embedding_knowledge", columnList = "knowledgeId"),
		@Index(name = "idx_embedding_chunk", columnList = "chunkId")
	}
)
public class EmbeddingEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64)
	private String chunkId;

	@Column(nullable = false, length = 64)
	private String knowledgeId;

	@Column(nullable = false, length = 128)
	private String modelName;

	@Column(nullable = false)
	private int dimension;

	@Column(nullable = false)
	private String vectorJson;

	@Column(nullable = false)
	private String content;

	@Column(nullable = false)
	private boolean enabled;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getChunkId() {
		return chunkId;
	}

	public void setChunkId(String chunkId) {
		this.chunkId = chunkId;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(String knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public String getModelName() {
		return modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
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

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
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
