-- Telegram-бот NAUA: аккаунты, состояние диалогов, тикеты, поддержка.
--
-- Бот переезжает с Vercel на Supabase Edge Functions и работает с тем же
-- проектом, что и приложение NAUA Security Mirage. Из-за этого появляется
-- общий аккаунт: Telegram-юзер входит через Google (Supabase Auth), а позже
-- тем же аккаунтом входит в приложение — и подписка/оплата подтягиваются
-- в обоих местах.
--
-- Скрипт идемпотентен.

-- ===================== Привязка Telegram <-> Google/Supabase =====================
-- Одна строка на Telegram-аккаунт. Хранит сессию Supabase Auth, чтобы бот мог
-- обращаться к платным узлам (france-config / belarus-config требуют JWT
-- пользователя) и выдавать единую подписку.
CREATE TABLE IF NOT EXISTS public.telegram_accounts (
  tg_id BIGINT PRIMARY KEY,
  user_id TEXT NOT NULL UNIQUE,
  email TEXT,
  access_token TEXT,
  refresh_token TEXT,
  access_expires_at TIMESTAMPTZ,
  linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Попытки входа через Google: state + PKCE-верификатор.
-- Живут недолго: строка удаляется сразу после обмена кода.
CREATE TABLE IF NOT EXISTS public.telegram_oauth_attempts (
  state TEXT PRIMARY KEY,
  tg_id BIGINT NOT NULL,
  code_verifier TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS telegram_oauth_attempts_created_idx
  ON public.telegram_oauth_attempts (created_at);

-- ===================== Профиль Telegram-пользователя =====================
CREATE TABLE IF NOT EXISTS public.bot_users (
  id BIGINT PRIMARY KEY,
  username TEXT,
  name TEXT,
  banned BOOLEAN NOT NULL DEFAULT false,
  ban_reason TEXT,
  banned_at TIMESTAMPTZ,
  warnings INTEGER NOT NULL DEFAULT 0,
  referred_by BIGINT,
  sub_until TEXT,
  device_id TEXT,
  last_notified TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS bot_users_referred_by_idx
  ON public.bot_users (referred_by);

-- ===================== Состояние диалогов бота =====================
CREATE TABLE IF NOT EXISTS public.bot_state (
  chat_id BIGINT PRIMARY KEY,
  state TEXT NOT NULL,
  data JSONB,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===================== Обращения в поддержку =====================
CREATE TABLE IF NOT EXISTS public.tickets (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  message TEXT,
  status TEXT NOT NULL DEFAULT 'open',
  replied BOOLEAN NOT NULL DEFAULT false,
  admin_id BIGINT,
  admin_reply TEXT,
  replied_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tickets_user_id_idx ON public.tickets (user_id);
CREATE INDEX IF NOT EXISTS tickets_status_idx ON public.tickets (status);

-- ===================== Журнал действий администратора =====================
CREATE TABLE IF NOT EXISTS public.admin_logs (
  id BIGSERIAL PRIMARY KEY,
  admin_id BIGINT,
  action TEXT,
  target_id BIGINT,
  details TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ===================== Ссылка на Telegram в подписке =====================
-- Ускоряет поиск подписки по Telegram-аккаунту. Заполняется ботом при
-- привязке Google-аккаунта.
ALTER TABLE public.subscriptions ADD COLUMN IF NOT EXISTS tg_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS subscriptions_tg_id_idx
  ON public.subscriptions (tg_id)
  WHERE tg_id IS NOT NULL;

-- ===================== Политики доступа =====================
-- Все таблицы бота — серверные: читает и пишет только бот через
-- service-role ключ. RLS включён без политик, поэтому anon-ключ из APK
-- не может их прочитать (как node_status и payment_events).
ALTER TABLE public.telegram_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.telegram_oauth_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bot_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bot_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tickets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.admin_logs ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.telegram_accounts FROM anon, authenticated;
REVOKE ALL ON public.telegram_oauth_attempts FROM anon, authenticated;
REVOKE ALL ON public.bot_users FROM anon, authenticated;
REVOKE ALL ON public.bot_state FROM anon, authenticated;
REVOKE ALL ON public.tickets FROM anon, authenticated;
REVOKE ALL ON public.admin_logs FROM anon, authenticated;
