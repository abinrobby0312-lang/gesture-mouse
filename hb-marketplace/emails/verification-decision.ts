// Supabase Edge Function: verification-decision
//
// Records an admin's decision on an ID-verification request and emails the
// applicant. Before this existed there was no decision mail at all, and no
// path from verification_requests.status to profiles.verification_status --
// applicants sat on "pending" forever with no notification.
//
// POST { request_id: uuid, decision: "approved" | "rejected", note?: string }
// Auth: the caller's own JWT. Only profiles.role = 'admin' may call it.
//
// Requires secret RESEND_API_KEY. Without it the decision is still recorded
// and the response reports emailed:false, so review is never blocked on mail.

import { createClient } from "jsr:@supabase/supabase-js@2";

const FROM = Deno.env.get("VERIFICATION_FROM_EMAIL") ?? "HB <onboarding@resend.dev>";
const SITE = Deno.env.get("SITE_URL") ?? "";
const RESEND_KEY = Deno.env.get("RESEND_API_KEY");

const shell = (heading: string, accent: string, body: string) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/></head>
<body style="margin:0;padding:0;background:#f5f3f0;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background:#f5f3f0;">
<tr><td align="center" style="padding:32px 16px;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
 style="max-width:520px;background:#fff;border-radius:14px;
        font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">
<tr><td style="padding:32px 32px 0 32px;">
  <div style="font-size:20px;font-weight:700;letter-spacing:-0.3px;color:#1a1a1a;">HB</div>
  <div style="height:4px;width:44px;background:${accent};border-radius:2px;margin:18px 0 20px 0;"></div>
  <h1 style="margin:0 0 12px 0;font-size:24px;line-height:1.3;font-weight:700;color:#1a1a1a;">${heading}</h1>
</td></tr>
<tr><td style="padding:0 32px 32px 32px;">${body}</td></tr>
</table>
<p style="max-width:520px;margin:16px auto 0 auto;font-size:11px;color:#a0a0a0;
   font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif;">
  Sent by HB regarding your verification request.
</p>
</td></tr></table></body></html>`;

const button = (label: string) =>
  SITE
    ? `<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:4px 0 20px 0;">
         <tr><td bgcolor="#1a1a1a" style="border-radius:10px;">
           <a href="${SITE}" style="display:inline-block;padding:14px 32px;font-size:15px;
              font-weight:600;color:#fff;text-decoration:none;border-radius:10px;">${label}</a>
         </td></tr></table>`
    : "";

const p = (t: string) =>
  `<p style="margin:0 0 16px 0;font-size:15px;line-height:1.6;color:#4a4a4a;">${t}</p>`;

const escapeHtml = (s: string) =>
  s.replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));

function approvedEmail(name: string) {
  return {
    subject: "You're verified on HB",
    html: shell("You're verified", "#3f7d5a",
      p(`Hi ${escapeHtml(name)}, your ID check came back clear — your account is verified.`) +
      p("You can now list a homeboy, express interest in listings, and message your matches.") +
      button("Open HB") +
      p(`<span style="font-size:13px;color:#8a8a8a;">Thanks for helping keep HB a vouched-for space.
         The documents you uploaded are no longer needed for review.</span>`)),
  };
}

function rejectedEmail(name: string, note: string | null) {
  return {
    subject: "About your HB verification",
    html: shell("We couldn't verify your account", "#b0623f",
      p(`Hi ${escapeHtml(name)}, we reviewed your verification request and weren't able to approve it.`) +
      (note
        ? `<table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
             style="background:#f7f6f4;border-radius:10px;margin:0 0 16px 0;">
             <tr><td style="padding:16px 20px;">
               <div style="font-size:12px;text-transform:uppercase;letter-spacing:0.6px;
                           color:#8a8a8a;margin-bottom:6px;">Reviewer note</div>
               <div style="font-size:14px;line-height:1.6;color:#3a3a3a;">${escapeHtml(note)}</div>
             </td></tr></table>`
        : "") +
      p("Most rejections are down to a blurry photo, a cropped document, or a selfie that doesn't clearly match the ID. You're welcome to submit again.") +
      button("Try again") +
      p(`<span style="font-size:13px;color:#8a8a8a;">Replying to this email reaches the review team if you think this was a mistake.</span>`)),
  };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") {
    return Response.json({ error: "POST only" }, { status: 405 });
  }

  const authHeader = req.headers.get("Authorization") ?? "";
  const url = Deno.env.get("SUPABASE_URL")!;

  // caller-scoped client: RLS applies, so this identifies who is asking
  const caller = createClient(url, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: authHeader } },
  });
  const { data: { user }, error: userErr } = await caller.auth.getUser();
  if (userErr || !user) return Response.json({ error: "not signed in" }, { status: 401 });

  const admin = createClient(url, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);

  const { data: me } = await admin
    .from("profiles").select("role").eq("id", user.id).single();
  if (me?.role !== "admin") {
    return Response.json({ error: "admins only" }, { status: 403 });
  }

  let body: { request_id?: string; decision?: string; note?: string };
  try { body = await req.json(); }
  catch { return Response.json({ error: "invalid JSON body" }, { status: 400 }); }

  const { request_id, decision, note } = body;
  if (!request_id || (decision !== "approved" && decision !== "rejected")) {
    return Response.json(
      { error: "request_id and decision ('approved'|'rejected') are required" },
      { status: 400 });
  }

  // Record the decision. The verification_apply_status trigger mirrors this
  // onto profiles.verification_status.
  const { data: updated, error: updErr } = await admin
    .from("verification_requests")
    .update({ status: decision, review_note: note ?? null, reviewer_id: user.id })
    .eq("id", request_id)
    .select("user_id, status")
    .single();

  if (updErr || !updated) {
    return Response.json({ error: updErr?.message ?? "request not found" }, { status: 400 });
  }

  const { data: applicant } = await admin
    .from("profiles").select("display_name").eq("id", updated.user_id).single();
  const { data: authUser } = await admin.auth.admin.getUserById(updated.user_id);
  const to = authUser?.user?.email;
  const name = applicant?.display_name ?? "there";

  if (!RESEND_KEY) {
    return Response.json({ ok: true, decision, emailed: false, reason: "RESEND_API_KEY not set" });
  }
  if (!to) {
    return Response.json({ ok: true, decision, emailed: false, reason: "no email on account" });
  }

  const mail = decision === "approved"
    ? approvedEmail(name)
    : rejectedEmail(name, note ?? null);

  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${RESEND_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ from: FROM, to, subject: mail.subject, html: mail.html }),
  });

  if (!res.ok) {
    // The decision is already recorded; surface the mail failure without undoing it.
    return Response.json(
      { ok: true, decision, emailed: false, reason: await res.text() },
      { status: 200 });
  }

  return Response.json({ ok: true, decision, emailed: true });
});
