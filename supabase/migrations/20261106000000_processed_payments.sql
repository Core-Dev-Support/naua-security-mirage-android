-- Квитанции обработанных платежей YooMoney.
--
-- Одного поля subscriptions.operation_id недостаточно: оно хранит только
-- последнюю операцию. Если YooMoney повторно доставит более старое
-- уведомление после новой оплаты, проверка «совпадает с последней» его
-- пропустит и подписка продлится ещё раз. Первичный ключ operation_id
-- делает повторную обработку невозможной на уровне базы.
--
-- Строка вставляется до обращения к 3X-UI и удаляется, если начисление
-- сорвалось: тогда повторная доставка от YooMoney сможет пройти.

CREATE TABLE IF NOT EXISTS public.processed_payments (
  operation_id TEXT PRIMARY KEY CHECK (length(operation_id) BETWEEN 1 AND 128),
  user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS processed_payments_user_idx
  ON public.processed_payments (user_id, processed_at DESC);

ALTER TABLE public.processed_payments ENABLE ROW LEVEL SECURITY;

-- Таблицу читает и пишет только вебхук с service_role. Анонимный ключ,
-- который лежит внутри APK, не должен видеть историю операций.
REVOKE ALL ON public.processed_payments FROM anon, authenticated;
GRANT SELECT, INSERT, DELETE ON public.processed_payments TO service_role;
