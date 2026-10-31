-- Push notifications: "La TV respondió" reaches the phone even with the app closed.
-- The phone attaches a Web Push subscription to the note it just sent; when the TV
-- answers, a trigger asks the notify-reply edge function to deliver it.

create extension if not exists pg_net;

alter table notes add column if not exists push_sub jsonb;
alter table notes add column if not exists push_sent_reply text;

create or replace function public.push_endpoint_ok(p_sub jsonb)
returns boolean language sql immutable set search_path = public, pg_temp as $$
  select coalesce(p_sub->>'endpoint', '') ~ '^https://([a-z0-9.-]+\.)?(push\.apple\.com|fcm\.googleapis\.com|push\.services\.mozilla\.com|notify\.windows\.com)/'
     and coalesce(p_sub->'keys'->>'p256dh', '') <> ''
     and coalesce(p_sub->'keys'->>'auth', '') <> ''
     and length(p_sub::text) < 4000;
$$;

create or replace function public.notify_reply_request(p_id uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
  select net.http_post(
    url := 'https://narursszyfdkwyytgtsa.supabase.co/functions/v1/notify-reply',
    body := jsonb_build_object('id', p_id),
    headers := '{"Content-Type": "application/json"}'::jsonb
  );
$$;

-- Called by the phone right after send_note.
create or replace function public.note_push(p_send_key text, p_id uuid, p_sub jsonb)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r text;
begin
  if not push_endpoint_ok(p_sub) then raise exception 'bad subscription' using errcode = '22023'; end if;
  update notes n set push_sub = p_sub
    from households h
   where h.id = n.household_id and h.send_key = p_send_key and n.id = p_id
  returning n.reply into r;
  if not found then return false; end if;
  if r is not null then perform notify_reply_request(p_id); end if;  -- the TV was faster than us
  return true;
end $$;

create or replace function public.notes_reply_push()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
begin
  perform notify_reply_request(new.id);
  return new;
end $$;

drop trigger if exists notes_reply_push on notes;
create trigger notes_reply_push after update of reply on notes
  for each row
  when (new.reply is not null and new.reply is distinct from old.reply and new.push_sub is not null)
  execute function notes_reply_push();

-- The VAPID private key lives in Vault; only the edge function (service role) reads it.
create or replace function public.vapid_private_key()
returns text language sql stable security definer set search_path = public, pg_temp as $$
  select decrypted_secret from vault.decrypted_secrets where name = 'vapid_private_key';
$$;

revoke all on function public.vapid_private_key() from public, anon, authenticated;
grant execute on function public.vapid_private_key() to service_role;
revoke all on function public.notify_reply_request(uuid) from public, anon, authenticated;
revoke all on function public.notes_reply_push() from public, anon, authenticated;
grant execute on function public.note_push(text, uuid, jsonb) to anon;
