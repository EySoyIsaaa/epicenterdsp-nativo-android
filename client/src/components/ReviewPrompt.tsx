/**
 * ReviewPrompt — tarjeta para invitar a calificar con 5 estrellas.
 * Entra desde abajo, con las estrellas apareciendo escalonadas.
 */

import { Star } from 'lucide-react';
import type { TranslateFn } from '@/components/home/types';

interface ReviewPromptProps {
  t: TranslateFn;
  onRate: () => void;
  onLater: () => void;
  onNever: () => void;
}

export function ReviewPrompt({ t, onRate, onLater, onNever }: ReviewPromptProps) {
  return (
    <div className="modal-backdrop fixed inset-0 z-[70] flex items-end justify-center bg-black/70 p-4 sm:items-center">
      <div className="review-card w-full max-w-sm rounded-3xl border border-[var(--ep-border)] bg-zinc-900 p-6 text-center shadow-2xl">
        <div className="mb-4 flex justify-center gap-1.5">
          {Array.from({ length: 5 }).map((_, index) => (
            <Star
              key={index}
              className="review-star h-7 w-7 text-[var(--ep-red)]"
              fill="currentColor"
              style={{ animationDelay: `${120 + index * 90}ms` }}
            />
          ))}
        </div>

        <h3 className="premium-title text-lg font-black text-white">
          {t('review.title')}
        </h3>
        <p className="mt-2 text-sm leading-relaxed text-[var(--ep-text-secondary)]">
          {t('review.message')}
        </p>

        <button
          onClick={onRate}
          className="mt-5 w-full rounded-full bg-[var(--ep-red)] px-5 py-3 text-sm font-black uppercase tracking-[0.1em] text-white shadow-[0_0_20px_rgba(255,16,42,0.35)]"
        >
          {t('review.rate')}
        </button>
        <div className="mt-3 flex items-center justify-center gap-5">
          <button
            onClick={onLater}
            className="text-xs font-semibold text-zinc-300 hover:text-white"
          >
            {t('review.later')}
          </button>
          <button
            onClick={onNever}
            className="text-xs font-semibold text-zinc-500 hover:text-zinc-300"
          >
            {t('review.never')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default ReviewPrompt;
