/**
 * useReviewPrompt — recordatorio para calificar la app.
 *
 * Criterio: pedir reseña solo cuando la app ya demostró valor, y no volver a
 * insistir de inmediato si el usuario dijo "después". Nada de mostrarlo en el
 * primer arranque.
 *
 *   · primera vez: a partir del 3er arranque Y 2 días desde la instalación
 *   · "después"  : se pospone 7 días y 5 arranques más
 *   · "calificar" / "no preguntar": no se vuelve a mostrar
 *
 * Todo el estado vive en localStorage; si no está disponible (modo privado,
 * WebView raro) el hook simplemente nunca muestra el aviso en vez de romper.
 */

import { useCallback, useEffect, useState } from 'react';

const STORAGE_KEY = 'epicenter-review-prompt';
const PLAY_STORE_URL =
  'https://play.google.com/store/apps/details?id=com.epicenter.hifi';

const DAY_MS = 24 * 60 * 60 * 1000;
const FIRST_ASK_LAUNCHES = 3;
const FIRST_ASK_DAYS = 2;
const SNOOZE_DAYS = 7;
const SNOOZE_LAUNCHES = 5;

interface ReviewState {
  firstLaunchAt: number;
  launches: number;
  dismissedUntil: number;
  dismissedAtLaunch: number;
  done: boolean;
}

const DEFAULT_STATE: ReviewState = {
  firstLaunchAt: 0,
  launches: 0,
  dismissedUntil: 0,
  dismissedAtLaunch: 0,
  done: false,
};

function readState(): ReviewState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_STATE };
    return { ...DEFAULT_STATE, ...(JSON.parse(raw) as Partial<ReviewState>) };
  } catch {
    return null;
  }
}

function writeState(state: ReviewState): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* sin persistencia: el aviso no se mostrará, que es el fallo seguro */
  }
}

export interface ReviewPromptApi {
  shouldShow: boolean;
  rate: () => void;
  later: () => void;
  never: () => void;
}

export function useReviewPrompt(enabled: boolean): ReviewPromptApi {
  const [shouldShow, setShouldShow] = useState(false);
  const [state, setState] = useState<ReviewState | null>(null);

  // Cuenta el arranque una sola vez por sesión.
  useEffect(() => {
    const current = readState();
    if (!current) return;
    const next: ReviewState = {
      ...current,
      firstLaunchAt: current.firstLaunchAt || Date.now(),
      launches: current.launches + 1,
    };
    writeState(next);
    setState(next);
  }, []);

  // Decide si toca preguntar.
  useEffect(() => {
    if (!enabled || !state || state.done) return;
    const now = Date.now();
    if (now < state.dismissedUntil) return;
    if (state.launches < FIRST_ASK_LAUNCHES) return;
    if (now - state.firstLaunchAt < FIRST_ASK_DAYS * DAY_MS) return;
    if (
      state.dismissedAtLaunch > 0 &&
      state.launches < state.dismissedAtLaunch + SNOOZE_LAUNCHES
    ) {
      return;
    }
    // Pequeño respiro para no aparecer encima de la intro / la carga inicial.
    const timer = window.setTimeout(() => setShouldShow(true), 1200);
    return () => window.clearTimeout(timer);
  }, [enabled, state]);

  const close = useCallback(
    (patch: Partial<ReviewState>) => {
      setShouldShow(false);
      setState((prev) => {
        const base = prev ?? { ...DEFAULT_STATE };
        const next = { ...base, ...patch };
        writeState(next);
        return next;
      });
    },
    [],
  );

  const rate = useCallback(() => {
    close({ done: true });
    try {
      window.open(PLAY_STORE_URL, '_blank');
    } catch {
      window.location.href = PLAY_STORE_URL;
    }
  }, [close]);

  const later = useCallback(() => {
    close({
      dismissedUntil: Date.now() + SNOOZE_DAYS * DAY_MS,
      dismissedAtLaunch: state?.launches ?? 0,
    });
  }, [close, state]);

  const never = useCallback(() => close({ done: true }), [close]);

  return { shouldShow, rate, later, never };
}
