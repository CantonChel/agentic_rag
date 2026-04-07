package com.agenticrag.app.benchmark.mapping;

import java.util.List;

public class BenchmarkBuildChunkMappingView {
	private final Long id;
	private final String buildId;
	private final String knowledgeId;
	private final String chunkId;
	private final String docPath;
	private final Integer startAt;
	private final Integer endAt;
	private final List<String> goldBlockIds;
	private final String primaryGoldBlockId;

	public BenchmarkBuildChunkMappingView(
		Long id,
		String buildId,
		String knowledgeId,
		String chunkId,
		String docPath,
		Integer startAt,
		Integer endAt,
		List<String> goldBlockIds,
		String primaryGoldBlockId
	) {
		this.id = id;
		this.buildId = buildId;
		this.knowledgeId = knowledgeId;
		this.chunkId = chunkId;
		this.docPath = docPath;
		this.startAt = startAt;
		this.endAt = endAt;
		this.goldBlockIds = goldBlockIds;
		this.primaryGoldBlockId = primaryGoldBlockId;
	}

	public Long getId() {
		return id;
	}

	public String getBuildId() {
		return buildId;
	}

	public String getKnowledgeId() {
		return knowledgeId;
	}

	public String getChunkId() {
		return chunkId;
	}

	public String getDocPath() {
		return docPath;
	}

	public Integer getStartAt() {
		return startAt;
	}

	public Integer getEndAt() {
		return endAt;
	}

	public List<String> getGoldBlockIds() {
		return goldBlockIds;
	}

	public String getPrimaryGoldBlockId() {
		return primaryGoldBlockId;
	}
}
