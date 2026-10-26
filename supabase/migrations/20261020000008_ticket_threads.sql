-- Поддержка: переписка, вложения и заявка пользователя.
--
-- 1) ticket_messages — переписка по тикету: что написал пользователь, что
--    ответил админ. Раньше тикет хранил только одно сообщение пользователя и
--    один ответ админа, поэтому продолжить диалог было нечем.
--
-- 2) tickets.file_id / file_kind — вложение к первому сообщению: скрин,
--    видео или файл с логами. Хранится только идентификатор файла Telegram,
--    сам файл лежит у Telegram.

CREATE TABLE IF NOT EXISTS public.ticket_messages (
  id BIGSERIAL PRIMARY KEY,
  ticket_id BIGINT NOT NULL REFERENCES public.tickets(id) ON DELETE CASCADE,
  from_user BOOLEAN NOT NULL DEFAULT true,
  text TEXT,
  file_id TEXT,
  file_kind TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ticket_messages_ticket_idx
  ON public.ticket_messages (ticket_id, created_at);

ALTER TABLE public.tickets
  ADD COLUMN IF NOT EXISTS file_id TEXT;

ALTER TABLE public.tickets
  ADD COLUMN IF NOT EXISTS file_kind TEXT;

REVOKE ALL ON public.ticket_messages FROM anon, authenticated;
GRANT SELECT ON public.ticket_messages TO service_role;
