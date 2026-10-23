import {
  REFERRAL_BONUS_DAYS,
  REFERRAL_MILESTONE_DAYS,
  REFERRAL_MILESTONE_EVERY,
  friendsToMilestone,
} from "./referral.ts";

// Правила реферальной программы должны совпадать с вебхуком: он начисляет
// бонус по этим же числам.
const EXPECTED = {
  bonus: 2,
  every: 5,
  milestone: 10,
};

Deno.test("the referral rules match the webhook contract", () => {
  if (REFERRAL_BONUS_DAYS !== EXPECTED.bonus) {
    throw new Error(`bonus days: expected ${EXPECTED.bonus}, got ${REFERRAL_BONUS_DAYS}`);
  }
  if (REFERRAL_MILESTONE_EVERY !== EXPECTED.every) {
    throw new Error(`milestone every: expected ${EXPECTED.every}, got ${REFERRAL_MILESTONE_EVERY}`);
  }
  if (REFERRAL_MILESTONE_DAYS !== EXPECTED.milestone) {
    throw new Error(`milestone days: expected ${EXPECTED.milestone}, got ${REFERRAL_MILESTONE_DAYS}`);
  }
});

Deno.test("the milestone progress counts down to the next bonus", () => {
  if (friendsToMilestone(0) !== 5) throw new Error("0 paid -> 5 friends left");
  if (friendsToMilestone(1) !== 4) throw new Error("1 paid -> 4 friends left");
  if (friendsToMilestone(4) !== 1) throw new Error("4 paid -> 1 friend left");
  if (friendsToMilestone(5) !== 5) throw new Error("5 paid -> a new round of 5");
});
