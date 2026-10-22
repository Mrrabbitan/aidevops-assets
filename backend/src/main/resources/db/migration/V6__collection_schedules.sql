CREATE TABLE collection_schedules (
 id UUID PRIMARY KEY,project_id VARCHAR(100) NOT NULL,name VARCHAR(200) NOT NULL,
 kind VARCHAR(20) NOT NULL CHECK(kind IN ('DISCOVERY','SYNC')),mode VARCHAR(20) NOT NULL CHECK(mode IN ('SIMULATED','REAL')),
 version BIGINT NOT NULL DEFAULT 1,enabled BOOLEAN NOT NULL DEFAULT false,
 interval_minutes INTEGER NOT NULL CHECK(interval_minutes BETWEEN 1 AND 525600),timezone VARCHAR(100) NOT NULL,
 service_subject VARCHAR(200) NOT NULL,config JSONB NOT NULL,scope_approved_by VARCHAR(200) NOT NULL,
 next_run_at TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(project_id,name)
);
CREATE INDEX collection_schedules_due ON collection_schedules(next_run_at) WHERE enabled;
CREATE TABLE collection_runs (
 id UUID PRIMARY KEY,schedule_id UUID NOT NULL REFERENCES collection_schedules(id),project_id VARCHAR(100) NOT NULL,
 kind VARCHAR(20) NOT NULL,mode VARCHAR(20) NOT NULL,schedule_version BIGINT NOT NULL,
 snapshot JSONB NOT NULL,status VARCHAR(32) NOT NULL,actor VARCHAR(200) NOT NULL,requested_by VARCHAR(200) NOT NULL,
 result JSONB NOT NULL DEFAULT '{}',preview_id VARCHAR(100),error_code VARCHAR(100),error_message TEXT,
 worker_id VARCHAR(200),fencing_token BIGINT NOT NULL DEFAULT 0,lease_until TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),started_at TIMESTAMPTZ,finished_at TIMESTAMPTZ
);
CREATE INDEX collection_runs_project ON collection_runs(project_id,created_at DESC);
CREATE UNIQUE INDEX collection_runs_one_active ON collection_runs(schedule_id) WHERE status IN ('QUEUED','RUNNING','UNKNOWN');
