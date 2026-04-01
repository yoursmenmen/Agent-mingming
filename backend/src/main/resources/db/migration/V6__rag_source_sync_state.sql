CREATE TABLE IF NOT EXISTS rag_source_sync_state (
  source_id VARCHAR(256) PRIMARY KEY,
  etag VARCHAR(512),
  last_modified VARCHAR(128),
  last_doc_hash VARCHAR(128),
  last_checked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_success_at TIMESTAMPTZ,
  last_status VARCHAR(64) NOT NULL DEFAULT 'UNKNOWN',
  last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_rag_source_sync_state_checked_at
  ON rag_source_sync_state (last_checked_at DESC);
