package com.agenticrag.app.rag.retriever;

import com.agenticrag.app.rag.model.TextChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@Deprecated
@ConditionalOnProperty(name = "rag.retriever.postgres.enabled", havingValue = "false")
public class LuceneBm25Retriever implements Retriever, ChunkIndexer {
	private final Directory directory = new RAMDirectory();
	private final Analyzer analyzer = new SmartChineseAnalyzer();
	private final Map<String, TextChunk> chunksById = new ConcurrentHashMap<>();

	@Override
	public synchronized void addChunks(List<TextChunk> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return;
		}

		IndexWriter writer = null;
		try {
			IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
			cfg.setSimilarity(new BM25Similarity());
			writer = new IndexWriter(directory, cfg);

			for (TextChunk c : chunks) {
				if (c == null || c.getChunkId() == null || c.getChunkId().trim().isEmpty()) {
					continue;
				}
				String indexedChunkId = indexedChunkId(c);
				chunksById.put(indexedChunkId, c);
				Document doc = toLuceneDoc(c);
				writer.updateDocument(new Term("indexedChunkId", indexedChunkId), doc);
			}
			writer.commit();
		} catch (Exception ignored) {
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	@Override
	public synchronized List<TextChunk> retrieve(String query, int topK) {
		if (query == null || query.trim().isEmpty() || topK <= 0) {
			return new ArrayList<>();
		}

		DirectoryReader reader = null;
		try {
			reader = DirectoryReader.open(directory);
			IndexSearcher searcher = new IndexSearcher(reader);
			searcher.setSimilarity(new BM25Similarity());

			QueryParser parser = new QueryParser("text", analyzer);
			Query q = parser.parse(QueryParser.escape(query.trim()));

			TopDocs top = searcher.search(q, topK);
			List<TextChunk> out = new ArrayList<>();
			for (ScoreDoc sd : top.scoreDocs) {
				Document d = searcher.doc(sd.doc);
				String chunkId = d.get("indexedChunkId");
				TextChunk chunk = chunkId != null ? chunksById.get(chunkId) : null;
				if (chunk != null) {
					out.add(chunk);
					continue;
				}
				out.add(fromLuceneDoc(d));
			}
			return out;
		} catch (Exception ignored) {
			return new ArrayList<>();
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	@Override
	public synchronized void removeKnowledge(String knowledgeId) {
		if (knowledgeId == null || knowledgeId.trim().isEmpty()) {
			return;
		}
		String target = knowledgeId.trim();
		IndexWriter writer = null;
		try {
			IndexWriterConfig cfg = new IndexWriterConfig(analyzer);
			cfg.setSimilarity(new BM25Similarity());
			writer = new IndexWriter(directory, cfg);
			List<String> toRemove = new ArrayList<>();
			for (Map.Entry<String, TextChunk> entry : chunksById.entrySet()) {
				TextChunk chunk = entry.getValue();
				if (chunk != null && target.equals(chunk.getDocumentId())) {
					toRemove.add(entry.getKey());
				}
			}
			for (String indexedChunkId : toRemove) {
				chunksById.remove(indexedChunkId);
				writer.deleteDocuments(new Term("indexedChunkId", indexedChunkId));
			}
			writer.commit();
		} catch (Exception ignored) {
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	private Document toLuceneDoc(TextChunk c) {
		Document d = new Document();
		d.add(new StringField("indexedChunkId", indexedChunkId(c), Field.Store.YES));
		d.add(new StringField("chunkId", c.getChunkId(), Field.Store.YES));
		if (c.getDocumentId() != null) {
			d.add(new StringField("documentId", c.getDocumentId(), Field.Store.YES));
		}

		Object source = c.getMetadata() != null ? c.getMetadata().get("source") : null;
		if (source != null) {
			d.add(new StringField("source", String.valueOf(source), Field.Store.YES));
		}

		d.add(new TextField("text", c.getText() != null ? c.getText() : "", Field.Store.YES));
		return d;
	}

	private TextChunk fromLuceneDoc(Document d) {
		String chunkId = d.get("chunkId");
		String documentId = d.get("documentId");
		String text = d.get("text");
		Map<String, Object> md = new HashMap<>();
		String source = d.get("source");
		if (source != null) {
			md.put("source", source);
		}
		return new TextChunk(chunkId, documentId, text, null, md);
	}

	private String indexedChunkId(TextChunk chunk) {
		String documentId = chunk != null && chunk.getDocumentId() != null ? chunk.getDocumentId().trim() : "";
		String chunkId = chunk != null && chunk.getChunkId() != null ? chunk.getChunkId().trim() : "";
		return documentId + ":" + chunkId;
	}
}
