create table public.strength_logs (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day date not null,
  routine_id text not null check (routine_id in ('A', 'B')),
  logged_at timestamptz not null default now(),
  primary key (user_id, day, routine_id)
);

alter table public.strength_logs enable row level security;

revoke all on public.strength_logs from anon;
revoke all on public.strength_logs from authenticated;
grant select, insert, update, delete on public.strength_logs to authenticated;

create policy strength_logs_select on public.strength_logs for select to authenticated
  using ((select auth.uid()) = user_id);
create policy strength_logs_insert on public.strength_logs for insert to authenticated
  with check ((select auth.uid()) = user_id);
create policy strength_logs_update on public.strength_logs for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
create policy strength_logs_delete on public.strength_logs for delete to authenticated
  using ((select auth.uid()) = user_id);

create or replace view public.weekly_strength
with (security_invoker = true)
as
select
  user_id,
  date_trunc('week', day)::date as week_start,
  count(*) as sessions,
  count(*) filter (where routine_id = 'A') as routine_a,
  count(*) filter (where routine_id = 'B') as routine_b
from public.strength_logs
group by user_id, week_start;

revoke all on public.weekly_strength from anon;
grant select on public.weekly_strength to authenticated;
