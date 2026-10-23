-- Safe subscription entitlement migration.
-- Run once in the Supabase SQL editor. Existing rows are preserved.

ALTER TABLE public.subscriptions
    ADD COLUMN IF NOT EXISTS flow TEXT,
    ADD COLUMN IF NOT EXISTS operation_id TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX IF NOT EXISTS subscriptions_user_id_idx
    ON public.subscriptions (user_id);

-- The client is read-only. Only the service-role webhook may mutate entitlements.
ALTER TABLE public.subscriptions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow select for user" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow insert for user" ON public.subscriptions;
DROP POLICY IF EXISTS "Allow update for user" ON public.subscriptions;
DROP POLICY IF EXISTS subscriptions_select_own ON public.subscriptions;

CREATE POLICY subscriptions_select_own
    ON public.subscriptions
    FOR SELECT
    TO authenticated
    USING (auth.uid()::text = user_id);

REVOKE INSERT, UPDATE, DELETE ON public.subscriptions FROM anon, authenticated;
GRANT SELECT ON public.subscriptions TO authenticated;
