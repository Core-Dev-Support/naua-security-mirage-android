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
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Enable Row Level Security (RLS)
ALTER TABLE public.subscriptions ENABLE ROW LEVEL SECURITY;

-- Allow users to read own subscription (or anon by user_id filter)
DROP POLICY IF EXISTS "Allow select for user" ON public.subscriptions;
CREATE POLICY "Allow select for user"
ON public.subscriptions
FOR SELECT
USING (true);

-- Allow authenticated and anon to insert/update subscription
DROP POLICY IF EXISTS "Allow insert for user" ON public.subscriptions;
CREATE POLICY "Allow insert for user"
ON public.subscriptions
FOR INSERT
WITH CHECK (true);

DROP POLICY IF EXISTS "Allow update for user" ON public.subscriptions;
CREATE POLICY "Allow update for user"
ON public.subscriptions
FOR UPDATE
USING (true);
