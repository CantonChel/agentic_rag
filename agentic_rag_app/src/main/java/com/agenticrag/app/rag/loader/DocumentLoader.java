package com.agenticrag.app.rag.loader;

import com.agenticrag.app.rag.model.Document;
import java.util.List;

public interface DocumentLoader {
	List<Document> load();
}

