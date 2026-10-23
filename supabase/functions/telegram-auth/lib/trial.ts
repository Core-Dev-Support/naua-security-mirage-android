// Пробник для новых пользователей.
//
// Каждый, кто вошёл через Google впервые, получает пробник: подписку на
// TRIAL_DAYS. Реферальная ссылка тут ни при чём — пробник у всех.
//
// Если подписка уже была (план или срок), пробник не выдаётся: это
// возвращающийся пользователь, а не новый.

export const TRIAL_DAYS = 1;

// subscriptionRow — строка подписки, которую нужно записать.
//
// hadSubscription = true, если у пользователя уже была подписка: тогда строка
// не трогает срок и план, а только обновляет почту и привязку.
export function subscriptionRow(args: {
  userId: string;
  email: string;
  tgId: number;
  now: string;
  hadSubscription: boolean;
  nowMs?: number;
}): Record<string, unknown> {
  const row: Record<string, unknown> = {
    user_id: args.userId,
    email: args.email,
    tg_id: args.tgId,
    updated_at: args.now,
  };
  if (args.hadSubscription) return row;

  const start = args.nowMs ?? Date.parse(args.now);
  row.is_active = true;
  row.plan = "trial";
  row.paid_until = new Date(start + TRIAL_DAYS * 86_400_000).toISOString();
  return row;
}

// hadSubscriptionBefore — была ли подписка раньше.
//
// Строка в таблице subscriptions создаётся при привязке аккаунта, поэтому
// сама по себе ничего не значит: важны plan и paid_until.
export function hadSubscriptionBefore(
  previous: { plan?: string | null; paid_until?: string | null } | null | undefined,
): boolean {
  if (!previous) return false;
  return Boolean(previous.plan || previous.paid_until);
}
