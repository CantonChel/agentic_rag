package com.agenticrag.app.rag.pipeline;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.loader.DocumentLoader;
import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.agenticrag.app.rag.splitter.TextSplitter;
import com.agenticrag.app.rag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RagPipeline {
	private final DocumentLoader documentLoader;
	private final TextSplitter textSplitter;
	private final EmbeddingModel embeddingModel;
	private final VectorStore vectorStore;
	private final List<ChunkIndexer> chunkIndexers;

	public RagPipeline(
		DocumentLoader documentLoader,
		TextSplitter textSplitter,
		EmbeddingModel embeddingModel,
		VectorStore vectorStore,
		List<ChunkIndexer> chunkIndexers
	) {
		this.documentLoader = documentLoader;
		this.textSplitter = textSplitter;
		this.embeddingModel = embeddingModel;
		this.vectorStore = vectorStore;
		this.chunkIndexers = chunkIndexers;
	}

	public IngestResult ingest() {
		List<Document> docs = documentLoader.load();
		return ingestDocuments(docs);
	}

	public IngestResult ingestDocuments(List<Document> docs) {
		if (docs == null || docs.isEmpty()) {
			return new IngestResult(0, 0);
		}

		List<TextChunk> allChunks = new ArrayList<>();
		for (Document d : docs) {
			List<TextChunk> chunks = textSplitter.split(d);
			if (chunks != null) {
				allChunks.addAll(chunks);
			}
		}

		if (allChunks.isEmpty()) {
			return new IngestResult(docs.size(), 0);
		}

		List<String> texts = allChunks.stream().map(TextChunk::getText).collect(Collectors.toList());
		List<List<Double>> embeddings = embeddingModel.embedTexts(texts);
		for (int i = 0; i < allChunks.size(); i++) {
			List<Double> emb = embeddings != null && i < embeddings.size() ? embeddings.get(i) : null;
			allChunks.get(i).setEmbedding(emb);
		}

		if (chunkIndexers != null) {
			for (ChunkIndexer idx : chunkIndexers) {
				if (idx != null) {
					idx.addChunks(allChunks);
				}
			}
		}
		return new IngestResult(docs.size(), allChunks.size());
	}

	public List<TextChunk> search(String query, int topK) {
		if (query == null || query.trim().isEmpty()) {
			return new ArrayList<>();
		}
		List<List<Double>> q = embeddingModel.embedTexts(java.util.Collections.singletonList(query));
		List<Double> qe = q != null && !q.isEmpty() ? q.get(0) : null;
		return vectorStore.similaritySearch(qe, topK);
	}

	public static class IngestResult {
		private final int documents;
		private final int chunks;

		public IngestResult(int documents, int chunks) {
			this.documents = documents;
			this.chunks = chunks;
		}

		public int getDocuments() {
			return documents;
		}

		public int getChunks() {
			return chunks;
		}
	}
}
