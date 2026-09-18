-- Gesture Mouse — website data. Run once on a fresh Supabase project.
--
-- The site talks to this with the public anon key, so the whole design is:
-- anonymous visitors may INSERT into three tables and do nothing else — no
-- reads, no updates, no deletes. The maintainer reads everything through the
-- Supabase dashboard (or the service role), which bypasses RLS.
--
-- Every column is length- and format-checked here, not just in the page's
-- JavaScript, because the anon key can call the API directly.

-- update list --------------------------------------------------------------
create table public.signups (
  id          bigint generated always as identity primary key,
  email       text not null unique
              check (char_length(email) <= 254 and email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
  consent     boolean not null check (consent),       -- the tick box, required
  source      text check (char_length(source) <= 40), -- ?src= from the link, e.g. "ig-bio"
  created_at  timestamptz not null default now()
);

-- compatibility reports ----------------------------------------------------
create table public.compat_reports (
  id               bigint generated always as identity primary key,
  phone_make       text not null check (char_length(phone_make) between 1 and 60),
  phone_model      text not null check (char_length(phone_model) between 1 and 80),
  android_version  text check (char_length(android_version) <= 20),
  app_version      text check (char_length(app_version) <= 20),
  host_os          text not null check (host_os in
                     ('Windows','macOS','Linux','ChromeOS','iPadOS','Android / TV','Other')),
  result           text not null check (result in ('works','partly','no')),
  notes            text check (char_length(notes) <= 1000),
  reviewed         boolean not null default false,  -- set in the dashboard before publishing
  created_at       timestamptz not null default now()
);

-- contact messages ---------------------------------------------------------
create table public.feedback (
  id          bigint generated always as identity primary key,
  message     text not null check (char_length(message) between 3 and 2000),
  email       text check (email is null or
                (char_length(email) <= 254 and email ~* '^[^@\s]+@[^@\s]+\.[^@\s]+$')),
  handled     boolean not null default false,
  created_at  timestamptz not null default now()
);

-- access: insert-only for the public ---------------------------------------
alter table public.signups        enable row level security;
alter table public.compat_reports enable row level security;
alter table public.feedback       enable row level security;

-- nothing but INSERT, and only on the columns a visitor may set
revoke all on public.signups, public.compat_reports, public.feedback from anon, authenticated;
grant insert (email, consent, source) on public.signups to anon;
grant insert (phone_make, phone_model, android_version, app_version, host_os, result, notes)
  on public.compat_reports to anon;
grant insert (message, email) on public.feedback to anon;

create policy "anyone can sign up"        on public.signups        for insert to anon with check (true);
create policy "anyone can report"         on public.compat_reports for insert to anon with check (true);
create policy "anyone can send a message" on public.feedback       for insert to anon with check (true);
-- no select/update/delete policies exist, so with RLS on those are refused

-- Supabase creates projects with an event-trigger helper, public.rls_auto_enable(),
-- that the security advisor flags as callable over the API. It still fires on
-- DDL without EXECUTE; this only removes direct calls.
do $$ begin
  if exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace
             where n.nspname = 'public' and p.proname = 'rls_auto_enable') then
    revoke execute on function public.rls_auto_enable() from anon, authenticated, public;
  end if;
end $$;
