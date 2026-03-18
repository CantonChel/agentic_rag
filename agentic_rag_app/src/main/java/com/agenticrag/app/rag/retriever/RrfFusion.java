package com.agenticrag.app.rag.retriever;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RrfFusion {
	public static <T> List<T> fuse(List<T> a, List<T> b, int topK, int k) {
		if (topK <= 0) {
			return new ArrayList<>();
		}
		Map<T, Double> scores = new LinkedHashMap<>();
		add(scores, a, k);
		add(scores, b, k);
		return scores.entrySet().stream()
			.sorted(Comparator.<Map.Entry<T, Double>>comparingDouble(Map.Entry::getValue).reversed())
			.limit(topK)
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toList());
	}

	private static <T> void add(Map<T, Double> scores, List<T> list, int k) {
		if (list == null || list.isEmpty()) {
			return;
		}
		for (int i = 0; i < list.size(); i++) {
			T item = list.get(i);
			if (item == null) {
				continue;
			}
			double inc = 1.0 / (k + (i + 1));
			scores.put(item, scores.getOrDefault(item, 0.0) + inc);
		}
	}
}

