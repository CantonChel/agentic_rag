package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
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
