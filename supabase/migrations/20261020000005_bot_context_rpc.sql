-- Контекст бота за один запрос к базе.
--
-- Раньше обработчик клика читал bot_users, telegram_accounts и subscriptions
-- последовательно: до шести кругов до PostgREST подряд, по 300–400 мс каждый.
-- Именно на этом, а не на самом коде, уходило время отклика бота (около двух
-- секунд на клик).
--
-- Теперь всё нужное собирается одним запросом на стороне Postgres: три
-- выборки выполняются внутри базы, клиент делает ровно один круг.
--
-- Функция только читает. Выполнять её может исключительно service_role:
-- анонимный ключ (он лежит внутри APK приложения) не должен видеть чужие
-- строки, поэтому доступ для public/anon/authenticated отозван.

CREATE OR REPLACE FUNCTION public.bot_context(p_tg_id bigint)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT jsonb_build_object(
    'user', (
      SELECT to_jsonb(u) FROM (
        SELECT id, username, name, banned, ban_reason, warnings,
               referred_by, sub_until, last_notified, lang, screen_message_id
        FROM public.bot_users
        WHERE id = p_tg_id
      ) AS u
    ),
    'account', (
      SELECT to_jsonb(a) FROM (
        SELECT tg_id, user_id, email, linked_at, screen_message_id
        FROM public.telegram_accounts
        WHERE tg_id = p_tg_id
      ) AS a
    ),
    'subscription', (
      SELECT to_jsonb(s) FROM (
        SELECT sub.is_active, sub.plan, sub.paid_until
        FROM public.subscriptions AS sub
        JOIN public.telegram_accounts AS acc ON acc.user_id = sub.user_id
        WHERE acc.tg_id = p_tg_id
      ) AS s
    )
  );
$$;

REVOKE ALL ON FUNCTION public.bot_context(bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bot_context(bigint) TO service_role;
