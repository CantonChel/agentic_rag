package com.agenticrag.app.rag.splitter;

import com.agenticrag.app.rag.model.Document;
import com.agenticrag.app.rag.model.TextChunk;
import java.util.List;

public interface TextSplitter {
	List<TextChunk> split(Document doc);
}

