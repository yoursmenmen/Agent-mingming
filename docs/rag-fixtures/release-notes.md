# Release Notes

## 2026-03-30

- Added RETRIEVAL_RESULT timeline event persistence in backend run events.
- Retrieval payload now includes `query`, `hitCount`, and top-hit metadata.
- Frontend timeline shows a readable retrieval summary instead of raw JSON.

## 2026-03-20

- Introduced docs chunking for Markdown files.
- Added BM25-based retriever with threshold and top-k controls.
