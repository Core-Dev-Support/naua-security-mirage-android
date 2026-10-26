-- Server-driven app configuration: subscription plans and the paid-node catalogue.
--
-- The app and the Telegram bot read this through the public `app-config`
-- function, so a price change or a new VLESS node does not require an APK
-- release. Provider credentials never belong here: a node only names the edge
-- function that issues its per-user key.
create table if not exists public.app_config (
  id text primary key,
  payload jsonb not null,
  updated_at timestamptz not null default now()
);

alter table public.app_config enable row level security;

-- No client policy on purpose: only the service role (inside app-config and the
-- payment webhook) may read the table. The public function returns a sanitized
-- projection, not raw rows.
revoke all on table public.app_config from anon, authenticated;
grant select, insert, update, delete on table public.app_config to service_role;

-- Touching a row must refresh the version the app sees. Otherwise a price edit
-- made in the SQL editor would keep the old updated_at until someone set it by
-- hand.
create or replace function public.touch_app_config()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists app_config_touch on public.app_config;
create trigger app_config_touch
before update on public.app_config
for each row execute function public.touch_app_config();

insert into public.app_config (id, payload)
values (
  'subscription_plans',
  $json$[
    {"id":"1m","days":30,"price_rub":30,"badge":null,"label_ru":"30 дней","label_en":"30 days"},
    {"id":"3m","days":90,"price_rub":80,"badge":"-11%","label_ru":"3 месяца","label_en":"3 months"},
    {"id":"6m","days":180,"price_rub":150,"badge":"-17%","label_ru":"6 месяцев","label_en":"6 months"},
    {"id":"12m","days":365,"price_rub":250,"badge":"-32%","label_ru":"1 год","label_en":"1 year"}
  ]$json$::jsonb
)
on conflict (id) do nothing;

insert into public.app_config (id, payload)
values (
  'paid_nodes',
  $json$[
    {"id":"premium_france","kind":"france","label_ru":"Франция (Платная)","label_en":"France (Paid)","flag":"france","config_function":"france-config","enabled":true,"sort":10},
    {"id":"premium_belarus","kind":"belarus","label_ru":"Беларусь (Платная)","label_en":"Belarus (Paid)","flag":"belarus","config_function":"belarus-config","enabled":true,"sort":20}
  ]$json$::jsonb
)
on conflict (id) do nothing;

comment on table public.app_config is
  'Server-driven prices and paid-node catalogue. Read by app-config; write only with the service role.';
