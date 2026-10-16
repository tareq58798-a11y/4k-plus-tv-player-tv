create table if not exists devices (
  id bigserial primary key,
  mac text unique not null,
  device_key text not null,
  status text not null default 'pending',       -- 'pending' | 'assigned'
  playlist_type text,                           -- 'm3u' | 'xtream'
  playlist_name text,
  m3u_url text,
  xtream_server text,
  xtream_username text,
  xtream_password text,
  first_seen timestamptz not null default now(),
  last_seen timestamptz not null default now()
);

-- Playlist 2. Added by the server itself at start-up (db.ensureSchema), so an existing database
-- gains them without anyone running this file; listed here so a fresh one matches. Additive only.
alter table devices
  add column if not exists playlist2_type text,                   -- 'm3u' | 'xtream'
  add column if not exists playlist2_name text,
  add column if not exists playlist2_m3u_url text,
  add column if not exists playlist2_xtream_server text,
  add column if not exists playlist2_xtream_username text,
  add column if not exists playlist2_xtream_password text;
