// Telegram Bot API: типы, вызовы, клавиатуры.
//
// Модуль самодостаточен: кросс-импорты между каталогами supabase/functions/*
// не деплоятся, поэтому всё нужное живёт внутри каталога функции.

const API = "https://api.telegram.org";

export type InlineButton = {
  text: string;
  callback_data?: string;
  url?: string;
  // Кнопка «скопировать»: Telegram кладёт текст в буфер обмена.
  copy_text?: { text: string };
};

export type InlineKeyboard = InlineButton[][];

export type KeyboardButton = { text: string };

export type ReplyKeyboard = KeyboardButton[][];

export type TelegramUser = {
  id: number;
  first_name?: string;
  last_name?: string;
  username?: string;
};

export type TelegramChat = {
  id: number;
  type?: string;
};

export type TelegramMessage = {
  message_id: number;
  chat: TelegramChat;
  from?: TelegramUser;
  text?: string;
  caption?: string;
  photo?: Array<{ file_id: string; file_unique_id?: string }>;
  video?: { file_id: string; file_unique_id?: string };
  document?: {
    file_id: string;
    file_unique_id?: string;
    file_name?: string;
    mime_type?: string;
  };
};

export type CallbackQuery = {
  id: string;
  data?: string;
  from?: TelegramUser;
  message?: TelegramMessage;
};

export type TelegramUpdate = {
  update_id: number;
  message?: TelegramMessage;
  edited_message?: TelegramMessage;
  callback_query?: CallbackQuery;
};

export type BotStateRow = {
  state: string;
  data: Record<string, unknown> | null;
};

export type BotUserRow = {
  id: number;
  username: string | null;
  name: string | null;
  banned: boolean;
  ban_reason: string | null;
  warnings: number;
  referred_by: number | null;
  sub_until: string | null;
  last_notified: string | null;
  lang: string | null;
  /** Когда принял документы; null — ещё не принимал. */
  consent_at?: string | null;
  /** Версия принятых документов: при обновлении спрашиваем снова. */
  consent_version?: string | null;
  screen_message_id?: number | null;
  created_at?: string | null;
};

export type AccountRow = {
  tg_id: number;
  user_id: string;
  email: string | null;
  linked_at?: string | null;
  screen_message_id?: number | null;
  /** Версия ссылки подписки: сброс увеличивает её и гасит старые адреса. */
  link_version?: number | null;
};

// Вложение к сообщению: скрин, видео или файл.
export type FileRef = {
  file_id: string;
  kind: "photo" | "video" | "document" | "audio";
  name?: string;
  mime?: string;
};

export type TicketRow = {
  id: number;
  user_id: number;
  message: string | null;
  status: string;
  replied: boolean;
  created_at: string;
};

const USER_AGENTS = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
];

// Клиент с переиспользуемыми соединениями: один edge-инстанс делает десятки
// запросов к api.telegram.org, постоянные TLS-хэндшейки съедали время.
class Client {
  async post(url: string, body: unknown, timeoutMs = 10_000): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "User-Agent": USER_AGENTS[0],
        },
        body: JSON.stringify(body),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
  }
}

const httpClient = new Client();

// Снимает HTML-спецсимволы — текст попадает в сообщения с parse_mode=HTML.
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// Приводит произвольный текст к безопасному для HTML виду.
export function safeText(value: string): string {
  return escapeHtml(value.replace(/[\t\n\r]/g, " "));
}

// Только разрешённые символы username — попадает в фильтры PostgREST.
export function safeUsername(value: string): string {
  return value.replace(/[^A-Za-z0-9_]/g, "").slice(0, 32);
}

// validateKeyboard — проверяет, что у каждой кнопки есть callback_data или url.
//
// Telegram не принимает кнопку, у которой есть только текст: он отвергает
// всю клавиатуру целиком («Text buttons are not allowed in the inline
// keyboard»), и экран молча не отрисовывается — пользователь жмёт кнопку и
// ничего не происходит. Поэтому проверяем клавиатуру до отправки.
export function validateKeyboard(keyboard: InlineKeyboard): string | null {
  for (const row of keyboard) {
    for (const button of row) {
      if (!button.callback_data && !button.url && !button.copy_text) {
        return `кнопка «${button.text}» без callback_data, url и copy_text`;
      }
    }
  }
  return null;
}

export class Telegram {
  #token: string;

  constructor(token: string) {
    this.#token = token;
  }

  get token(): string {
    return this.#token;
  }

  async #call(method: string, body: unknown): Promise<boolean> {
    if (!this.#token) return false;
    try {
      const response = await this.post(method, body);
      return response.ok;
    } catch {
      return false;
    }
  }

