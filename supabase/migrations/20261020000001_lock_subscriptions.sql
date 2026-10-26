-- Закрываем доступ к таблице subscriptions для анонимного ключа.
--
-- В проекте была политика "Allow read and update subscriptions"
-- (cmd = ALL, roles = {anon, authenticated}, qual = true), созданная вручную
-- в дашборде: anon-ключ (а он лежит внутри APK приложения) читал ВСЕ строки —
-- почты платящих клиентов и их рабочие vless://-ссылки.
--
-- Миграции репозитория дропали только "Allow select/insert/update for user",
-- поэтому эта политика выжила. Теперь дропаем её явно.
--
-- Остаётся subscriptions_select_own: пользователь с сессией видит только
-- свою строку. Запись — только через service-role (бот и платёжный вебхук).

DROP POLICY IF EXISTS "Allow read and update subscriptions" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow select for user" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow insert for user" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow update for user" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow delete for user" ON public.subscriptions;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname = 'public' AND tablename = 'subscriptions'
      AND policyname = 'subscriptions_select_own'
  ) THEN
    CREATE POLICY subscriptions_select_own
      ON public.subscriptions
      FOR SELECT
      TO authenticated
      USING (auth.uid()::text = user_id);
  END IF;
END $$;

REVOKE INSERT, UPDATE, DELETE ON public.subscriptions FROM anon, authenticated;
GRANT SELECT ON public.subscriptions TO authenticated;

-- Таблицы бота: только service-role, никаких политик для публичной роли.
REVOKE ALL ON public.telegram_accounts FROM anon, authenticated;
REVOKE ALL ON public.telegram_oauth_attempts FROM anon, authenticated;
REVOKE ALL ON public.bot_users FROM anon, authenticated;
REVOKE ALL ON public.bot_state FROM anon, authenticated;
REVOKE ALL ON public.tickets FROM anon, authenticated;
REVOKE ALL ON public.admin_logs FROM anon, authenticated;
