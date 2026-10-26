-- Напоминания об истечении подписки и продвижение рассылки.
--
-- pg_cron раз в 15 минут вызывает telegram-bot?op=cron. Ключ x-sub-key
-- хранится в Vault под именем telegram_bot_cron_key — в репозитории его нет.
--
-- На новом проекте сначала создайте секрет (значение — любое случайное):
--
--   select vault.create_secret('<CRON_KEY>', 'telegram_bot_cron_key',
--     'x-sub-key for telegram-bot cron');
--
-- Без секрета миграция просто пропускает создание задания.
create extension if not exists pg_cron;
create extension if not exists pg_net;

do $do$
begin
  if not exists (
    select 1 from vault.decrypted_secrets where name = 'telegram_bot_cron_key'
  ) then
    raise notice 'telegram_bot_cron_key не найден — крон не создан';
    return;
  end if;

  if exists (select 1 from cron.job where jobname = 'telegram-bot-cron') then
    perform cron.unschedule('telegram-bot-cron');
  end if;

  perform cron.schedule(
    'telegram-bot-cron',
    '*/15 * * * *',
    $job$
    select net.http_post(
      url := 'https://orwwaptbxmwxvfwfqavc.supabase.co/functions/v1/telegram-bot?op=cron',
      headers := jsonb_build_object(
        'Content-Type', 'application/json',
        'x-sub-key', (
          select decrypted_secret from vault.decrypted_secrets
          where name = 'telegram_bot_cron_key'
        )
      ),
      body := '{}'::jsonb,
      timeout_milliseconds := 30000
    );
    $job$
  );
end
$do$;
