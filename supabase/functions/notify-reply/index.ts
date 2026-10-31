// Sends "La TV respondió" to the phone that sent a note. Called by the notes_reply_push
// trigger (and by note_push when the reply came first). It only ever pushes a note's own
// reply to that note's own subscription, once per reply, so it needs no caller auth.
import webpush from "npm:web-push@3.6.7";
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

const VAPID_PUBLIC = "BAcUgZKWV52GtfQCoilRWVhmeJuocH77baoKtwmrK1j71nJkeJvdZtypb0Gb-q5rR4TovC7d_lvFksY1FfldmpI";
const PUSH_HOST = /^https:\/\/([a-z0-9.-]+\.)?(push\.apple\.com|fcm\.googleapis\.com|push\.services\.mozilla\.com|notify\.windows\.com)\//;

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
let vapidReady = false;

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method", { status: 405 });
  let id = "";
  try { id = String((await req.json()).id || ""); } catch { /* ignore */ }
  if (!/^[0-9a-f-]{36}$/i.test(id)) return new Response("id", { status: 400 });

  const { data: note } = await db.from("notes")
    .select("id, reply, push_sub, push_sent_reply").eq("id", id).maybeSingle();
  if (!note?.reply || !note.push_sub || note.push_sent_reply === note.reply) return new Response("skip");
  if (!PUSH_HOST.test(note.push_sub.endpoint ?? "")) return new Response("endpoint", { status: 400 });

  if (!vapidReady) {
    const { data: priv, error } = await db.rpc("vapid_private_key");
    if (error || !priv) return new Response("vapid", { status: 500 });
    webpush.setVapidDetails("https://casatv-nine.vercel.app", VAPID_PUBLIC, priv);
    vapidReady = true;
  }

  // Claim this reply so a retry or a second trigger doesn't buzz the phone twice.
  let claim = db.from("notes").update({ push_sent_reply: note.reply }).eq("id", id).eq("reply", note.reply);
  claim = note.push_sent_reply === null ? claim.is("push_sent_reply", null) : claim.eq("push_sent_reply", note.push_sent_reply);
  const { data: claimed } = await claim.select("id");
  if (!claimed?.length) return new Response("skip");

  const short = [...note.reply].length <= 2;
  try {
    await webpush.sendNotification(note.push_sub, JSON.stringify({
      title: short ? `La TV respondió ${note.reply}` : "La TV respondió",
      body: short ? "a tu nota" : note.reply,
      tag: id,
    }), { TTL: 86400, urgency: "high" });
  } catch (e) {
    const code = (e as { statusCode?: number }).statusCode;
    if (code === 404 || code === 410) await db.from("notes").update({ push_sub: null }).eq("id", id);
    return new Response("push failed", { status: 502 });
  }
  return new Response("ok");
});
