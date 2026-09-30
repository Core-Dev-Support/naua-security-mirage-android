-- ==============================================================================
-- NAUA Security Mirage - Supabase Database Schema for Subscriptions
-- Run this script in the Supabase SQL Editor (https://supabase.com/dashboard)
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL UNIQUE,
    email TEXT,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    plan TEXT NOT NULL DEFAULT 'free',
    paid_until TIMESTAMPTZ,
    vless_key TEXT,
    client_uuid TEXT,
    flow TEXT,
    operation_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable Row Level Security (RLS)
ALTER TABLE public.subscriptions ENABLE ROW LEVEL SECURITY;

-- Users may read only their own entitlement. Writes are reserved for the
-- service-role payment webhook; clients must never be able to grant plans.
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
