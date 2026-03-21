package com.agenticrag.app.ingest.entity;

import com.agenticrag.app.ingest.model.ChunkType;
import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

@Entity
@Table(
	name = "chunk",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_chunk_knowledge_chunk", columnNames = {"knowledgeId", "chunkId"})
	},
	indexes = {
		@Index(name = "idx_chunk_knowledge", columnList = "knowledgeId"),
		@Index(name = "idx_chunk_parent", columnList = "parentChunkId")
	}
)
public class ChunkEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64)
	private String chunkId;

	@Column(nullable = false, length = 64)
	private String knowledgeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ChunkType chunkType;

	@Column
	private Integer chunkIndex;

	@Column
	private Integer startAt;

	@Column
	private Integer endAt;

	@Column(length = 64)
	private String parentChunkId;

	@Column(length = 64)
	private String preChunkId;

	@Column(length = 64)
	private String nextChunkId;

	@Column(nullable = false)
	private String content;

	@Column
	private String imageInfoJson;

	@Column
	private String metadataJson;

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

	public ChunkType getChunkType() {
		return chunkType;
	}

	public void setChunkType(ChunkType chunkType) {
		this.chunkType = chunkType;
	}

	public Integer getChunkIndex() {
		return chunkIndex;
	}

	public void setChunkIndex(Integer chunkIndex) {
		this.chunkIndex = chunkIndex;
	}

	public Integer getStartAt() {
		return startAt;
	}

	public void setStartAt(Integer startAt) {
		this.startAt = startAt;
	}

	public Integer getEndAt() {
		return endAt;
	}

	public void setEndAt(Integer endAt) {
		this.endAt = endAt;
	}

	public String getParentChunkId() {
		return parentChunkId;
	}

	public void setParentChunkId(String parentChunkId) {
		this.parentChunkId = parentChunkId;
	}

	public String getPreChunkId() {
		return preChunkId;
	}

	public void setPreChunkId(String preChunkId) {
		this.preChunkId = preChunkId;
	}

	public String getNextChunkId() {
		return nextChunkId;
	}

	public void setNextChunkId(String nextChunkId) {
		this.nextChunkId = nextChunkId;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getImageInfoJson() {
		return imageInfoJson;
	}

	public void setImageInfoJson(String imageInfoJson) {
		this.imageInfoJson = imageInfoJson;
	}

	public String getMetadataJson() {
		return metadataJson;
	}

	public void setMetadataJson(String metadataJson) {
		this.metadataJson = metadataJson;
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
