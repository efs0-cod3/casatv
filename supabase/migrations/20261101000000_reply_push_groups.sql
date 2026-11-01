-- Multi-TV: send_note now returns a group id (one note row per TV). Attach the phone's
-- push subscription to every note in the group, so whichever TV answers notifies it.

create or replace function public.note_push(p_send_key text, p_id uuid, p_sub jsonb)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare n record; hit boolean := false;
begin
  if not push_endpoint_ok(p_sub) then raise exception 'bad subscription' using errcode = '22023'; end if;
  for n in
    update notes set push_sub = p_sub
      from households h
     where h.id = notes.household_id and h.send_key = p_send_key
       and (notes.group_id = p_id or notes.id = p_id)
    returning notes.id, notes.reply
  loop
    hit := true;
    if n.reply is not null then perform notify_reply_request(n.id); end if;  -- that TV was faster than us
  end loop;
  return hit;
end $$;

grant execute on function public.note_push(text, uuid, jsonb) to anon;
