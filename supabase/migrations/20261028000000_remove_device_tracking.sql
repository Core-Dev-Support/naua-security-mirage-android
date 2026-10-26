-- Пункт «Устройства» убран из бота.
--
-- Журнал клиентов и HWID-устройств больше не ведётся: он был нужен только
-- экрану «Устройства», а отключить отдельное устройство всё равно нельзя —
-- ключ подписки общий для аккаунта. Удаляем таблицы и RPC, которые его
-- наполняли.
drop function if exists public.track_subscription_device(
  bigint, text, text, text, text, text, text
);
drop function if exists public.track_subscription_client(bigint, text, text);
drop table if exists public.subscription_devices;
drop table if exists public.subscription_clients;
