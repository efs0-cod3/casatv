-- New TV app (tv/): pairs with a QR instead of carrying the household's tv_key in the
-- APK, and takes remote-control commands from the phone.

-- Pairing -------------------------------------------------------------------------------
-- 1. The TV makes a random code and registers it (tv_pair_start), then shows it as a QR.
-- 2. The phone, which already has the send key, opens the QR and names the TV (tv_pair_confirm).
-- 3. The TV polls tv_pair_claim and receives the household's tv_key once.

create table if not exists tv_pairings (
  code text primary key,
  device_id text not null,
  household_id uuid references households(id) on delete cascade,
  tv_id uuid references tvs(id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table tv_pairings enable row level security;

create or replace function public.tv_pair_start(p_code text, p_device_id text)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
begin
  if p_code !~ '^[A-Za-z0-9]{24,64}$' or coalesce(length(trim(p_device_id)), 0) not between 8 and 64 then
    raise exception 'bad code' using errcode = '22023';
  end if;
  delete from tv_pairings where created_at < now() - interval '30 minutes';
  if (select count(*) from tv_pairings) >= 50 then
    raise exception 'too many pairings' using errcode = '54000';
  end if;
  insert into tv_pairings (code, device_id) values (p_code, trim(p_device_id))
    on conflict (code) do nothing;
  return true;
end $$;

-- Called by the phone. Reuses a TV row with the same name when that TV has gone quiet
-- (an old app being replaced on the same TV), so the TV keeps its place and its notes.
create or replace function public.tv_pair_confirm(p_send_key text, p_code text, p_name text)
returns json language plpgsql security definer set search_path = public, pg_temp as $$
declare
  h uuid;
  p tv_pairings;
  nm text := left(nullif(trim(p_name), ''), 24);
  old tvs;
  tv uuid;
begin
  select id into h from households where send_key = p_send_key;
  if h is null then raise exception 'invalid key' using errcode = '28000'; end if;
  if nm is null then raise exception 'name required' using errcode = '22023'; end if;
  select * into p from tv_pairings where code = p_code and created_at > now() - interval '30 minutes';
  if p.code is null then raise exception 'unknown code' using errcode = '22023'; end if;
  if p.household_id is not null then return json_build_object('ok', true, 'tv', p.tv_id); end if;

  select * into old from tvs where household_id = h and device_id = p.device_id;
  if old.id is null then
    select * into old from tvs
     where household_id = h and lower(name) = lower(nm)
       and (device_id = 'legacy' or last_seen < now() - interval '2 minutes');
    if old.id is null and exists (select 1 from tvs where household_id = h and lower(name) = lower(nm)) then
      return json_build_object('ok', false, 'error', 'name_in_use');
    end if;
  end if;

  if old.id is not null then
    update tvs set device_id = p.device_id, name = nm where id = old.id;
    tv := old.id;
  else
    insert into tvs (household_id, device_id, name, last_seen, screen_on, state_since)
    values (h, p.device_id, nm, now(), true, now()) returning id into tv;
  end if;
  update tv_pairings set household_id = h, tv_id = tv where code = p_code;
  return json_build_object('ok', true, 'tv', tv);
end $$;

-- Called by the TV until the phone confirmed. Hands over the key exactly once.
create or replace function public.tv_pair_claim(p_code text)
returns json language plpgsql security definer set search_path = public, pg_temp as $$
declare r record;
begin
  delete from tv_pairings pp
   using households h, tvs t
   where pp.code = p_code and pp.household_id is not null
     and h.id = pp.household_id and t.id = pp.tv_id
  returning h.tv_key, t.name into r;
  if r.tv_key is null then return json_build_object('paired', false); end if;
  return json_build_object('paired', true, 'tv_key', r.tv_key, 'name', r.name);
end $$;

-- Remote control ------------------------------------------------------------------------

alter table tvs add column if not exists caps text[] not null default '{}';
alter table tvs add column if not exists apps jsonb;

create table if not exists tv_commands (
  id bigint generated always as identity primary key,
  household_id uuid not null references households(id) on delete cascade,
  tv_id uuid not null references tvs(id) on delete cascade,
  cmd text not null,
  arg text,
  created_at timestamptz not null default now(),
  taken_at timestamptz
);
alter table tv_commands enable row level security;
create index if not exists tv_commands_pending on tv_commands (tv_id, id) where taken_at is null;

-- Called by the phone.
create or replace function public.remote_send(p_send_key text, p_tv uuid, p_cmd text, p_arg text default null)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare h uuid;
begin
  select id into h from households where send_key = p_send_key;
  if h is null then raise exception 'invalid key' using errcode = '28000'; end if;
  if not exists (select 1 from tvs where id = p_tv and household_id = h and 'remote' = any(caps)) then
    raise exception 'unknown tv' using errcode = '22023';
  end if;
  if p_cmd not in ('ping', 'key', 'launch', 'sleep', 'wake')
     or (p_cmd = 'key' and coalesce(p_arg, '') !~ '^[a-z_]{1,20}$')
     or (p_cmd = 'launch' and coalesce(p_arg, '') !~ '^[A-Za-z0-9_.]{3,120}$') then
    raise exception 'bad command' using errcode = '22023';
  end if;
  if (select count(*) from tv_commands where household_id = h and created_at > now() - interval '1 minute') >= 300 then
    raise exception 'too many commands' using errcode = '54000';
  end if;
  delete from tv_commands where tv_id = p_tv and created_at < now() - interval '10 minutes';
  insert into tv_commands (household_id, tv_id, cmd, arg) values (h, p_tv, p_cmd, p_arg);
  return true;
end $$;

-- Called by the TV, often while the phone's remote is open. Also reports what it can do
-- and which apps it has. Commands older than 20 s are dropped: a late "down" is worse
-- than a lost one.
create or replace function public.tv_remote(p_tv_key text, p_device_id text, p_caps text[] default null, p_apps jsonb default null)
returns table (id bigint, cmd text, arg text)
language plpgsql security definer set search_path = public, pg_temp as $$
declare h uuid; tv uuid;
begin
  select households.id into h from households where tv_key = p_tv_key;
  if h is null then raise exception 'invalid key' using errcode = '28000'; end if;
  select tvs.id into tv from tvs where tvs.household_id = h and tvs.device_id = p_device_id;
  if tv is null then return; end if;
  if p_caps is not null or p_apps is not null then
    update tvs set caps = coalesce(p_caps, caps),
                   apps = case when p_apps is null then apps
                               when jsonb_typeof(p_apps) = 'array' and length(p_apps::text) < 20000 then p_apps
                               else apps end
     where tvs.id = tv;
  end if;
  return query
    update tv_commands c set taken_at = now()
     where c.tv_id = tv and c.taken_at is null and c.created_at > now() - interval '20 seconds'
    returning c.id, c.cmd, c.arg;
end $$;

-- tv_status now also says which TVs take remote commands, and their apps.
create or replace function public.tv_status(p_send_key text)
returns json language sql stable security definer set search_path = public, pg_temp as $$
  with h as (select id from households where send_key = p_send_key),
  t as (
    select tv.id, tv.name,
           tv.screen_on and tv.last_seen > now() - interval '45 seconds' as on_now,
           case when tv.screen_on and tv.last_seen <= now() - interval '45 seconds' then tv.last_seen else tv.state_since end as since,
           tv.created_at, tv.caps, tv.apps
      from tvs tv join h on h.id = tv.household_id
     where tv.last_seen > now() - interval '30 days'
  )
  select case when not exists (select 1 from h) then json_build_object('known', false)
  else json_build_object(
    'known', exists (select 1 from t),
    'on', coalesce((select bool_or(on_now) from t), false),
    'since', (select max(since) from t),
    'tvs', coalesce((select json_agg(json_build_object('id', id, 'name', name, 'on', on_now, 'since', since,
                                                       'remote', 'remote' = any(caps), 'apps', apps) order by created_at) from t), '[]'::json)
  ) end;
$$;

grant execute on function public.tv_pair_start(text, text) to anon;
grant execute on function public.tv_pair_confirm(text, text, text) to anon;
grant execute on function public.tv_pair_claim(text) to anon;
grant execute on function public.remote_send(text, uuid, text, text) to anon;
grant execute on function public.tv_remote(text, text, text[], jsonb) to anon;
