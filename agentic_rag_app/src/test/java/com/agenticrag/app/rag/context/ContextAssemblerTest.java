package com.agenticrag.app.rag.context;

import com.agenticrag.app.benchmark.retrieval.RetrievalTraceCollector;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ContextAssemblerTest {
	@Test
	void assemblesContextWithSources() {
		ContextAssembler assembler = new ContextAssembler();
		HashMap<String, Object> md = new HashMap<String, Object>();
		md.put("source", "a.md");
		TextChunk c = new TextChunk("c1", "d1", "hello", null, md);
		List<TextChunk> out = Arrays.asList(c);
		String s = assembler.assemble(out);
		Assertions.assertTrue(s.contains("<context>"));
		Assertions.assertTrue(s.contains("[引用 1]"));
		Assertions.assertTrue(s.contains("来源: a.md"));
		Assertions.assertTrue(s.contains("hello"));
		Assertions.assertTrue(s.contains("</context>"));
	}

	@Test
	void assemblesSidecarForContextOutput() {
		ContextAssembler assembler = new ContextAssembler();
		HashMap<String, Object> md = new HashMap<String, Object>();
		md.put("source", "a.md");
		md.put("evidence_id", "e1");
		md.put("build_id", "build-1");
		TextChunk c = new TextChunk("c1", "d1", "hello", null, md);
		RetrievalTraceCollector collector = new RetrievalTraceCollector("trace-1", "call-1", "search_knowledge_base", "kb-1", "hello");

		ContextAssemblyResult result = assembler.assemble(Arrays.asList(c), collector);

		Assertions.assertNotNull(result);
		Assertions.assertTrue(result.getContextText().contains("[引用 1]"));
		Assertions.assertNotNull(result.getSidecar());
		Assertions.assertEquals("retrieval_context_v1", result.getSidecar().get("type").asText());
		Assertions.assertEquals(1, result.getSidecar().get("items").size());
		Assertions.assertEquals("e1", result.getSidecar().get("items").get(0).get("evidenceId").asText());
	}
}
