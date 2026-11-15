-- B-28: agent runs and their steps (ADR-0016 point 6). On the pilot branch: renumber when it is merged into main.

CREATE TABLE agent_run (
    id                     uuid PRIMARY KEY,
    organization_id        uuid          NOT NULL,
    store_id               uuid          NOT NULL,
    -- One analysis of one snapshot; attempts (primary model, fallback model) share it.
    analysis_key           text          NOT NULL,
    attempt                integer       NOT NULL CHECK (attempt >= 1),
    opportunity_id         uuid          NOT NULL,
    context_snapshot_id    uuid          NOT NULL,
    agent_name             text          NOT NULL,
    agent_version          text          NOT NULL,
    skill_ref              text          NOT NULL,
    knowledge_hash         text          NOT NULL,
    prompt_hash            text          NOT NULL,
    tools_hash             text          NOT NULL,
    output_schema_version  text          NOT NULL,
    model_policy           text          NOT NULL,
    provider               text          NOT NULL,
    model                  text          NOT NULL,
    price_list             text          NOT NULL,
    status                 text          NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'LIMIT_EXCEEDED',
                                                                    'OUTPUT_INVALID', 'MODEL_FAILED', 'REFUSED',
                                                                    'STOPPED', 'OUTPUT_REJECTED')),
    stop_reason            text,
    model_calls            integer       NOT NULL DEFAULT 0,
    tool_calls             integer       NOT NULL DEFAULT 0,
    repair_calls           integer       NOT NULL DEFAULT 0,
    input_tokens           integer       NOT NULL DEFAULT 0,
    output_tokens          integer       NOT NULL DEFAULT 0,
    usage_complete         boolean       NOT NULL DEFAULT true,
    estimated_cost_usd     numeric(12, 6) NOT NULL DEFAULT 0,
    output                 jsonb,
    started_at             timestamptz   NOT NULL,
    finished_at            timestamptz,
    UNIQUE (organization_id, store_id, analysis_key, attempt),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (opportunity_id, store_id) REFERENCES opportunity (id, store_id),
    FOREIGN KEY (context_snapshot_id, store_id) REFERENCES context_snapshot (id, store_id)
);

CREATE INDEX agent_run_opportunity ON agent_run (store_id, opportunity_id, started_at);

CREATE TABLE agent_run_step (
    run_id          uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    store_id        uuid        NOT NULL,
    seq             integer     NOT NULL CHECK (seq >= 1),
    kind            text        NOT NULL CHECK (kind IN ('MODEL_CALL', 'TOOL_CALL')),
    name            text        NOT NULL,
    tool_call_id    text,
    status          text        NOT NULL,
    error_code      text,
    -- Canonical step: model text and tool calls with usage, or tool arguments and result. No system prompt.
    payload         jsonb       NOT NULL,
    duration_ms     bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    PRIMARY KEY (run_id, seq),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (run_id, store_id) REFERENCES agent_run (id, store_id)
);

ALTER TABLE decision_proposal
    ADD FOREIGN KEY (agent_run_id, store_id) REFERENCES agent_run (id, store_id);
