import { useEffect } from "react";
import { Check, Loader2 } from "lucide-react";

interface LibraryPreparingOverlayProps {
  /** True only when the library has REALLY finished settling (not just the native scan). */
  done: boolean;
  /** Called after the success checkmark has been shown briefly. */
  onClose: () => void;
}

/**
 * "Preparing library" overlay: a circular spinner while the library is still
 * being prepared, then a success checkmark for ~2s once it's REALLY done.
 *
 * `done` is driven by the caller (Home), which only flips it true after the
 * library has stopped changing — NOT when the native DB scan emits 'complete'
 * (that fires well before the frontend finishes ingesting the tracks, which is
 * what made the checkmark appear far too early).
 */
export function LibraryPreparingOverlay({
  done,
  onClose,
}: LibraryPreparingOverlayProps) {
  useEffect(() => {
    if (!done) return;
    const t = window.setTimeout(() => onClose(), 1800);
    return () => window.clearTimeout(t);
  }, [done, onClose]);

  return (
    <div
      className="fixed inset-0 z-[120] flex items-center justify-center bg-black/85 p-6 backdrop-blur-sm"
      role="alertdialog"
      aria-modal="true"
      aria-live="assertive"
    >
      <div className="mx-6 w-full max-w-md space-y-4 rounded-2xl border border-zinc-800 bg-zinc-950 p-6 text-center shadow-2xl">
        {done ? (
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-[var(--ep-red)] shadow-lg">
            <Check className="h-8 w-8 text-white" strokeWidth={3} />
          </div>
        ) : (
          <Loader2
            className="mx-auto h-10 w-10 animate-spin text-[var(--ep-red)]"
            aria-hidden="true"
          />
        )}
        <h2 className="text-xl font-semibold text-white">
          {done ? "¡Biblioteca lista!" : "Preparando tu biblioteca"}
        </h2>
        <p className="text-sm leading-relaxed text-zinc-300">
          {done
            ? "Ya puedes disfrutar tu música."
            : "Estoy preparando tus canciones para que la reproducción funcione perfecta. Esto puede tardar un momento si agregaste muchas canciones."}
        </p>
      </div>
    </div>
  );
}

export default LibraryPreparingOverlay;