  async post(method: string, body: unknown): Promise<Response> {
    return await httpClient.post(`${API}/bot${this.#token}/${method}`, body);
  }

  // sendMessage возвращает id отправленного сообщения (нужен, чтобы потом
  // переписать этот же пузырь) и null, если отправка не удалась.
  async sendMessage(
    chatId: number,
    text: string,
    keyboard?: InlineKeyboard,
  ): Promise<number | null> {
    const payload: Record<string, unknown> = {
      chat_id: chatId,
      text,
      parse_mode: "HTML",
      disable_web_page_preview: true,
    };
    if (keyboard) {
      const problem = validateKeyboard(keyboard);
      if (problem) throw new Error(`Некорректная клавиатура: ${problem}`);
      payload.reply_markup = { inline_keyboard: keyboard };
    }
    if (!this.#token) return null;
    try {
      const response = await this.post("sendMessage", payload);
      if (!response.ok) return null;
      const parsed = await response.json();
      const id = parsed?.result?.message_id;
      return typeof id === "number" ? id : null;
    } catch {
      return null;
    }
  }

  // copyFile — копирует файл из Telegram в наш чат (файл нельзя переслать
  // по file_id напрямую, его надо скопировать).
  async copyFile(fileId: string): Promise<string | null> {
    if (!this.#token) return null;
    try {
      const response = await this.post("copyFile", { file_id: fileId });
      if (!response.ok) return null;
      const parsed = await response.json();
      return parsed?.result?.file_id ?? null;
    } catch {
      return null;
    }
  }

  // sendFile — отправляет файл в чат по его file_id.
  async sendFile(
    chatId: number,
    fileId: string,
    kind: string,
    caption?: string,
  ): Promise<boolean> {
    const method = kind === "photo"
      ? "sendPhoto"
      : kind === "video"
      ? "sendVideo"
      : "sendDocument";
    const payload: Record<string, unknown> = { chat_id: chatId };
    payload[kind === "document" ? "document" : kind] = fileId;
    if (caption) payload.caption = caption;
    return await this.#call(method, payload);
  }

  async sendPhoto(
    chatId: number,
    photoUrl: string,
    caption: string,
    keyboard?: InlineKeyboard,
  ): Promise<boolean> {
    const payload: Record<string, unknown> = {
      chat_id: chatId,
      photo: photoUrl,
      caption,
      parse_mode: "HTML",
    };
    if (keyboard) {
      const problem = validateKeyboard(keyboard);
      if (problem) throw new Error(`Некорректная клавиатура: ${problem}`);
      payload.reply_markup = { inline_keyboard: keyboard };
    }
    return await this.#call("sendPhoto", payload);
  }

  // Исход правки:
  //   "ok"        — сообщение обновлено;
  //   "unchanged" — содержимое уже такое же (Telegram отдаёт 400
  //                 "message is not modified"), дубликат слать нельзя;
  //   "error"     — правка не удалась, экран надо создать заново.
  async editMessage(
    chatId: number,
    messageId: number,
    text: string,
    keyboard?: InlineKeyboard,
  ): Promise<"ok" | "unchanged" | "error"> {
    const payload: Record<string, unknown> = {
      chat_id: chatId,
      message_id: messageId,
      text,
      parse_mode: "HTML",
      disable_web_page_preview: true,
    };
    if (keyboard) {
      const problem = validateKeyboard(keyboard);
      if (problem) throw new Error(`Некорректная клавиатура: ${problem}`);
      payload.reply_markup = { inline_keyboard: keyboard };
    }
    if (!this.#token) return "error";
    try {
      const response = await this.post("editMessageText", payload);
      if (response.ok) return "ok";
      const body = await response.text();
      if (body.includes("not modified")) return "unchanged";
      return "error";
    } catch {
      return "error";
    }
  }

  async deleteMessage(chatId: number, messageId: number): Promise<boolean> {
    return await this.#call("deleteMessage", {
      chat_id: chatId,
      message_id: messageId,
    });
  }

  async answerCallback(callbackId: string, text = "", alert = false): Promise<boolean> {
    return await this.#call("answerCallbackQuery", {
      callback_query_id: callbackId,
      ...(text ? { text } : {}),
      ...(alert ? { show_alert: true } : {}),
    });
  }

  async setWebhook(url: string, secret: string): Promise<boolean> {
    return await this.#call("setWebhook", {
      url,
      secret_token: secret,
      allowed_updates: ["message", "edited_message", "callback_query"],
    });
  }

  async getWebhookInfo(): Promise<{ url?: string; secret_token?: string } | null> {
    try {
      const response = await this.post("getWebhookInfo", {});
      if (!response.ok) return null;
      const parsed = await response.json();
      return parsed?.result ?? null;
    } catch {
      return null;
    }
  }
}
