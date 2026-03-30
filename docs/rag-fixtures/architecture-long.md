# Agent Architecture Notes

## Overview

This document describes the architecture used by the agent MVP. The backend receives
chat requests and orchestrates model calls, while the frontend renders stream updates
and historical run events. Data persistence is focused on observability and replay.

## Request Flow

1. The client posts a message to the chat stream endpoint.
2. The backend creates a new session and run record.
3. Retrieval executes over local docs chunks using BM25 scoring.
4. A retrieval event is stored before model completion.
5. Model response is streamed back and persisted as run events.

## Retrieval Event Shape

Retrieval event payloads are JSON objects with:

- `query`: original user question.
- `hitCount`: number of matched chunks.
- `hits`: list of hit objects where each item may include `docPath`, `headingPath`,
  `snippet`, `chunkId`, and `score`.

The first hit often provides the best representative source path for timeline summaries.

## Frontend Timeline Guidance

Timeline cards should avoid dumping raw payload JSON when the event type is
`RETRIEVAL_RESULT`. A human-readable summary should indicate whether retrieval found
hits and include the query plus a representative document path.

## Non-goals

- Full citation rendering in chat bubbles.
- Cross-run retrieval aggregation.
- Vector database integration.
