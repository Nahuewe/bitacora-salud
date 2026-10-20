alter table public.daily_activity
  add column if not exists active_min integer check (active_min between 0 and 1440);

alter table public.strength_logs drop constraint if exists strength_logs_routine_id_check;
alter table public.strength_logs
  add constraint strength_logs_routine_id_check check (routine_id in ('A', 'B', 'C', 'M'));

create or replace view public.weekly_strength
with (security_invoker = true)
as
select
  user_id,
  date_trunc('week', day)::date as week_start,
  count(*) filter (where routine_id in ('A', 'B')) as strength_sessions,
  count(*) filter (where routine_id = 'C') as core_sessions,
  count(*) filter (where routine_id = 'M') as mobility_sessions
from public.strength_logs
group by user_id, week_start;

revoke all on public.weekly_strength from anon;
grant select on public.weekly_strength to authenticated;
