import en from "@/i18n/messages/en.json";
import zh from "@/i18n/messages/zh.json";
import ja from "@/i18n/messages/ja.json";

type Messages = typeof en;

const messagesMap: Record<string, Messages> = { en, zh, ja };

function resolveKey(obj: any, key: string): string | undefined {
  const parts = key.split(".");
  let result: any = obj;
  for (const part of parts) {
    result = result?.[part];
    if (result === undefined) return undefined;
  }
  return typeof result === "string" ? result : undefined;
}

export function getTranslations(locale: string, namespace: string) {
  const messages = messagesMap[locale] || zh;
  const ns = (messages as Record<string, any>)[namespace];
  const fallbackNs = (zh as Record<string, any>)[namespace];
  return (key: string): string => {
    return resolveKey(ns, key) || resolveKey(fallbackNs, key) || key;
  };
}
