-- Устройства в VPN-клиентах (HWID).
--
-- Happ, v2RayTun и другие клиенты при получении подписки присылают
-- x-hwid, x-device-os, x-ver-os, x-device-model (стандарт Happ, его же
-- использует Remnawave). Это единственный способ увидеть конкретный
-- телефон или компьютер: без HWID клиент неотличим от другого такого же.
--
-- Храним хеш HWID, а не сам идентификатор: для показа и блокировки
-- достаточно совпадения, сырые данные устройства не нужны.
create table if not exists public.subscription_devices (
  tg_id bigint not null,
  hwid_hash text not null,
  client text,
  device_model text,
  device_os text,
  os_version text,
  last_ip text,
  blocked boolean not null default false,
  hits bigint not null default 0,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (tg_id, hwid_hash)
);

alter table public.subscription_devices enable row level security;

-- track_subscription_device — upsert устройства и ответ «отключено ли оно».
--
-- Возвращает blocked, чтобы telegram-subscription не делал второй запрос:
-- отключённому устройству сразу отдаётся пустая подписка.
create or replace function public.track_subscription_device(
  p_tg_id bigint,
  p_hwid_hash text,
  p_client text default null,
  p_model text default null,
  p_os text default null,
  p_os_version text default null,
  p_ip text default null
) returns boolean
language sql
security definer
set search_path = public
as $$
  insert into public.subscription_devices (
    tg_id, hwid_hash, client, device_model, device_os, os_version, last_ip, hits
  )
  values (
    p_tg_id, left(p_hwid_hash, 64), left(p_client, 40), left(p_model, 80),
    left(p_os, 40), left(p_os_version, 40), p_ip, 1
  )
  on conflict (tg_id, hwid_hash) do update
    set client = coalesce(excluded.client, public.subscription_devices.client),
        device_model = coalesce(excluded.device_model, public.subscription_devices.device_model),
        device_os = coalesce(excluded.device_os, public.subscription_devices.device_os),
        os_version = coalesce(excluded.os_version, public.subscription_devices.os_version),
        last_ip = excluded.last_ip,
        hits = public.subscription_devices.hits + 1,
        last_seen_at = now()
  returning blocked;
$$;

revoke all on function public.track_subscription_device(
  bigint, text, text, text, text, text, text
) from public, anon, authenticated;
grant execute on function public.track_subscription_device(
  bigint, text, text, text, text, text, text
) to service_role;
