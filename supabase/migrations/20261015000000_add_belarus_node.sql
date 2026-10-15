-- The paid node is no longer singular. France already had columns for the
-- key it is issued; Belarus needs a pair of its own, otherwise switching
-- between the two nodes would overwrite the other node's link.
ALTER TABLE public.subscriptions
    ADD COLUMN IF NOT EXISTS belarus_vless_key TEXT,
    ADD COLUMN IF NOT EXISTS belarus_client_uuid TEXT;