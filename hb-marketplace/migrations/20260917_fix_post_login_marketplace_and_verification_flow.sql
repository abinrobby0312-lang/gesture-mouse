-- Applied to Supabase project ooipifeiymybepdbsufe (hb-marketplace) on 2026-09-17.
-- Fixes three defects that broke the marketplace page after login and left
-- ID verification permanently unreachable.

-- 1. Privilege escalation: profiles_insert_self constrained verification_status
--    but not role, and the guard trigger only covered UPDATE -- so any signed-up
--    user without a profile row could insert their own with role='admin'.
drop policy if exists profiles_insert_self on public.profiles;
create policy profiles_insert_self on public.profiles
  for insert to authenticated
  with check (
    id = auth.uid()
    and verification_status = 'unverified'
    and role in ('woman', 'man')
  );

create or replace function private.guard_profile_privileges()
returns trigger language plpgsql set search_path to 'public', 'pg_temp'
as $function$
begin
  if auth.role() = 'service_role' or current_user in ('postgres', 'supabase_admin') then
    return new;
  end if;
  if tg_op = 'INSERT' then
    if new.role = 'admin' or new.verification_status <> 'unverified' then
      raise exception 'new profiles start unverified and may not self-assign admin';
    end if;
    return new;
  end if;
  if new.role is distinct from old.role
     or new.verification_status is distinct from old.verification_status then
    raise exception 'role and verification_status are set by review, not by the user';
  end if;
  return new;
end
$function$;

drop trigger if exists profiles_guard_privileges on public.profiles;
create trigger profiles_guard_privileges
  before insert or update on public.profiles
  for each row execute function private.guard_profile_privileges();

-- 2. Marketplace page: a browsing woman could read homeboys but not the profile
--    of the woman who listed them, so any PostgREST embed on listed_by returned
--    null and the listing card broke after login.
drop policy if exists profiles_select_lister on public.profiles;
create policy profiles_select_lister on public.profiles
  for select
  using (
    exists (
      select 1 from public.homeboys h
      where h.listed_by = profiles.id
        and h.archived = false
        and (private.is_woman(auth.uid()) or h.man_user_id = auth.uid())
    )
  );

-- 3. Verification dead end: nothing propagated an approved verification_request
--    to profiles.verification_status, and the guard trigger blocked admins from
--    setting it by hand, so 'approved' was unreachable through the app.
create or replace function private.stamp_verification_review()
returns trigger language plpgsql set search_path to 'public', 'pg_temp'
as $function$
begin
  if new.status is distinct from old.status then
    new.reviewed_at := now();
    new.reviewer_id := coalesce(new.reviewer_id, auth.uid());
  end if;
  return new;
end
$function$;

create or replace function private.apply_verification_status()
returns trigger language plpgsql security definer set search_path to 'public', 'pg_temp'
as $function$
begin
  if tg_op = 'INSERT' then
    update profiles set verification_status = 'pending'
     where id = new.user_id and verification_status = 'unverified';
    return new;
  end if;
  if new.status is distinct from old.status
     and new.status in ('approved', 'rejected', 'pending') then
    update profiles set verification_status = new.status where id = new.user_id;
  end if;
  return new;
end
$function$;

drop trigger if exists verification_stamp_review on public.verification_requests;
create trigger verification_stamp_review
  before update on public.verification_requests
  for each row execute function private.stamp_verification_review();

drop trigger if exists verification_apply_status on public.verification_requests;
create trigger verification_apply_status
  after insert or update on public.verification_requests
  for each row execute function private.apply_verification_status();

-- 4. handle_new_user silently created no profile when signup metadata lacked a
--    display_name, leaving the user profile-less (and the page empty) after login.
create or replace function private.handle_new_user()
returns trigger language plpgsql security definer set search_path to 'public', 'pg_temp'
as $function$
declare
  requested_role text := new.raw_user_meta_data->>'role';
  requested_name text := nullif(trim(coalesce(
    new.raw_user_meta_data->>'display_name',
    new.raw_user_meta_data->>'full_name',
    new.raw_user_meta_data->>'name', '')), '');
begin
  if coalesce(requested_role, '') not in ('woman', 'man') then
    return new;
  end if;
  insert into profiles (id, role, display_name, verification_status)
  values (new.id, requested_role::user_role,
          left(coalesce(requested_name, split_part(new.email, '@', 1)), 80),
          'unverified')
  on conflict (id) do nothing;
  return new;
end
$function$;

-- 5. Backfill: reconcile profiles with existing verification requests.
update public.profiles p
   set verification_status = v.status
  from public.verification_requests v
 where v.user_id = p.id
   and p.role <> 'admin'
   and p.verification_status is distinct from v.status;
