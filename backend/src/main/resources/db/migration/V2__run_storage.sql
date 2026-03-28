-- Run/session storage (MVP)

CREATE TABLE chat_session (
  id UUID PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  title TEXT NULL
);

CREATE TABLE agent_run (
  id UUID PRIMARY KEY,
  session_id UUID NOT NULL REFERENCES chat_session(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  model TEXT NOT NULL,
  temperature DOUBLE PRECISION NULL,
  top_p DOUBLE PRECISION NULL,
  system_prompt_version TEXT NULL
);

CREATE TABLE run_event (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES agent_run(id),
  seq INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  type TEXT NOT NULL,
  payload JSONB NOT NULL
);

CREATE UNIQUE INDEX run_event_run_seq_uq ON run_event(run_id, seq);
CREATE INDEX run_event_run_idx ON run_event(run_id);
