-- Язык бота: выбирается при первом входе (русский / английский).
--
-- NULL означает «язык ещё не выбран» — бот покажет выбор языка.
-- Значение хранится рядом с профилем Telegram-пользователя.

ALTER TABLE public.bot_users
  ADD COLUMN IF NOT EXISTS lang TEXT;

CREATE INDEX IF NOT EXISTS bot_users_lang_idx
  ON public.bot_users (lang)
  WHERE lang IS NOT NULL;
