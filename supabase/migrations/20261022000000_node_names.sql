-- Названия узлов: одно имя для всех клиентов.
--
-- Францию и Беларусь переименовываем прямо в сохранённых ссылках: их видят
-- приложение и VPN-клиенты, а служебные имена панелей («mrge_<почта>_…»)
-- пользователю показывать незачем.
--
-- Функции france-config и belarus-config при следующем обращении всё равно
-- пересоберут ссылки и запишут новые имена — эта миграция нужна, чтобы
-- названия поменялись сразу, не дожидаясь запроса из приложения.

update public.subscriptions
set vless_key = regexp_replace(vless_key, '#.*$', '#NAUA%20Security%20Mirage%20%D0%A4%D1%80%D0%B0%D0%BD%D1%86%D0%B8%D1%8F%20%28%D0%9F%D0%BB%D0%B0%D1%82%D0%BD%D1%8B%D0%B9%29')
where vless_key like 'vless://%';

update public.subscriptions
set belarus_vless_key = regexp_replace(belarus_vless_key, '#.*$', '#NAUA%20Security%20Mirage%20%D0%91%D0%B5%D0%BB%D0%B0%D1%80%D1%83%D1%81%D1%8C%20%28%D0%9F%D0%BB%D0%B0%D1%82%D0%BD%D1%8B%D0%B9%29')
where belarus_vless_key like 'vless://%';
