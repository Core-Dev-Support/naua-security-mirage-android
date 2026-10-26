-- Сброс ссылки подписки и журнал клиентов.
--
-- link_version входит в подпись ссылки. Ноль — поведение прежнее, поэтому
-- деплой ничего не ломает у действующих пользователей. Сброс — version + 1:
-- старые адреса перестают проходить проверку подписи, новые бот собирает
-- уже с другой подписью.
alter table public.telegram_accounts
  add column if not exists link_version integer not null default 0;

-- Клиенты, которые забирали ссылку подписки (Happ, Hiddify, v2rayNG…).
--
-- Точное устройство определить нельзя: VPN-клиенты не присылают
-- идентификатор, поэтому строка — приложение, а не телефон. Храним
-- последнюю подсеть, счётчик обращений и время последнего запроса.
create table if not exists public.subscription_clients (
  tg_id bigint not null,
  client text not null,
  last_ip text,
  hits bigint not null default 0,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  primary key (tg_id, client)
);

alter table public.subscription_clients enable row level security;

-- Upsert одним вызовом: счётчик и время обновляются на месте.
create or replace function public.track_subscription_client(
  p_tg_id bigint,
  p_client text,
  p_ip text default null
) returns void
language sql
security definer
set search_path = public
as $$
  insert into public.subscription_clients (tg_id, client, last_ip, hits)
  values (p_tg_id, left(p_client, 40), p_ip, 1)
  on conflict (tg_id, client) do update
    set hits = public.subscription_clients.hits + 1,
        last_ip = excluded.last_ip,
        last_seen_at = now();
$$;

revoke all on function public.track_subscription_client(bigint, text, text)
  from public, anon, authenticated;
grant execute on function public.track_subscription_client(bigint, text, text)
  to service_role;
