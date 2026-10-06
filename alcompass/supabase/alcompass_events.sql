-- Alcompass activity and error log (applied to the gesture-mouse Supabase project on 2026-10-06).
-- The app may only insert; reading is for the dashboard owner.
create table public.alcompass_events (
  id bigint generated always as identity primary key,
  at timestamptz not null default now(),
  session text not null check (char_length(session) between 8 and 40),
  kind text not null check (kind in ('activity', 'error')),
  name text not null check (char_length(name) between 1 and 60),
  data jsonb not null default '{}'::jsonb check (pg_column_size(data) <= 4000),
  app_version text check (char_length(app_version) <= 20),
  platform text check (char_length(platform) <= 20),
  user_agent text check (char_length(user_agent) <= 300)
);

create index alcompass_events_at_idx on public.alcompass_events (at desc);
create index alcompass_events_kind_name_idx on public.alcompass_events (kind, name, at desc);

alter table public.alcompass_events enable row level security;

create policy "alcompass app can insert events"
  on public.alcompass_events for insert
  to anon, authenticated
  with check (at > now() - interval '5 minutes' and at < now() + interval '5 minutes');

-- Dashboards. security_invoker keeps them behind the table's RLS, so the public key cannot read them.
create view public.alcompass_daily with (security_invoker = true) as
  select date_trunc('day', at at time zone 'Asia/Kolkata')::date as day,
         kind, name, count(*) as events, count(distinct session) as sessions
  from public.alcompass_events
  group by 1, 2, 3;

create view public.alcompass_recent_errors with (security_invoker = true) as
  select at, session, name, data->>'message' as message, data->>'where' as "where",
         data->'trail' as trail, platform, user_agent, data
  from public.alcompass_events
  where kind = 'error'
  order by at desc
  limit 200;

revoke all on public.alcompass_daily, public.alcompass_recent_errors from anon, authenticated;
