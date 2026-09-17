# hb-marketplace — post-login and verification fixes

Backend fixes for the HB marketplace (Supabase project `ooipifeiymybepdbsufe`).
The app's frontend is not in this repository — only the database migration,
the Auth email template, and the verification-decision edge function live here.

## What was broken

**1. Listing cards broke after login.** `homeboys` was readable by any signed-in
woman, but `profiles` had no policy letting her read the profile of the woman who
posted a listing. A PostgREST embed on `listed_by` therefore came back `null` for
every row, so anything rendering "listed by …" got a null reference on the
marketplace page — immediately after login, for every user.

**2. Verification could never be granted.** Nothing propagated
`verification_requests.status` to `profiles.verification_status`, and the
`profiles_guard_privileges` trigger rejected any non-`service_role` attempt to set
it. An admin approving a request in the app changed nothing, so no account could
reach `approved` — and `is_verified_woman()` gates listing, expressing interest,
and messaging. One applicant had been stuck on `pending` since 16 Sep.

**3. Privilege escalation (found while fixing the above).** `profiles_insert_self`
checked `verification_status` but not `role`, and the guard trigger only covered
`UPDATE`. Any signed-up user without a profile row could insert their own with
`role = 'admin'`, which `private.is_admin()` then honoured — granting read access
to every profile, interest, and match, plus the ability to approve verifications.
Verified by simulated insert against live RLS; the insert was accepted and stored
`role=admin`. Now blocked at both the policy and the trigger.

Also hardened: `handle_new_user` silently created no profile when signup metadata
lacked a `display_name`, leaving the account profile-less after login. It now
falls back to other metadata keys and then the email local-part.

## Applying

`migrations/20260917_fix_post_login_marketplace_and_verification_flow.sql` is
already applied to the live project. It is idempotent and safe to re-run.

## Auth email template

Paste `emails/confirm-signup.html` into
**Authentication → Emails → Confirm signup** in the Supabase dashboard.

Beyond branding, it adds a `{{ .Token }}` code fallback next to the magic link.
Corporate mail scanners and link-preview fetchers routinely consume Supabase's
one-time confirmation link before the recipient clicks it, which is what produces
"link expired / invalid" on a first click. The code gives those users a way
through. Pair it with `verifyOtp({ type: 'signup', token, email })` on an
`/auth/confirm` route.

Check that **Site URL** and **Redirect URLs** list your real origins — a redirect
not on the allowlist silently falls back to Site URL and looks like a broken login.

## Verification decision email

`emails/verification-decision.ts` is deployed as edge function
`verification-decision`. It records an admin's decision and emails the applicant
(approved, or rejected with the reviewer's note).

```
POST /functions/v1/verification-decision
Authorization: Bearer <admin's JWT>
{ "request_id": "<uuid>", "decision": "approved" | "rejected", "note": "optional" }
```

Admin-only: it resolves the caller from their JWT and checks `profiles.role`.
The DB trigger mirrors the decision onto `profiles.verification_status`.

### Required before mail sends

Set these secrets (Edge Functions → Secrets):

| Secret | Purpose |
| --- | --- |
| `RESEND_API_KEY` | Resend API key. **Without it the decision is still recorded** and the response returns `emailed:false` — review is never blocked on mail. |
| `VERIFICATION_FROM_EMAIL` | e.g. `HB <verify@yourdomain.com>`. Defaults to Resend's sandbox sender, which only delivers to your own address. |
| `SITE_URL` | Makes the call-to-action button render. Omitted → button is left out. |

Swap the `api.resend.com` call if you use a different provider; the templates are
provider-agnostic HTML.

## Verification

Each fix was confirmed against live RLS in a rolled-back transaction:

- admin self-insert now rejected; normal `woman`/`man` signup insert still works
- a signed-in woman now resolves each listing's lister (`Dre → Seed Girl`, etc.)
- an admin approval flips the applicant's profile to `approved`, stamps
  `reviewer_id`/`reviewed_at`, and makes `is_verified_woman()` return true

No new Supabase security advisories were introduced.
