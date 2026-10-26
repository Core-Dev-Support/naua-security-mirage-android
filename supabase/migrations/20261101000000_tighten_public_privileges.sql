-- Supabase по умолчанию выдаёт anon и authenticated полный набор прав на новые
-- объекты public. Из-за этого у subscriptions остались TRUNCATE, TRIGGER и
-- REFERENCES (миграция отзывала только INSERT/UPDATE/DELETE), а любая будущая
-- функция стала бы исполнима анонимным ключом из APK.
--
-- Приложению нужен только SELECT собственной строки подписки: её читает
-- SupabaseManager с access token пользователя, а RLS-политика
-- subscriptions_select_own ограничивает выборку auth.uid().
revoke all on table public.subscriptions from anon, authenticated;
grant select on table public.subscriptions to authenticated;

-- Дальше доступ выдаётся явно в каждой миграции. Это защищает от повторения
-- истории, когда новая таблица или SECURITY DEFINER-функция автоматически
-- открывается публичному anon-ключу.
alter default privileges for role postgres in schema public
  revoke all on tables from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke all on sequences from anon, authenticated;
alter default privileges for role postgres in schema public
  revoke all on functions from anon, authenticated;
