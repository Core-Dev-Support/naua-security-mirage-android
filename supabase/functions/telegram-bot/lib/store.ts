// Доступ к данным бота в Supabase.
//
// Все запросы идут через service-role ключ: таблицы бота закрыты RLS без
// политик, поэтому anon-ключ их не видит.

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.39.8";
import type {
  AccountRow,
  BotStateRow,
  BotUserRow,
  TicketRow,
} from "./telegram.ts";

export const SUPABASE_URL = Deno.env.get("SUPABASE_URL") || "";
export const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY") || "";
export const SUPABASE_SERVICE_ROLE_KEY =
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";

export function db() {
  return createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

// ---------- Пользователи ----------

export async function getUser(tgId: number): Promise<BotUserRow | null> {
  const { data, error } = await db()
    .from("bot_users")
    .select("id,username,name,banned,ban_reason,warnings,referred_by,sub_until,last_notified,lang,consent_at,consent_version")
    .eq("id", tgId)
    .limit(1);
  if (error) return null;
  return ((data && data[0]) as BotUserRow | undefined) ?? null;
}

export async function upsertUser(
  tgId: number,
  username: string,
  name: string,
): Promise<void> {
  await db().from("bot_users").upsert({
    id: tgId,
    username,
    name,
    updated_at: new Date().toISOString(),
  }, { onConflict: "id" });
}

// getScreenId / setScreenId — единый экран чата: сообщение, которое бот переписывает.
//
// Зачему чату в чате было бы единое сообщение бота, а меню переписывалось в разных местах — обя иначе ведёт себя один экран.
export async function getScreenId(tgId: number): Promise<number> {
  const { data } = await db()
    .from("bot_users")
    .select("screen_message_id")
    .eq("id", tgId)
    .limit(1);
  const row = (data && data[0]) as { screen_message_id: number | null } | undefined;
  return row?.screen_message_id ?? 0;
}

export async function setScreenId(tgId: number, msgId: number): Promise<void> {
  await db().from("bot_users").update({
    screen_message_id: msgId,
    updated_at: new Date().toISOString(),
  }).eq("id", tgId);
}

// setLanguage сохраняет выбранный язык пользователя.
export async function setLanguage(tgId: number, lang: string): Promise<void> {
  await db().from("bot_users").update({
    lang,
    updated_at: new Date().toISOString(),
  }).eq("id", tgId);
}

// setConsent фиксирует принятие документов и их версию.
export async function setConsent(tgId: number, version: string): Promise<void> {
  await db().from("bot_users").update({
    consent_at: new Date().toISOString(),
    consent_version: version,
    updated_at: new Date().toISOString(),
  }).eq("id", tgId);
}

export async function updateUser(
  tgId: number,
  fields: Record<string, unknown>,
): Promise<void> {
  await db()
    .from("bot_users")
    .update({ ...fields, updated_at: new Date().toISOString() })
    .eq("id", tgId);
}

export async function listUsers(limit = 1000): Promise<BotUserRow[]> {
  const { data, error } = await db()
    .from("bot_users")
    .select("id,username,name,banned,ban_reason,warnings,referred_by,sub_until,last_notified,lang,consent_at,consent_version")
    .order("id", { ascending: true })
    .limit(limit);
  if (error) return [];
  return (data as BotUserRow[]) ?? [];
}

export async function listBannedUsers(): Promise<BotUserRow[]> {
  const { data, error } = await db()
    .from("bot_users")
    .select("id,username,name,banned,ban_reason,warnings,referred_by,sub_until,last_notified,lang,consent_at,consent_version")
    .eq("banned", true)
    .order("id", { ascending: true });
  if (error) return [];
  return (data as BotUserRow[]) ?? [];
}

// ---------- Состояние диалогов ----------

const STATE_TTL_MINUTES = 30;

export async function getState(tgId: number): Promise<BotStateRow | null> {
  const { data, error } = await db()
    .from("bot_state")
    .select("state,data,updated_at")
    .eq("chat_id", tgId)
    .limit(1);
  if (error) return null;
  const row = (data && data[0]) as
    | (BotStateRow & { updated_at: string })
    | undefined;
  if (!row) return null;
  const updated = Date.parse(row.updated_at);
  if (!Number.isFinite(updated) || Date.now() - updated > STATE_TTL_MINUTES * 60_000) {
    return null;
  }
  return { state: row.state, data: row.data ?? {} };
}

export async function setState(
  tgId: number,
  state: string,
  data: Record<string, unknown> = {},
): Promise<void> {
  await db().from("bot_state").upsert({
    chat_id: tgId,
    state,
    data,
    updated_at: new Date().toISOString(),
  }, { onConflict: "chat_id" });
}

export async function clearState(tgId: number): Promise<void> {
  await db().from("bot_state").delete().eq("chat_id", tgId);
}

// ---------- Тикеты ----------

// createTicket — новая заявка.
//
// Вложение (скрин, видео, файл с логами) хранится идентификатором файла
// Telegram: сам файл остаётся у Telegram.
export async function createTicket(
  tgId: number,
  message: string,
  fileId?: string | null,
  fileKind?: string | null,
): Promise<number | null> {
  const { data, error } = await db()
    .from("tickets")
    .insert({
      user_id: tgId,
      message,
      status: "open",
      file_id: fileId ?? null,
      file_kind: fileKind ?? null,
      created_at: new Date().toISOString(),
    })
    .select("id")
    .limit(1);
  if (error) return null;
  const id = (data?.[0] as { id: number } | undefined)?.id;
  if (id) {
    await addTicketMessage(id, true, message, fileId ?? null, fileKind ?? null);
  }
  return id ?? null;
}

// TicketMessage — реплика в переписке по тикету.
export type TicketMessage = {
  id: number;
  ticket_id: number;
  from_user: boolean;
  text: string | null;
  file_id: string | null;
  file_kind: string | null;
  created_at: string;
};

// addTicketMessage — реплика: от пользователя или от админа.
export async function addTicketMessage(
  ticketId: number,
  fromUser: boolean,
  text: string | null,
  fileId?: string | null,
  fileKind?: string | null,
): Promise<void> {
  await db().from("ticket_messages").insert({
    ticket_id: ticketId,
    from_user: fromUser,
    text: text ?? null,
    file_id: fileId ?? null,
    file_kind: fileKind ?? null,
    created_at: new Date().toISOString(),
  });
}

// listTicketMessages — переписка по тикету, по порядку.
export async function listTicketMessages(ticketId: number): Promise<TicketMessage[]> {
  const { data, error } = await db()
    .from("ticket_messages")
    .select("id,ticket_id,from_user,text,file_id,file_kind,created_at")
    .eq("ticket_id", ticketId)
    .order("created_at", { ascending: true });
  if (error) return [];
  return (data as TicketMessage[]) ?? [];
}

// listUserTickets — заявки пользователя (любого статуса), новые первыми.
export async function listUserTickets(
  tgId: number,
  limit = 5,
): Promise<TicketRow[]> {
  const { data, error } = await db()
    .from("tickets")
    .select("id,user_id,message,status,replied,created_at,admin_reply,replied_at,file_id,file_kind")
    .eq("user_id", tgId)
    .order("created_at", { ascending: false })
    .limit(limit);
  if (error) return [];
  return (data as TicketRow[]) ?? [];
}

// getTicket — заявка по id.
export async function getTicket(id: number): Promise<TicketRow | null> {
  const { data, error } = await db()
    .from("tickets")
    .select("id,user_id,message,status,replied,created_at,admin_reply,replied_at,file_id,file_kind")
    .eq("id", id)
    .limit(1);
  if (error) return null;
  return ((data && data[0]) as TicketRow | undefined) ?? null;
}

export async function getLatestTicket(tgId: number): Promise<TicketRow | null> {
  const { data, error } = await db()
    .from("tickets")
    .select("id,user_id,message,status,replied,created_at")
    .eq("user_id", tgId)
    .order("created_at", { ascending: false })
    .limit(1);
  if (error) return null;
  return ((data && data[0]) as TicketRow | undefined) ?? null;
}

export async function listTickets(status = "open", limit = 50): Promise<TicketRow[]> {
  const { data, error } = await db()
    .from("tickets")
    .select("id,user_id,message,status,replied,created_at")
    .eq("status", status)
    .order("created_at", { ascending: true })
    .limit(limit);
  if (error) return [];
  return (data as TicketRow[]) ?? [];
}

export async function setTicketStatus(id: number, status: string): Promise<void> {
  await db().from("tickets").update({
    status,
    replied: status !== "open",
    replied_at: new Date().toISOString(),
  }).eq("id", id);
}

export async function setTicketReply(
  id: number,
  adminId: number,
  reply: string,
): Promise<void> {
  await db().from("tickets").update({
    admin_id: adminId,
    admin_reply: reply,
    replied: true,
    status: "done",
    replied_at: new Date().toISOString(),
  }).eq("id", id);
}

// ---------- Привязка Google-аккаунта ----------

export async function getAccount(tgId: number): Promise<AccountRow | null> {
  const { data, error } = await db()
    .from("telegram_accounts")
    .select("tg_id,user_id,email,linked_at,screen_message_id,link_version")
    .eq("tg_id", tgId)
    .limit(1);
  if (error) return null;
  return ((data && data[0]) as AccountRow | undefined) ?? null;
}

// unlinkAccount отвязывает Telegram-аккаунт от Google-аккаунта.
//
// Удаляются сохранённые у бота сессия Supabase и связь в subscriptions.
// Сам Google-аккаунт и подписка не трогаются: в приложении этим же
// аккаунтом по-прежнему можно войти.
export async function unlinkAccount(tgId: number): Promise<string | null> {
  const client = db();
  const { data } = await client
    .from("telegram_accounts")
    .select("user_id,email")
    .eq("tg_id", tgId)
    .limit(1);
  const row = (data && data[0]) as { user_id: string; email: string | null } | undefined;

  await client.from("telegram_accounts").delete().eq("tg_id", tgId);
  await client.from("subscriptions").update({ tg_id: null }).eq("tg_id", tgId);
  return row?.email ?? null;
}

export async function getSubscription(userId: string): Promise<{
  is_active: boolean;
  plan: string;
  paid_until: string | null;
} | null> {
  const { data, error } = await db()
    .from("subscriptions")
    .select("is_active,plan,paid_until")
    .eq("user_id", userId)
    .limit(1);
  if (error) return null;
  return ((data && data[0]) as {
    is_active: boolean;
    plan: string;
    paid_until: string | null;
  } | undefined) ?? null;
}

// ---------- Контекст бота одним запросом ----------

// SubscriptionRow — строка подписки в общей таблице.
export type SubscriptionRow = {
  is_active: boolean;
  plan: string;
  paid_until: string | null;
};

// BotContext — всё, что нужно обработчику апдейта.
//
// Раньше эти выборки шли последовательно, по 300–400 мс каждая, и на клик
// это давало около двух секунд ожидания. Теперь база отдаёт всё одним
// запросом (миграция 20261020000005_bot_context_rpc.sql).
// PaymentRow — начисленный платёж из журнала вебхука.
export type PaymentRow = {
  created_at: string | null;
  withdraw_amount: string | null;
  amount: string | null;
  commission: string | null;
  operation_id: string | null;
};

// ReferralRow — приглашённый по ссылке.
export type ReferralRow = {
  id: number;
  name: string | null;
  username: string | null;
  joined_at: string | null;
  /** План подписки приглашённого: пусто или 'trial' — ещё не оплатил. */
  plan: string | null;
};

export type BotContext = {
  user: BotUserRow | null;
  account: AccountRow | null;
  subscription: SubscriptionRow | null;
  /** Сколько людей пришло по реферальной ссылке. */
  referrals: number;
  /** Кто пришёл по ссылке: имя, ник, когда, оплатил ли. */
  referrals_list: ReferralRow[];
  /** Сколько дней заработано за рефералов. */
  referral_days: number;
  /** Сколько рефералов оплатили. */
  paid_referrals: number;
  /** Последние начисленные платежи (новые первыми). */
  payments: PaymentRow[];
};

// Кэш контекста на несколько секунд.
//
// Экземпляр edge-функции живёт между запросами, поэтому подряд идущие клики
// (обычно за пару секунд) не должны снова ходить в базу. Данные меняются
// редко: подписка обновляется только при оплате, а вход и выход сбрасывают
// кэш явно через invalidateContext.
const CONTEXT_TTL_MS = 5_000;
const contextCache = new Map<number, { at: number; ctx: BotContext }>();

// invalidateContext — забыть кэш пользователя (вход, выход, оплата).
export function invalidateContext(tgId: number): void {
  contextCache.delete(tgId);
}

// loadContext собирает пользователя, привязку и подписку одним запросом.
//
// fresh = true — всегда читать из базы (например, после оплаты).
export async function loadContext(
  tgId: number,
  fresh = false,
): Promise<BotContext> {
  const now = Date.now();
  if (!fresh) {
    const hit = contextCache.get(tgId);
    if (hit && now - hit.at < CONTEXT_TTL_MS) return hit.ctx;
  }
  const ctx = await fetchContext(tgId);
  contextCache.set(tgId, { at: now, ctx });
  return ctx;
}

async function fetchContext(tgId: number): Promise<BotContext> {
  const { data, error } = await db().rpc("bot_context", { p_tg_id: tgId });
  if (error || !data || typeof data !== "object") {
    // RPC недоступен (миграция не применена) — читаем по частям: бот работает.
    const [user, account] = await Promise.all([getUser(tgId), getAccount(tgId)]);
    const subscription = account ? await getSubscription(account.user_id) : null;
    return {
      user,
      account,
      subscription,
      referrals: 0,
      referrals_list: [],
      referral_days: 0,
      paid_referrals: 0,
      payments: [],
    };
  }
  const row = data as Record<string, unknown>;
  return {
    user: (row.user as BotUserRow | null) ?? null,
    account: (row.account as AccountRow | null) ?? null,
    subscription: (row.subscription as SubscriptionRow | null) ?? null,
    referrals: Number(row.referrals ?? 0) || 0,
    referrals_list: Array.isArray(row.referrals_list)
      ? (row.referrals_list as ReferralRow[])
      : [],
    referral_days: Number(row.referral_days ?? 0) || 0,
    paid_referrals: Number(row.paid_referrals ?? 0) || 0,
    payments: Array.isArray(row.payments)
      ? (row.payments as PaymentRow[])
      : [],
  };
}

// ---------- Журнал администратора ----------

export async function logAdmin(
  adminId: number,
  action: string,
  targetId: number,
  details: string,
): Promise<void> {
  await db().from("admin_logs").insert({
    admin_id: adminId,
    action,
    target_id: targetId,
    details,
    created_at: new Date().toISOString(),
  });
}

// ---------- Рассылка ----------

// Очередь рассылки лежит в bot_state под служебным chat_id = 0.
// Рассылка идёт порциями: каждый вызов функции двигает курсор, поэтому
// serverless-инстанс не упирается в лимит времени, а рассылка переживает
// холодный старт.
export const BROADCAST_QUEUE_ID = 0;
export const BROADCAST_CHUNK = 80;

export type BroadcastQueue = {
  text: string;
  cursor: number;
  total: number;
  admin_id: number;
  done: boolean;
};

export async function countRecipients(adminId: number): Promise<number> {
  const { count, error } = await db()
    .from("bot_users")
    .select("id", { count: "exact", head: true })
    .eq("banned", false)
    .neq("id", adminId);
  if (error || count === null) return 0;
  return count;
}

export async function enqueueBroadcast(
  adminId: number,
  text: string,
): Promise<number> {
  const total = await countRecipients(adminId);
  const { error } = await db().from("bot_state").upsert({
    chat_id: BROADCAST_QUEUE_ID,
    state: "broadcast",
    data: { text, cursor: 0, total, admin_id: adminId, done: false },
    updated_at: new Date().toISOString(),
  }, { onConflict: "chat_id" });
  if (error) return -1;
  return total;
}

export async function readBroadcastQueue(): Promise<BroadcastQueue | null> {
  const { data, error } = await db()
    .from("bot_state")
    .select("data")
    .eq("chat_id", BROADCAST_QUEUE_ID)
    .limit(1);
  if (error) return null;
  const row = (data && data[0]) as { data: BroadcastQueue | null } | undefined;
  if (!row?.data || row.data.done) return null;
  return row.data;
}

// Возвращает получателей очередной порции.
export async function broadcastRecipients(
  adminId: number,
  offset: number,
  size: number,
): Promise<number[]> {
  const { data, error } = await db()
    .from("bot_users")
    .select("id")
    .eq("banned", false)
    .neq("id", adminId)
    .order("id", { ascending: true })
    .range(offset, offset + size - 1);
  if (error) return [];
  return ((data as { id: number }[]) ?? []).map((row) => row.id);
}

export async function advanceBroadcast(cursor: number): Promise<void> {
  await db().from("bot_state").update({
    data: { ...(await readRawQueue()), cursor },
    updated_at: new Date().toISOString(),
  }).eq("chat_id", BROADCAST_QUEUE_ID);
}

export async function finishBroadcast(adminId: number): Promise<void> {
  const raw = await readRawQueue();
  await db().from("bot_state").update({
    data: { ...(raw ?? {}), done: true },
    updated_at: new Date().toISOString(),
  }).eq("chat_id", BROADCAST_QUEUE_ID);
  await logAdmin(adminId, "broadcast", 0, "рассылка завершена");
}

async function readRawQueue(): Promise<BroadcastQueue | null> {
  const { data } = await db()
    .from("bot_state")
    .select("data")
    .eq("chat_id", BROADCAST_QUEUE_ID)
    .limit(1);
  const row = (data && data[0]) as { data: BroadcastQueue | null } | undefined;
  return row?.data ?? null;
}
