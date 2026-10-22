CREATE TABLE tasks (
 id UUID PRIMARY KEY, project_id VARCHAR(100) NOT NULL, name VARCHAR(200) NOT NULL,
 kind VARCHAR(32) NOT NULL CHECK (kind IN ('CLUSTER','VM','ENVIRONMENT','DEPLOYMENT','DISTRIBUTION')),
 type VARCHAR(64) NOT NULL, mode VARCHAR(16) NOT NULL CHECK (mode IN ('SIMULATED','REAL')),
 version BIGINT NOT NULL DEFAULT 1, status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
 config JSONB NOT NULL, confirmed_version BIGINT, confirmed_snapshot JSONB,
 schedule_enabled BOOLEAN NOT NULL DEFAULT FALSE, next_run_at TIMESTAMPTZ,
 created_by VARCHAR(200) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(project_id, name)
);
CREATE INDEX tasks_project_kind ON tasks(project_id,kind,created_at DESC);
CREATE INDEX tasks_due ON tasks(next_run_at) WHERE schedule_enabled;
CREATE TABLE task_runs (
 id UUID PRIMARY KEY, task_id UUID NOT NULL REFERENCES tasks(id), project_id VARCHAR(100) NOT NULL,
 task_version BIGINT NOT NULL, mode VARCHAR(16) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'QUEUED', progress INTEGER NOT NULL DEFAULT 0,
 snapshot JSONB NOT NULL, actor_snapshot JSONB NOT NULL,
 idempotency_key VARCHAR(200) NOT NULL, request_hash VARCHAR(64) NOT NULL,
 worker_id VARCHAR(200), fencing_token BIGINT NOT NULL DEFAULT 0,
 lease_until TIMESTAMPTZ, heartbeat_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ,
 UNIQUE(project_id,idempotency_key)
);
CREATE INDEX task_runs_queue ON task_runs(created_at) WHERE status='QUEUED';
CREATE INDEX task_runs_project ON task_runs(project_id,created_at DESC);
CREATE UNIQUE INDEX task_runs_one_active ON task_runs(task_id) WHERE status IN ('QUEUED','RUNNING','UNKNOWN');
CREATE TABLE task_run_targets (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES task_runs(id), target_id VARCHAR(200) NOT NULL,
 name VARCHAR(200) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 result JSONB NOT NULL DEFAULT '{}', updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(run_id,target_id)
);
CREATE TABLE task_run_logs (
 sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, run_id UUID NOT NULL REFERENCES task_runs(id),
 target_id VARCHAR(200), level VARCHAR(16) NOT NULL, message TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX task_run_logs_run ON task_run_logs(run_id,sequence);
-- Simulation outputs are deliberately separated from observed or managed real assets.
CREATE TABLE task_simulated_resources (
 id UUID PRIMARY KEY, project_id VARCHAR(100) NOT NULL, task_id UUID NOT NULL REFERENCES tasks(id),
 kind VARCHAR(32) NOT NULL, host_id VARCHAR(200), cpu INTEGER NOT NULL DEFAULT 0,
 memory_gb INTEGER NOT NULL DEFAULT 0, disk_gb INTEGER NOT NULL DEFAULT 0,
 current_run_id UUID NOT NULL REFERENCES task_runs(id), previous_run_id UUID,
 state JSONB NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(task_id)
);
