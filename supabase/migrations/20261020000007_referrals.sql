-- Реферальная программа: бонус пригласившему, список приглашённых, прогресс.
--
-- 1) В журнале платежей появляется колонка bonus_for — префикс user_id того,
--    кому начислен бонус. По ней считаются заработанные дни и видно, кому
--    бонус уже начисляли.
--
-- 2) bot_context дополняется:
--      referrals_list   — кто пришёл по ссылке (имя, когда, оплатил ли);
--      referral_days    — сколько дней заработано;
--      paid_referrals   — сколько рефералов оплатили (для прогресса).

ALTER TABLE public.payment_events
  ADD COLUMN IF NOT EXISTS bonus_for TEXT;

CREATE INDEX IF NOT EXISTS payment_events_bonus_idx
  ON public.payment_events (bonus_for, outcome);

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
    'referrals_list', (
      SELECT COALESCE(jsonb_agg(r ORDER BY r.joined_at DESC), '[]'::jsonb)
      FROM (
        SELECT u.id, u.name, u.username, u.created_at AS joined_at,
               COALESCE((
                 SELECT s.plan FROM public.subscriptions AS s
                 JOIN public.telegram_accounts AS ta ON ta.user_id = s.user_id
                 WHERE ta.tg_id = u.id
                 LIMIT 1
               ), '') AS plan
        FROM public.bot_users AS u
        WHERE u.referred_by = p_tg_id
        ORDER BY u.created_at DESC
        LIMIT 50
      ) AS r
    ),
    'referral_days', (
      SELECT COALESCE(SUM((pe.withdraw_amount)::numeric), 0)::int
      FROM public.payment_events AS pe
      WHERE pe.outcome = 'referral_bonus'
        AND pe.bonus_for = left((
          SELECT acc3.user_id FROM public.telegram_accounts AS acc3
          WHERE acc3.tg_id = p_tg_id
        ), 8)
    ),
    'paid_referrals', (
      SELECT count(*)::int FROM (
        SELECT 1 FROM public.bot_users AS u
        JOIN public.telegram_accounts AS ta ON ta.tg_id = u.id
        JOIN public.subscriptions AS s ON s.user_id = ta.user_id
        WHERE u.referred_by = p_tg_id
          AND s.plan IS DISTINCT FROM 'trial'
      ) AS x
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
