package com.agenticrag.app.rag.embedding;

import java.util.List;

public interface EmbeddingModel {
	List<List<Double>> embedTexts(List<String> texts);
}

