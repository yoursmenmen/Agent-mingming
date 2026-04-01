TRUNCATE TABLE doc_chunk_embedding;

DROP INDEX IF EXISTS idx_doc_chunk_embedding_ivfflat;

ALTER TABLE doc_chunk_embedding
  ALTER COLUMN embedding TYPE vector(1024);

CREATE INDEX IF NOT EXISTS idx_doc_chunk_embedding_ivfflat
  ON doc_chunk_embedding USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
