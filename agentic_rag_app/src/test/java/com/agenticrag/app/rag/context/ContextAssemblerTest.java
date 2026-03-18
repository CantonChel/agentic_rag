package com.agenticrag.app.rag.context;

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
}

