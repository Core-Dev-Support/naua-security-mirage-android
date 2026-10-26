-- Устройства приложения: человекочитаемое имя и короткий id.
--
-- Имя показывает бот на экране «Устройства», а id нужен кнопкам: сам
-- FCM-токен длиной 150+ символов в callback_data Telegram не помещается
-- (лимит 64 байта).
alter table public.device_tokens
  add column if not exists id uuid not null default gen_random_uuid();

alter table public.device_tokens
  add column if not exists device_name text;

create unique index if not exists device_tokens_id_key
  on public.device_tokens (id);
