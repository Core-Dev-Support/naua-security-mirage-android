-- ticket_messages: включаем RLS.
--
-- Таблица была защищена только запретом привилегий (REVOKE ALL от anon и
-- authenticated). Если кто-то однажды выдаст на неё SELECT, все сообщения
-- поддержки — переписка пользователей с админом — стали бы читаемы.
-- RLS закрывает таблицу дважды: привилегиями и политиками.

ALTER TABLE public.ticket_messages ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.ticket_messages FROM anon, authenticated;
GRANT SELECT ON public.ticket_messages TO service_role;
