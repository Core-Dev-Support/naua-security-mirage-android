-- Пробный день платного узла: серверная запись о том, что устройство уже
-- пользовалось пробником.
--
-- Ключ устройства приходит из приложения и жёстко привязан к строке: повторный
-- запрос с тем же ключом отклоняется базой (уникальный индекс), а не
-- проверкой в коде функции. Поэтому гонка двух запросов тоже безопасна.
--
-- Клиент не может ни читать, ни писать эту таблицу: решение о выдаче пробника
-- принимает серверная функция free-trial.

CREATE TABLE IF NOT EXISTS public.trial_devices (
    device_key TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS trial_devices_user_idx
    ON public.trial_devices (user_id);

ALTER TABLE public.trial_devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS trial_devices_select_own ON public.trial_devices;

REVOKE ALL ON public.trial_devices FROM anon, authenticated;
