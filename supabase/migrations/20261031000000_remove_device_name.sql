-- Списка устройств в приложении нет: модель устройства больше не нужна.
-- device_tokens остаётся хранилищем FCM-токенов для push-уведомлений,
-- поэтому саму таблицу и остальные поля не трогаем.
alter table public.device_tokens drop column if exists device_name;
