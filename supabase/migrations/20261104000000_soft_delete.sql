alter table public.exercise_sessions add column if not exists deleted_at timestamptz;
alter table public.sleep_sessions add column if not exists deleted_at timestamptz;
alter table public.weight_entries add column if not exists deleted_at timestamptz;

drop view if exists public.weekly_exercise;

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
where deleted_at is null
group by user_id, week_start;

revoke all on public.weekly_exercise from anon;
grant select on public.weekly_exercise to authenticated;
