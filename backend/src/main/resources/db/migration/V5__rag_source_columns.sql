ALTER TABLE doc_chunk
  ADD COLUMN source_type VARCHAR(64) NOT NULL DEFAULT 'local_docs';

ALTER TABLE doc_chunk
  ADD COLUMN source_id VARCHAR(256) NOT NULL DEFAULT 'local:legacy';

UPDATE doc_chunk
SET source_id = 'local:' || doc_path
WHERE source_type = 'local_docs';

CREATE INDEX IF NOT EXISTS idx_doc_chunk_source_type ON doc_chunk (source_type);
CREATE INDEX IF NOT EXISTS idx_doc_chunk_source_id ON doc_chunk (source_id);
