CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS doc_chunk (
  chunk_id VARCHAR(64) PRIMARY KEY,
  doc_path VARCHAR(512) NOT NULL,
  heading_path VARCHAR(512) NOT NULL,
  content TEXT NOT NULL,
  content_hash VARCHAR(128) NOT NULL,
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS doc_chunk_embedding (
  chunk_id VARCHAR(64) PRIMARY KEY REFERENCES doc_chunk(chunk_id) ON DELETE CASCADE,
  embedding vector(1536) NOT NULL,
  embedding_model VARCHAR(128) NOT NULL,
  embedding_version VARCHAR(64) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_doc_chunk_doc_path ON doc_chunk (doc_path);
CREATE INDEX IF NOT EXISTS idx_doc_chunk_is_deleted ON doc_chunk (is_deleted);
CREATE INDEX IF NOT EXISTS idx_doc_chunk_embedding_ivfflat
  ON doc_chunk_embedding USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
