-- Protect physical identifiers against concurrent create/update races.
create unique index uq_assets_serial_active on assets(project_id,mode,lower(btrim(technical_json::jsonb->>'serial'))) where deleted=false and admission<>'INACTIVE' and coalesce(btrim(technical_json::jsonb->>'serial'),'')<>'';
create unique index uq_assets_mac_active on assets(project_id,mode,regexp_replace(lower(technical_json::jsonb->>'mac'),'[^0-9a-f]','','g')) where deleted=false and admission<>'INACTIVE' and coalesce(btrim(technical_json::jsonb->>'mac'),'')<>'';
