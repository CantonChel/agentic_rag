package com.agenticrag.app.benchmark.mapping;

import java.time.Instant;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;

@Entity
@Table(
	name = "benchmark_build_chunk_mapping",
	indexes = {
		@Index(name = "idx_benchmark_chunk_mapping_build", columnList = "buildId"),
		@Index(name = "idx_benchmark_chunk_mapping_chunk", columnList = "chunkId"),
		@Index(name = "idx_benchmark_chunk_mapping_doc", columnList = "docPath")
	}
)
public class BenchmarkBuildChunkMappingEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 64)
	private String buildId;

	@Column(nullable = false, length = 64)
	private String knowledgeId;

	@Column(nullable = false, length = 64)
	private String chunkId;

	@Column(nullable = false, length = 2048)
	private String docPath;

	@Column(nullable = false)
	private Integer startAt;

	@Column(nullable = false)
	private Integer endAt;

	@Column(nullable = false, columnDefinition = "text")
	private String goldBlockIdsJson;

	@Column(length = 128)
	private String primaryGoldBlockId;

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

	public String getBuildId() {
		return buildId;
	}

	public void setBuildId(String buildId) {
		this.buildId = buildId;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(String knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public String getChunkId() {
		return chunkId;
	}

	public void setChunkId(String chunkId) {
		this.chunkId = chunkId;
	}

	public String getDocPath() {
		return docPath;
	}

	public void setDocPath(String docPath) {
		this.docPath = docPath;
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

	public String getGoldBlockIdsJson() {
		return goldBlockIdsJson;
	}

	public void setGoldBlockIdsJson(String goldBlockIdsJson) {
		this.goldBlockIdsJson = goldBlockIdsJson;
	}

	public String getPrimaryGoldBlockId() {
		return primaryGoldBlockId;
	}

	public void setPrimaryGoldBlockId(String primaryGoldBlockId) {
		this.primaryGoldBlockId = primaryGoldBlockId;
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
