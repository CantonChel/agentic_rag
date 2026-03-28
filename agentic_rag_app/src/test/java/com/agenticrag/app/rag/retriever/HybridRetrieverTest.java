package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.benchmark.retrieval.RetrievalTraceCollector;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceRecord;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceRecordType;
import com.agenticrag.app.benchmark.retrieval.RetrievalTraceStage;
import com.agenticrag.app.rag.model.TextChunk;
import com.agenticrag.app.rag.model.TextChunkMetadataHelper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

class HybridRetrieverTest {
	@Test
	void keepsChunksFromDifferentDocumentsWhenChunkIdsMatch() {
		DenseVectorRetriever denseVectorRetriever = Mockito.mock(DenseVectorRetriever.class);
		LuceneBm25Retriever luceneBm25Retriever = Mockito.mock(LuceneBm25Retriever.class);
		PostgresBm25Retriever postgresBm25Retriever = Mockito.mock(PostgresBm25Retriever.class);
		Reranker reranker = Mockito.mock(Reranker.class);

		TextChunk left = new TextChunk("same-chunk", "knowledge-a", "left", null, Collections.emptyMap());
		TextChunk right = new TextChunk("same-chunk", "knowledge-b", "right", null, Collections.emptyMap());

		Mockito.when(denseVectorRetriever.retrieve("hello", 4, "trace-1", null)).thenReturn(Collections.singletonList(left));
		Mockito.when(postgresBm25Retriever.retrieve("hello", 4, "trace-1", null)).thenReturn(Collections.singletonList(right));
		Mockito.when(reranker.rerank(Mockito.eq("hello"), Mockito.anyList(), Mockito.eq(2)))
			.thenAnswer(invocation -> invocation.getArgument(1));

		HybridRetriever hybridRetriever = new HybridRetriever(
			denseVectorRetriever,
			providerOf(luceneBm25Retriever),
			providerOf(postgresBm25Retriever),
			reranker
		);

		List<TextChunk> out = hybridRetriever.retrieve("hello", 4, 2, "trace-1");
		Assertions.assertEquals(2, out.size());
		Assertions.assertEquals(Arrays.asList("knowledge-a", "knowledge-b"), Arrays.asList(out.get(0).getDocumentId(), out.get(1).getDocumentId()));
	}

	@Test
	void forwardsKnowledgeBaseScopeToDenseAndBm25Retrievers() {
		DenseVectorRetriever denseVectorRetriever = Mockito.mock(DenseVectorRetriever.class);
		LuceneBm25Retriever luceneBm25Retriever = Mockito.mock(LuceneBm25Retriever.class);
		Reranker reranker = Mockito.mock(Reranker.class);

		TextChunk left = new TextChunk("chunk-a", "knowledge-a", "left", null, Collections.emptyMap());
		TextChunk right = new TextChunk("chunk-b", "knowledge-b", "right", null, Collections.emptyMap());

		Mockito.when(denseVectorRetriever.retrieve("hello", 4, "trace-1", "kb-1")).thenReturn(Collections.singletonList(left));
		Mockito.when(luceneBm25Retriever.retrieve("hello", 4, "kb-1")).thenReturn(Collections.singletonList(right));
		Mockito.when(reranker.rerank(Mockito.eq("hello"), Mockito.anyList(), Mockito.eq(2)))
			.thenAnswer(invocation -> invocation.getArgument(1));

		HybridRetriever hybridRetriever = new HybridRetriever(
			denseVectorRetriever,
			providerOf(luceneBm25Retriever),
			providerOf(null),
			reranker
		);

		List<TextChunk> out = hybridRetriever.retrieve("hello", 4, 2, "trace-1", "kb-1");

		Assertions.assertEquals(2, out.size());
		Mockito.verify(denseVectorRetriever).retrieve("hello", 4, "trace-1", "kb-1");
		Mockito.verify(luceneBm25Retriever).retrieve("hello", 4, "kb-1");
	}

	@Test
	void recordsDenseBm25FuseAndRerankStagesIntoCollector() {
		DenseVectorRetriever denseVectorRetriever = Mockito.mock(DenseVectorRetriever.class);
		LuceneBm25Retriever luceneBm25Retriever = Mockito.mock(LuceneBm25Retriever.class);
		PostgresBm25Retriever postgresBm25Retriever = Mockito.mock(PostgresBm25Retriever.class);
		Reranker reranker = Mockito.mock(Reranker.class);

		TextChunk left = TextChunkMetadataHelper.withRetrievalScore(
			new TextChunk("chunk-a", "knowledge-a", "left", null, Collections.singletonMap("evidence_id", "e1")),
			0.9d
		);
		TextChunk right = TextChunkMetadataHelper.withRetrievalScore(
			new TextChunk("chunk-b", "knowledge-b", "right", null, Collections.singletonMap("evidence_id", "e2")),
			0.8d
		);

		Mockito.when(denseVectorRetriever.retrieve("hello", 4, "trace-1", "kb-1")).thenReturn(Collections.singletonList(left));
		Mockito.when(postgresBm25Retriever.retrieve("hello", 4, "trace-1", "kb-1")).thenReturn(Collections.singletonList(right));
		Mockito.when(reranker.rerank(Mockito.eq("hello"), Mockito.anyList(), Mockito.eq(2)))
			.thenReturn(Collections.singletonList(left));

		HybridRetriever hybridRetriever = new HybridRetriever(
			denseVectorRetriever,
			providerOf(luceneBm25Retriever),
			providerOf(postgresBm25Retriever),
			reranker
		);
		RetrievalTraceCollector collector = new RetrievalTraceCollector("trace-1", "call-1", "search_knowledge_base", "kb-1", "hello");

		List<TextChunk> out = hybridRetriever.retrieve("hello", 4, 2, "trace-1", "kb-1", collector);
		List<RetrievalTraceRecord> records = collector.getRecords();

		Assertions.assertEquals(1, out.size());
		Assertions.assertTrue(records.stream().anyMatch(record ->
			record.getRecordType() == RetrievalTraceRecordType.STAGE_SUMMARY
				&& record.getStage() == RetrievalTraceStage.DENSE
				&& Integer.valueOf(1).equals(record.getHitCount())
		));
		Assertions.assertTrue(records.stream().anyMatch(record ->
			record.getRecordType() == RetrievalTraceRecordType.STAGE_SUMMARY
				&& record.getStage() == RetrievalTraceStage.BM25
				&& Integer.valueOf(1).equals(record.getHitCount())
		));
		Assertions.assertTrue(records.stream().anyMatch(record ->
			record.getRecordType() == RetrievalTraceRecordType.STAGE_SUMMARY
				&& record.getStage() == RetrievalTraceStage.HYBRID_FUSED
				&& Integer.valueOf(2).equals(record.getHitCount())
		));
		Assertions.assertTrue(records.stream().anyMatch(record ->
			record.getRecordType() == RetrievalTraceRecordType.STAGE_SUMMARY
				&& record.getStage() == RetrievalTraceStage.RERANKED
				&& Integer.valueOf(1).equals(record.getHitCount())
		));
	}

	private <T> ObjectProvider<T> providerOf(T value) {
		return new ObjectProvider<T>() {
			@Override
			public T getObject(Object... args) {
				return value;
			}

			@Override
			public T getIfAvailable() {
				return value;
			}

			@Override
			public T getIfUnique() {
				return value;
			}

			@Override
			public T getObject() {
				return value;
			}
		};
	}
}
