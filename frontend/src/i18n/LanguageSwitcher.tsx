import { setLocale } from "./actions";
import { LOCALES } from "./locale";
import { getLocale, getMessages } from "./server";

/**
 * Language choice as one submit button per other language: it works without JavaScript and keeps the
 * current page, because the server action only re-renders it.
 */
export async function LanguageSwitcher() {
  const [locale, t] = await Promise.all([getLocale(), getMessages()]);
  return (
    <form action={setLocale} className="language-switcher" aria-label={t.common.language}>
      {LOCALES.filter((other) => other !== locale).map((other) => (
        <button key={other} type="submit" name="locale" value={other} lang={other} className="button secondary">
          {t.common.languageNames[other]}
        </button>
      ))}
    </form>
  );
}
