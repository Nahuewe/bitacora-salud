create or replace function public.touch_synced_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.synced_at := now();
  return new;
end;
$$;

create table public.exercise_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  hc_id text not null check (char_length(hc_id) between 1 and 128),
  started_at timestamptz not null,
  ended_at timestamptz not null,
  exercise_type smallint not null,
  exercise_kind text not null check (exercise_kind in ('walking', 'running', 'strength', 'cycling', 'swimming', 'other')),
  title text check (char_length(title) <= 200),
  duration_s integer not null check (duration_s >= 0),
  distance_m numeric(10, 1) check (distance_m >= 0),
  avg_hr smallint check (avg_hr between 20 and 250),
  max_hr smallint check (max_hr between 20 and 250),
  kcal numeric(8, 1) check (kcal >= 0),
  source_app text check (char_length(source_app) <= 200),
  synced_at timestamptz not null default now(),
  constraint exercise_sessions_time_ck check (ended_at >= started_at),
  constraint exercise_sessions_user_hc_uq unique (user_id, hc_id)
);

create index exercise_sessions_user_started_idx on public.exercise_sessions (user_id, started_at desc);

create table public.daily_activity (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  steps integer not null check (steps between 0 and 200000),
  distance_m numeric(10, 1) check (distance_m >= 0),
  active_kcal numeric(8, 1) check (active_kcal >= 0),
  synced_at timestamptz not null default now(),
  primary key (user_id, day)
);

create table public.sleep_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  hc_id text not null check (char_length(hc_id) between 1 and 128),
  started_at timestamptz not null,
  ended_at timestamptz not null,
  asleep_min integer not null check (asleep_min >= 0),
  deep_min integer not null check (deep_min >= 0),
  rem_min integer not null check (rem_min >= 0),
  light_min integer not null check (light_min >= 0),
  awake_min integer not null check (awake_min >= 0),
  avg_hr smallint check (avg_hr between 20 and 250),
  synced_at timestamptz not null default now(),
  constraint sleep_sessions_time_ck check (ended_at >= started_at),
  constraint sleep_sessions_user_hc_uq unique (user_id, hc_id)
);

create index sleep_sessions_user_started_idx on public.sleep_sessions (user_id, started_at desc);

create table public.weight_entries (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  hc_id text not null check (char_length(hc_id) between 1 and 128),
  measured_at timestamptz not null,
  weight_kg numeric(5, 2) not null check (weight_kg between 20 and 400),
  synced_at timestamptz not null default now(),
  constraint weight_entries_user_hc_uq unique (user_id, hc_id)
);

create index weight_entries_user_measured_idx on public.weight_entries (user_id, measured_at desc);

create table public.sync_runs (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  trigger text not null check (trigger in ('manual', 'periodic')),
  started_at timestamptz not null,
  finished_at timestamptz not null,
  status text not null check (status in ('success', 'failed')),
  counts jsonb not null default '{}'::jsonb,
  error_code text check (char_length(error_code) <= 64),
  app_version text check (char_length(app_version) <= 32)
);

create index sync_runs_user_started_idx on public.sync_runs (user_id, started_at desc);

create trigger exercise_sessions_touch before update on public.exercise_sessions
  for each row execute function public.touch_synced_at();
create trigger daily_activity_touch before update on public.daily_activity
  for each row execute function public.touch_synced_at();
create trigger sleep_sessions_touch before update on public.sleep_sessions
  for each row execute function public.touch_synced_at();
create trigger weight_entries_touch before update on public.weight_entries
  for each row execute function public.touch_synced_at();

alter table public.exercise_sessions enable row level security;
alter table public.daily_activity enable row level security;
alter table public.sleep_sessions enable row level security;
alter table public.weight_entries enable row level security;
alter table public.sync_runs enable row level security;

revoke all on public.exercise_sessions, public.daily_activity, public.sleep_sessions, public.weight_entries, public.sync_runs from anon;
revoke all on public.exercise_sessions, public.daily_activity, public.sleep_sessions, public.weight_entries, public.sync_runs from authenticated;
grant select, insert, update on public.exercise_sessions, public.daily_activity, public.sleep_sessions, public.weight_entries to authenticated;
grant select, insert on public.sync_runs to authenticated;

create policy exercise_sessions_select on public.exercise_sessions for select to authenticated
  using ((select auth.uid()) = user_id);
create policy exercise_sessions_insert on public.exercise_sessions for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy exercise_sessions_update on public.exercise_sessions for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy daily_activity_select on public.daily_activity for select to authenticated
  using ((select auth.uid()) = user_id);
create policy daily_activity_insert on public.daily_activity for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy daily_activity_update on public.daily_activity for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy sleep_sessions_select on public.sleep_sessions for select to authenticated
  using ((select auth.uid()) = user_id);
create policy sleep_sessions_insert on public.sleep_sessions for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy sleep_sessions_update on public.sleep_sessions for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy weight_entries_select on public.weight_entries for select to authenticated
  using ((select auth.uid()) = user_id);
create policy weight_entries_insert on public.weight_entries for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy weight_entries_update on public.weight_entries for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create policy sync_runs_select on public.sync_runs for select to authenticated
  using ((select auth.uid()) = user_id);
create policy sync_runs_insert on public.sync_runs for insert to authenticated
  with check ((select auth.uid()) = user_id);

create view public.weekly_exercise
with (security_invoker = true)
as
select
  user_id,
  date_trunc('week', started_at at time zone 'America/Argentina/Catamarca')::date as week_start,
  count(*) as sessions,
  round(sum(duration_s) / 60.0) as minutes,
  round(sum(duration_s) filter (where avg_hr >= 135) / 60.0) as intense_minutes,
  count(*) filter (where exercise_kind = 'strength') as strength_sessions
from public.exercise_sessions
group by user_id, week_start;

revoke all on public.weekly_exercise from anon;
grant select on public.weekly_exercise to authenticated;
