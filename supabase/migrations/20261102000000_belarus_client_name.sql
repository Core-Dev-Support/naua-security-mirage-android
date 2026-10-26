-- The Belarus panel client name is deterministic, but H1Cloud can already have
-- an inbound client with the same name that is not returned by /clients.
-- In that case belarus-config creates `<name>_v2`, and it must remember that
-- choice; otherwise every request would scan the whole panel again and try to
-- recreate the base name.
alter table public.subscriptions
  add column if not exists belarus_client_name text;
