package com.agenticrag.app.config;

import com.agenticrag.app.rag.retriever.PostgresBm25Retriever;
import com.agenticrag.app.rag.store.PostgresVectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RetrieverStartupLogger implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(RetrieverStartupLogger.class);

	private final ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever;
	private final ObjectProvider<PostgresVectorStore> postgresVectorStore;
	private final Environment environment;

	public RetrieverStartupLogger(
		ObjectProvider<PostgresBm25Retriever> postgresBm25Retriever,
		ObjectProvider<PostgresVectorStore> postgresVectorStore,
		Environment environment
	) {
		this.postgresBm25Retriever = postgresBm25Retriever;
		this.postgresVectorStore = postgresVectorStore;
		this.environment = environment;
	}

	@Override
	public void run(ApplicationArguments args) {
		boolean pgBm25 = postgresBm25Retriever.getIfAvailable() != null;
		boolean pgVector = postgresVectorStore.getIfAvailable() != null;
		String profiles = String.join(",", environment.getActiveProfiles());
		log.info("Retriever profile: activeProfiles={}", profiles.isEmpty() ? "default" : profiles);
		log.info("Retriever selection: bm25={}, vector={}",
			pgBm25 ? "postgres" : "lucene(in-memory)",
			pgVector ? "postgres(pgvector)" : "memory");
		if (!pgBm25) {
			log.warn("BM25 retriever is in-memory (Lucene). It will reset on every restart.");
		}
	}
}
