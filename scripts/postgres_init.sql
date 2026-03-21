-- pgvector + BM25 indexes for agentic_rag_app
-- Run as a superuser or a role with CREATE EXTENSION privilege.

CREATE EXTENSION IF NOT EXISTS vector;

-- Full-text index for BM25 (used by PostgresBm25Retriever)
CREATE INDEX IF NOT EXISTS idx_chunk_fts
ON chunk
USING GIN (to_tsvector('simple', content));

-- Vector index for pgvector (used by PostgresVectorStore)
-- NOTE: ivfflat requires ANALYZE after bulk load to build optimal lists.
CREATE INDEX IF NOT EXISTS idx_embedding_vector_l2
ON embedding
USING ivfflat (vector_json::vector);

ANALYZE chunk;
ANALYZE embedding;
