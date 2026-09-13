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
