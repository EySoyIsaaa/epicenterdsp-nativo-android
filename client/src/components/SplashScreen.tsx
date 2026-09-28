/**
 * SplashScreen — apertura "Bass Impact".
 *
 * Coreografía (el detalle fino vive en index.css, sección INTRO):
 *   0.00s  negro; un punto rojo se enciende en el centro
 *   0.20s  tres ondas expansivas salen escalonadas — el golpe de graves
 *   0.40s  el logo entra con rebote y un halo rojo que respira
 *   1.20s  una línea roja barre por debajo del logo
 *   1.45s  las barras de espectro cobran vida
 *   1.80s  aparece la versión
 *   3.08s  toda la escena se acerca y se disuelve hacia la app
 *
 * Todo se anima con transform/opacity (compuesto en GPU, sin reflow) y respeta
 * prefers-reduced-motion. Se puede saltar tocando la pantalla.
 *
 * El logo es PNG a propósito: logo-menu-inicial.svg trae un elemento SVG <font>
 * ("Good Times"), que Chromium no soporta desde hace años, así que su texto
 * caía a una tipografía genérica con coordenadas pensadas para otra.
 * `epicenter-logo.png` se genera con transparencia real a partir de LOGO
 * APP.png (que viene compuesto sobre negro opaco): si no, se ve el cuadrado.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useLanguage } from '@/hooks/useLanguage';

interface SplashScreenProps {
  onFinish: () => void;
  duration?: number;
}

const EXIT_MS = 520;
const SPECTRUM_BARS = 28;

export function SplashScreen({ onFinish, duration = 3600 }: SplashScreenProps) {
  const [leaving, setLeaving] = useState(false);
  const { t } = useLanguage();
  const finishedRef = useRef(false);
  const timersRef = useRef<number[]>([]);

  const finish = useCallback(() => {
    if (finishedRef.current) return;
    finishedRef.current = true;
    setLeaving(true);
    const timer = window.setTimeout(onFinish, EXIT_MS);
    timersRef.current.push(timer);
  }, [onFinish]);

  useEffect(() => {
    const timer = window.setTimeout(finish, Math.max(0, duration - EXIT_MS));
    timersRef.current.push(timer);
    const timers = timersRef.current;
    return () => timers.forEach(window.clearTimeout);
  }, [duration, finish]);

  return (
    <div
      className={`intro-root ${leaving ? 'intro-leaving' : ''}`}
      onClick={finish}
      role="presentation"
    >
      <div className="intro-stage">
        {/* Ondas expansivas: el golpe de graves */}
        <span className="intro-ring intro-ring-1" />
        <span className="intro-ring intro-ring-2" />
        <span className="intro-ring intro-ring-3" />

        {/* Halo que respira detrás del logo */}
        <span className="intro-bloom" />

        <img
          src="/epicenter-logo.png"
          alt="EpicenterDSP"
          className="intro-logo"
          draggable={false}
        />

        {/* Barrido rojo */}
        <span className="intro-sweep" />
      </div>

      <div className="intro-spectrum" aria-hidden="true">
        {Array.from({ length: SPECTRUM_BARS }).map((_, index) => (
          <span
            key={index}
            className="intro-bar"
            style={{
              // Retardo y altura desfasados por barra: parece audio real en vez
              // de una animación en bloque.
              animationDelay: `${1.45 + (index % 7) * 0.07}s`,
              ['--bar-peak' as string]: `${36 + ((index * 37) % 52)}%`,
            }}
          />
        ))}
      </div>

      <div className="intro-footer">
        <span className="intro-version">v{t('app.version')}</span>
        <span className="intro-tagline">Bass Reconstruction Technology</span>
      </div>
    </div>
  );
}

export default SplashScreen;
