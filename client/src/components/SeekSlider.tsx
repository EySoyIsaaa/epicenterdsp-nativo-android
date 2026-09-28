/**
 * SeekSlider — línea de tiempo del reproductor.
 *
 * Se abandonó `input[type=range]`: en el WebView de Android su arrastre depende
 * de gestos internos del navegador que resultaron poco fiables aquí (y se
 * rompen del todo con `touch-action: none`). Este control maneja el gesto él
 * mismo, que es predecible en cualquier versión:
 *
 *   · touchstart / mousedown sobre la barra → salta a esa posición y hace seek
 *   · los listeners de move/end se enganchan a WINDOW, no al elemento, para que
 *     el arrastre siga vivo aunque el dedo se salga de la barra
 *   · durante el arrastre manda el valor LOCAL (el reproductor reporta su
 *     posición varias veces por segundo y si no, le pelearía al dedo)
 *   · al soltar se sostiene el destino hasta que el reproductor confirme una
 *     posición cercana; si no, los primeros reportes traen la posición vieja y
 *     la barra pega un salto atrás
 */

import { useCallback, useEffect, useRef, useState } from 'react';

interface SeekSliderProps {
  currentTime: number;
  duration: number;
  onSeek: (time: number) => void;
  disabled?: boolean;
  formatTime: (seconds: number) => string;
}

const SEEK_CONFIRM_TOLERANCE_S = 1.5;
const SEEK_HOLD_TIMEOUT_MS = 2000;
const SEEK_THROTTLE_MS = 120;
const KEYBOARD_STEP_S = 5;

export function SeekSlider({
  currentTime,
  duration,
  onSeek,
  disabled = false,
  formatTime,
}: SeekSliderProps) {
  const [scrubbing, setScrubbing] = useState(false);
  const [scrubValue, setScrubValue] = useState(0);
  const [pendingSeek, setPendingSeek] = useState<number | null>(null);

  const trackRef = useRef<HTMLDivElement | null>(null);
  const scrubValueRef = useRef(0);
  const lastSeekAtRef = useRef(0);
  const draggingRef = useRef(false);

  const hasDuration = duration > 0;
  const isDisabled = disabled || !hasDuration;

  // Prioridad: dedo > destino recién pedido > posición real del reproductor.
  const displayValue = scrubbing ? scrubValue : pendingSeek ?? currentTime;
  const percent = hasDuration
    ? Math.max(0, Math.min(100, (displayValue / duration) * 100))
    : 0;

  // Suelta el "hold" cuando el reproductor llega cerca del destino.
  useEffect(() => {
    if (pendingSeek == null) return;
    if (Math.abs(currentTime - pendingSeek) < SEEK_CONFIRM_TOLERANCE_S) {
      setPendingSeek(null);
    }
  }, [currentTime, pendingSeek]);

  // Red de seguridad del hold. Depende SOLO de pendingSeek para armarse una vez
  // (con currentTime se reiniciaría en cada reporte y nunca dispararía).
  useEffect(() => {
    if (pendingSeek == null) return;
    const timer = window.setTimeout(() => setPendingSeek(null), SEEK_HOLD_TIMEOUT_MS);
    return () => window.clearTimeout(timer);
  }, [pendingSeek]);

  const valueFromClientX = useCallback(
    (clientX: number): number => {
      const el = trackRef.current;
      if (!el || duration <= 0) return 0;
      const rect = el.getBoundingClientRect();
      if (rect.width <= 0) return 0;
      const ratio = (clientX - rect.left) / rect.width;
      return Math.max(0, Math.min(1, ratio)) * duration;
    },
    [duration],
  );

  const applyValue = useCallback(
    (value: number, force: boolean) => {
      scrubValueRef.current = value;
      setScrubValue(value);
      const now = Date.now();
      if (force || now - lastSeekAtRef.current >= SEEK_THROTTLE_MS) {
        lastSeekAtRef.current = now;
        onSeek(value);
      }
    },
    [onSeek],
  );

  // Un solo camino para arrancar el arrastre, venga de dedo o de ratón.
  const beginDrag = useCallback(
    (clientX: number, isTouch: boolean) => {
      if (isDisabled) return;
      draggingRef.current = true;
      setScrubbing(true);
      applyValue(valueFromClientX(clientX), true);

      const moveEvent = isTouch ? 'touchmove' : 'mousemove';
      const endEvent = isTouch ? 'touchend' : 'mouseup';

      const onMove = (event: Event) => {
        if (!draggingRef.current) return;
        const x = isTouch
          ? (event as TouchEvent).touches[0]?.clientX
          : (event as MouseEvent).clientX;
        if (typeof x !== 'number') return;
        // Evita que la página haga scroll mientras se arrastra.
        if (event.cancelable) event.preventDefault();
        applyValue(valueFromClientX(x), false);
      };

      const onEnd = () => {
        if (!draggingRef.current) return;
        draggingRef.current = false;
        const value = scrubValueRef.current;
        setPendingSeek(value);
        setScrubbing(false);
        onSeek(value); // seek final, sin limitar
        window.removeEventListener(moveEvent, onMove);
        window.removeEventListener(endEvent, onEnd);
        window.removeEventListener('touchcancel', onEnd);
      };

      // En WINDOW, no en el elemento: si se engancharan al elemento, sacar el
      // dedo de la barra dejaría el arrastre colgado sin recibir el "end".
      window.addEventListener(moveEvent, onMove, { passive: false });
      window.addEventListener(endEvent, onEnd);
      window.addEventListener('touchcancel', onEnd);
    },
    [isDisabled, applyValue, valueFromClientX, onSeek],
  );

  useEffect(() => {
    return () => {
      draggingRef.current = false;
    };
  }, []);

  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent) => {
      if (isDisabled) return;
      let next: number | null = null;
      if (event.key === 'ArrowRight') next = Math.min(duration, displayValue + KEYBOARD_STEP_S);
      if (event.key === 'ArrowLeft') next = Math.max(0, displayValue - KEYBOARD_STEP_S);
      if (next == null) return;
      event.preventDefault();
      setPendingSeek(next);
      onSeek(next);
    },
    [isDisabled, duration, displayValue, onSeek],
  );

  return (
    <div className="seek-wrap" data-scrubbing={scrubbing ? 'true' : 'false'}>
      <div
        ref={trackRef}
        className={`seek-control ${isDisabled ? 'is-disabled' : ''}`}
        onTouchStart={(event) => {
          const touch = event.touches[0];
          if (touch) beginDrag(touch.clientX, true);
        }}
        onMouseDown={(event) => {
          event.preventDefault();
          beginDrag(event.clientX, false);
        }}
        onKeyDown={handleKeyDown}
        role="slider"
        tabIndex={isDisabled ? -1 : 0}
        aria-label="Posición de reproducción"
        aria-valuemin={0}
        aria-valuemax={hasDuration ? Math.round(duration) : 0}
        aria-valuenow={Math.round(displayValue)}
        aria-valuetext={formatTime(displayValue)}
        aria-disabled={isDisabled}
      >
        <div className="seek-track">
          <div className="seek-fill" style={{ width: `${percent}%` }} />
          <div className="seek-thumb" style={{ left: `${percent}%` }} />
        </div>
      </div>
      <div className="mt-2 flex justify-between text-[10px] font-bold tabular-nums text-[var(--ep-text-muted)]">
        <span className={scrubbing ? 'seek-time-active' : undefined}>
          {formatTime(displayValue)}
        </span>
        <span>{formatTime(duration)}</span>
      </div>
    </div>
  );
}

export default SeekSlider;
