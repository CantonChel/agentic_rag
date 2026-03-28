package com.agenticrag.app.benchmark.materialize;

import com.agenticrag.app.ingest.entity.EmbeddingEntity;
import com.agenticrag.app.rag.model.TextChunk;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkKnowledgeBaseMaterialization {
	private final List<com.agenticrag.app.ingest.entity.KnowledgeEntity> knowledgeEntities;
	private final List<com.agenticrag.app.ingest.entity.ChunkEntity> chunkEntities;
	private final List<TextChunk> indexChunks;
	private final List<BenchmarkEmbeddingInput> embeddingInputs;

	public BenchmarkKnowledgeBaseMaterialization(
		List<com.agenticrag.app.ingest.entity.KnowledgeEntity> knowledgeEntities,
		List<com.agenticrag.app.ingest.entity.ChunkEntity> chunkEntities,
		List<TextChunk> indexChunks,
		List<BenchmarkEmbeddingInput> embeddingInputs
	) {
		this.knowledgeEntities = knowledgeEntities;
		this.chunkEntities = chunkEntities;
		this.indexChunks = indexChunks;
		this.embeddingInputs = embeddingInputs;
	}

	public List<com.agenticrag.app.ingest.entity.KnowledgeEntity> getKnowledgeEntities() {
		return knowledgeEntities;
	}

	public List<com.agenticrag.app.ingest.entity.ChunkEntity> getChunkEntities() {
		return chunkEntities;
	}

	public List<TextChunk> getIndexChunks() {
		return indexChunks;
	}

	public List<BenchmarkEmbeddingInput> getEmbeddingInputs() {
		return embeddingInputs;
	}

	public List<EmbeddingEntity> buildEmbeddingEntities(String modelName, List<List<Double>> vectors, Instant now) {
		if (vectors == null || vectors.size() != embeddingInputs.size()) {
			throw new IllegalArgumentException("embedding vector count does not match chunk count");
		}
		List<EmbeddingEntity> out = new ArrayList<>();
		for (int i = 0; i < embeddingInputs.size(); i++) {
			BenchmarkEmbeddingInput input = embeddingInputs.get(i);
			List<Double> vector = vectors.get(i);
			EmbeddingEntity entity = new EmbeddingEntity();
			entity.setChunkId(input.getChunkId());
			entity.setKnowledgeId(input.getKnowledgeId());
			entity.setModelName(modelName);
			entity.setDimension(vector != null ? vector.size() : 0);
			entity.setVectorJson(toVectorJson(vector));
			entity.setContent(input.getContent());
			entity.setEnabled(true);
			entity.setCreatedAt(now);
			entity.setUpdatedAt(now);
			out.add(entity);
		}
		return out;
	}

	private String toVectorJson(List<Double> vector) {
		if (vector == null || vector.isEmpty()) {
			return "[]";
		}
		StringBuilder builder = new StringBuilder();
		builder.append('[');
		for (int i = 0; i < vector.size(); i++) {
			if (i > 0) {
				builder.append(',');
			}
			Double value = vector.get(i);
			builder.append(value != null ? value : 0.0d);
		}
		builder.append(']');
		return builder.toString();
	}
}
