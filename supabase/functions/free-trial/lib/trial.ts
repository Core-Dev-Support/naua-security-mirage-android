// Пробный день: один бесплатный день платного узла.
//
// Правила выдачи (все проверки — на сервере, клиент ничего не решает):
//   1. Пробник выдаётся только тому, у кого подписки ещё не было: в строке
//      subscriptions не должно быть ни плана, ни срока. Это правило общее с
//      ботом: пробник в боте и пробник в приложении — один и тот же пробник,
//      поэтому новый Google-аккаунт в боте закрывает пробник и в приложении.
//   2. Пробник выдаётся один раз на устройство. Ключ устройства приходит от
//      приложения и жёстко привязан к строке trial_devices: повторный запрос
//      с тем же ключом отклоняется базой, а не проверкой в коде.
//
// Ключ платного узла выдаёт france-config: он смотрит на ту же строку
// subscriptions и, если пробник активен, отдаёт ключ. Поэтому здесь достаточно
// записать пробник в подписку.

export const TRIAL_DAYS = 1;
export const TRIAL_PLAN = "trial";

// hadSubscriptionBefore — была ли подписка раньше.
//
// Строка в таблице subscriptions создаётся при первом входе, поэтому сама по
// себе ничего не значит: важны plan и paid_until.
export function hadSubscriptionBefore(
  previous: { plan?: string | null; paid_until?: string | null } | null | undefined,
): boolean {
  if (!previous) return false;
  return Boolean(previous.plan || previous.paid_until);
}

// trialExpiry — до какого момента действует пробник.
export function trialExpiry(nowMs: number, days: number = TRIAL_DAYS): string {
  return new Date(nowMs + days * 86_400_000).toISOString();
}

// Строка подписки для пробника.
export type TrialSubscriptionRow = {
  user_id: string;
  email: string;
  is_active: boolean;
  plan: string;
  paid_until: string;
  updated_at: string;
};

// trialSubscriptionRow — что записать в subscriptions, чтобы пробник был
// виден и боту, и приложению, и france-config.
export function trialSubscriptionRow(args: {
  userId: string;
  email: string;
  now: string;
  nowMs?: number;
}): TrialSubscriptionRow {
  const start = args.nowMs ?? Date.parse(args.now);
  return {
    user_id: args.userId,
    email: args.email,
    is_active: true,
    plan: TRIAL_PLAN,
    paid_until: trialExpiry(start),
    updated_at: args.now,
  };
}
