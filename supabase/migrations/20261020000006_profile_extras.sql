-- Профиль: дата регистрации, число рефералов и история платежей.
--
-- Всё это берётся одним запросом вместе с пользователем, привязкой и
-- подпиской (public.bot_context), чтобы экран профиля не превращался в
-- четыре запроса подряд.
--
-- payment_events ведёт вебхук YooMoney. Здесь он создаётся явно, чтобы
-- репозиторий был самодостаточным: на существующей базе CREATE IF NOT
-- EXISTS ничего не меняет.

CREATE TABLE IF NOT EXISTS public.payment_events (
  id BIGSERIAL PRIMARY KEY,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  notification_type TEXT,
  operation_id TEXT,
  amount TEXT,
  commission TEXT,
  codepro TEXT,
  label_prefix TEXT,
  signature_present BOOLEAN,
  outcome TEXT,
  withdraw_amount TEXT
);

CREATE INDEX IF NOT EXISTS payment_events_label_idx
  ON public.payment_events (label_prefix, outcome, created_at DESC);

-- Анонимный ключ (он лежит внутри APK) не должен видеть платежи.
REVOKE ALL ON public.payment_events FROM anon, authenticated;
GRANT SELECT ON public.payment_events TO service_role;

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
               referred_by, sub_until, last_notified, lang, screen_message_id,
               created_at
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
    ),
    'referrals', (
      SELECT count(*)::int FROM public.bot_users AS r
      WHERE r.referred_by = p_tg_id
    ),
    'payments', (
      SELECT COALESCE(jsonb_agg(p ORDER BY p.created_at DESC), '[]'::jsonb)
      FROM (
        SELECT pe.created_at, pe.withdraw_amount, pe.amount, pe.commission,
               pe.operation_id
        FROM public.payment_events AS pe
        WHERE pe.outcome = 'credited'
          AND pe.label_prefix = left((
            SELECT acc2.user_id FROM public.telegram_accounts AS acc2
            WHERE acc2.tg_id = p_tg_id
          ), 8)
        ORDER BY pe.created_at DESC
        LIMIT 20
      ) AS p
    )
  );
$$;

REVOKE ALL ON FUNCTION public.bot_context(bigint) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.bot_context(bigint) TO service_role;
