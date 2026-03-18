package com.agenticrag.app.rag.store;

import java.util.List;

public class CosineSimilarity {
	public static double cosine(List<Double> a, List<Double> b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
			return 0.0;
		}
		double dot = 0.0;
		double na = 0.0;
		double nb = 0.0;
		for (int i = 0; i < a.size(); i++) {
			double av = a.get(i) != null ? a.get(i) : 0.0;
			double bv = b.get(i) != null ? b.get(i) : 0.0;
			dot += av * bv;
			na += av * av;
			nb += bv * bv;
		}
		if (na == 0.0 || nb == 0.0) {
			return 0.0;
		}
		return dot / (Math.sqrt(na) * Math.sqrt(nb));
	}
}

