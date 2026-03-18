package com.agenticrag.app.rag.pipeline;

import com.agenticrag.app.rag.embedding.EmbeddingModel;
import com.agenticrag.app.rag.loader.DocumentLoader;
import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.retriever.ChunkIndexer;
import com.agenticrag.app.rag.splitter.TextSplitter;
import com.agenticrag.app.rag.store.InMemoryVectorStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RagPipelineIngestDocumentsTest {
	@Test
	void ingestsDocumentsIntoVectorStore() {
		DocumentLoader loader = () -> Collections.emptyList();

		TextSplitter splitter = doc -> Arrays.asList(
			new TextChunk(doc.getId() + ":0", doc.getId(), "hello", null, new HashMap<String, Object>()),
			new TextChunk(doc.getId() + ":1", doc.getId(), "world", null, new HashMap<String, Object>())
		);

		EmbeddingModel embedding = texts -> Arrays.asList(
			Arrays.asList(1.0, 0.0),
			Arrays.asList(0.0, 1.0)
		);

		InMemoryVectorStore store = new InMemoryVectorStore();

		List<ChunkIndexer> indexers = Arrays.asList(store);
		RagPipeline pipeline = new RagPipeline(loader, splitter, embedding, store, indexers);

		Document doc = new Document("d1", "ignored", new HashMap<String, Object>());
		RagPipeline.IngestResult res = pipeline.ingestDocuments(Collections.singletonList(doc));

		Assertions.assertEquals(1, res.getDocuments());
		Assertions.assertEquals(2, res.getChunks());
		Assertions.assertEquals(2, store.size());
	}
}
