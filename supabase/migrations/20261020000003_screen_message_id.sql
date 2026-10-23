-- Идентификатор сообщения-экрана, который нужно переписать после входа.
--
-- telegram-auth по завершении входа переписывает (editMessage) то самое
-- сообщение, в котором был нажат вход, вместо того чтобы слать новое.
-- Так в чате остаётся ровно один пузырь: приветствие -> «вход через Google»
-- -> «аккаунт привязан» + кнопки меню.
--
-- Хранится вместе с попыткой входа: он живёт 10 минут и удаляется сразу
-- после использования.

ALTER TABLE public.telegram_oauth_attempts
  ADD COLUMN IF NOT EXISTS screen_message_id BIGINT;

-- Тот же id переносится в telegram_accounts: после привязки бот читает его
-- оттуда, чтобы переписать исходный пузырь, а не слать новое сообщение.
ALTER TABLE public.telegram_accounts
  ADD COLUMN IF NOT EXISTS screen_message_id BIGINT;
