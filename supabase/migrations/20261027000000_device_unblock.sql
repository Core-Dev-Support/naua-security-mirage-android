-- Убираем «отключение» устройства из HWID-списка.
--
-- Оно не рвало уже выданный ключ: клиент продолжал работать на сохранённом
-- конфиге, а на бесплатном узле ключ вообще чужой (сервер VPG). Кнопка
-- вводила в заблуждение, поэтому блокировку убираем. Список устройств
-- остаётся справочным: модель, система, клиент и время последнего запроса.
alter table public.subscription_devices drop column if exists blocked;

-- Возвращаемый тип меняется (boolean → void), поэтому функцию пересоздаём.
drop function if exists public.track_subscription_device(
  bigint, text, text, text, text, text, text
);

create function public.track_subscription_device(
  p_tg_id bigint,
  p_hwid_hash text,
  p_client text default null,
  p_model text default null,
  p_os text default null,
  p_os_version text default null,
  p_ip text default null
) returns void
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
        last_seen_at = now();
$$;

revoke all on function public.track_subscription_device(
  bigint, text, text, text, text, text, text
) from public, anon, authenticated;
grant execute on function public.track_subscription_device(
  bigint, text, text, text, text, text, text
) to service_role;
