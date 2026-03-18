package com.agenticrag.app.rag.splitter;

import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecursiveCharacterTextSplitterTest {
	@Test
	void splitsWithOverlap() {
		TokenCounter counter = text -> text != null ? text.length() : 0;

		RecursiveCharacterTextSplitterProperties props = new RecursiveCharacterTextSplitterProperties();
		props.setChunkSize(20);
		props.setChunkOverlap(5);

		RecursiveCharacterTextSplitter splitter = new RecursiveCharacterTextSplitter(counter, props);

		String content = "第一段。\n\n第二段很长很长很长。\n第三段。";
		Document doc = new Document("d1", content, new HashMap<String, Object>());

		List<TextChunk> chunks = splitter.split(doc);
		Assertions.assertTrue(chunks.size() >= 2);

		for (TextChunk c : chunks) {
			Assertions.assertTrue(counter.count(c.getText()) <= 20);
		}
	}
}
