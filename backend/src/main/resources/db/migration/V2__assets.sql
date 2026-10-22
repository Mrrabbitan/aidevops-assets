create table assets (
 id varchar(80) primary key,
 project_id varchar(100) not null,
 name varchar(200) not null,
 type varchar(40) not null,
 category varchar(120) not null default '',
 owner_name varchar(160) not null default '',
 environment varchar(120) not null default '',
 dept varchar(160) not null default '',
 location varchar(200) not null default '',
 purpose varchar(400) not null default '',
 operation text not null default '{}',
 lifecycle text not null default '{}',
 mode varchar(24) not null,
 admission varchar(24) not null,
 resource_status varchar(40) not null default 'UNKNOWN',
 connector_id varchar(160) not null default '',
 external_id varchar(200) not null default '',
 network_scope varchar(160) not null default '',
 ip varchar(100) not null default '',
 technical_json text not null default '{}',
 manual_fields_json text not null default '{}',
 observed_at timestamptz,
 source varchar(160) not null default 'MANUAL',
 version bigint not null default 0,
 created_at timestamptz not null,
 updated_at timestamptz not null,
 deleted boolean not null default false
);
create index idx_assets_project on assets(project_id, deleted, type, admission);
create unique index uq_assets_external on assets(project_id,mode,connector_id,external_id) where external_id <> '' and deleted=false;
create unique index uq_assets_ip_scope on assets(project_id,mode,network_scope,ip) where ip <> '' and network_scope <> '' and deleted=false and admission <> 'INACTIVE';
create table asset_versions (
 id varchar(80) primary key,
 asset_id varchar(80) not null references assets(id),
 project_id varchar(100) not null,
 asset_version bigint not null,
 action varchar(80) not null,
 actor varchar(200) not null,
 snapshot_json text not null,
 created_at timestamptz not null
);
create index idx_asset_versions_asset on asset_versions(project_id,asset_id,created_at);
create table asset_previews (
 id varchar(80) primary key,
 project_id varchar(100) not null,
 kind varchar(30) not null,
 actor varchar(200) not null,
 status varchar(30) not null,
 payload_json text not null,
 result_json text not null default '{}',
 created_at timestamptz not null,
 expires_at timestamptz not null,
 version bigint not null default 0
);
create index idx_asset_previews_project on asset_previews(project_id,kind,created_at);
