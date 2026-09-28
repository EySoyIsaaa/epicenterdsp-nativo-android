/**
 * Ajuste automático por canción, calculado a partir del espectro REAL medido en
 * el nativo (SpectrumAnalyzer). No usa presets: cada canción produce su propia
 * corrección.
 *
 * Reglas del producto (pedidas explícitamente):
 *   · Epicenter: solo se mueve INTENSITY, y dentro de 80..100. Sweep y Width se
 *     derivan del contenido grave. BALANCE y VOLUME nunca se tocan.
 *   · Epicenter: NO aplica en modo Audífonos (ese motor solo usa Intensity y su
 *     calibración ya está validada).
 *   · EQ: corrección propia por canción, SIN presets, con los realces limitados
 *     a +1.5 dB para no saturar. Los cortes pueden ser algo mayores porque no
 *     añaden energía (y son los que de verdad limpian una mezcla embarrada).
 */

export interface SpectrumProfile {
  /** Energía media por banda, en dB. */
  bands: number[];
  /** Bordes de banda en Hz; longitud = bands.length + 1. */
  edges: number[];
  rmsDb: number;
  crestDb: number;
  frames: number;
  ready: boolean;
}

export interface EpicenterAutoResult {
  intensity: number;
  sweepFreq: number;
  width: number;
  /** Para poder explicar la decisión en los registros. */
  reason: string;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

/** Centro geométrico de cada banda, en Hz. */
function bandCenters(edges: number[]): number[] {
  const out: number[] = [];
  for (let i = 0; i + 1 < edges.length; i++) {
    out.push(Math.sqrt(edges[i] * edges[i + 1]));
  }
  return out;
}

/**
 * Energía media (dB) del rango [loHz, hiHz), ponderando cada banda por cuánto
 * se solapa con el rango pedido.
 */
function averageRange(profile: SpectrumProfile, loHz: number, hiHz: number): number {
  const { bands, edges } = profile;
  let sum = 0;
  let weight = 0;
  for (let i = 0; i < bands.length; i++) {
    const lo = edges[i];
    const hi = edges[i + 1];
    const overlap = Math.min(hi, hiHz) - Math.max(lo, loHz);
    if (overlap <= 0) continue;
    const w = overlap / (hi - lo);
    sum += bands[i] * w;
    weight += w;
  }
  return weight > 0 ? sum / weight : -120;
}

/**
 * Epicenter automático. La intensidad sube cuanto MENOS subgrave real tenga la
 * canción (que es justo lo que el procesador reconstruye) y baja cuando la
 * mezcla ya viene cargada de graves, para no embarrarla.
 */
export function autoTuneEpicenter(profile: SpectrumProfile): EpicenterAutoResult {
  // Referencia: cuánto sub hay respecto al cuerpo de la mezcla.
  const sub = averageRange(profile, 20, 60);
  const bass = averageRange(profile, 60, 125);
  const mids = averageRange(profile, 250, 2000);

  // Déficit de sub respecto a los medios. Positivo = le falta grave profundo.
  const subDeficit = mids - sub;      // dB
  const bassBody = bass - mids;       // dB, cuánto pesa el bajo "audible"

  // 12 dB de déficit ya es una mezcla claramente sin sub; 0 es una con mucho.
  const deficitNorm = clamp((subDeficit - 4) / 14, 0, 1);
  // Si la canción ya trae mucho cuerpo grave, se modera para no saturar.
  const bodyPenalty = clamp((bassBody + 2) / 10, 0, 1) * 0.35;

  const intensity = Math.round(clamp(80 + (deficitNorm - bodyPenalty) * 20, 80, 100));

  // Sweep = dónde vive el fundamental del bajo. Mezclas con el grave más alto
  // piden un sweep más alto para que el sintetizador enganche bien.
  const bassCenterBias = clamp((bass - sub) / 12, 0, 1);   // 0 = sub dominante
  const sweepFreq = Math.round(clamp(34 + bassCenterBias * 22, 27, 63));

  // Width: más ancho cuando la mezcla es densa en medios (ayuda a despejar el
  // centro); más estrecho cuando ya es abierta.
  const density = clamp((mids - averageRange(profile, 4000, 16000) - 6) / 18, 0, 1);
  const width = Math.round(clamp(40 + density * 30, 0, 100));

  return {
    intensity,
    sweepFreq,
    width,
    reason: `sub=${sub.toFixed(1)}dB bass=${bass.toFixed(1)}dB mids=${mids.toFixed(1)}dB ` +
      `deficit=${subDeficit.toFixed(1)}dB -> intensity=${intensity} sweep=${sweepFreq} width=${width}`,
  };
}

/**
 * EQ automático por canción, sin presets.
 *
 * Método: se calcula la curva espectral de la canción, se compara contra una
 * referencia suave con inclinación natural (los graves pesan más que los agudos
 * en música real), y se corrige la DESVIACIÓN — no la curva absoluta. Así una
 * canción ya equilibrada recibe casi nada, y una embarrada o chillona recibe la
 * corrección donde de verdad se desvía.
 *
 * @param bandFreqs frecuencias centrales de las bandas del ecualizador (31)
 * @param maxBoostDb tope de realce (1.5 dB por defecto, para no saturar)
 */
export function autoTuneEq(
  profile: SpectrumProfile,
  bandFreqs: number[],
  maxBoostDb = 1.5,
  maxCutDb = 3,
): number[] {
  const centers = bandCenters(profile.edges);
  const measured = profile.bands;

  // 1) Nivel de referencia: la media de las bandas con contenido real. Se
  //    ignoran las que están en el ruido de fondo para que el silencio de una
  //    banda vacía no arrastre la media.
  const alive = measured.filter((db) => db > -100);
  const meanDb = alive.length
    ? alive.reduce((a, b) => a + b, 0) / alive.length
    : -60;

  // 2) Curva objetivo: inclinación natural. En música real la energía cae al
  //    subir en frecuencia; una curva plana sonaría delgada y chillona.
  //    -3 dB por octava desde 200 Hz, suavizado en los extremos.
  const targetAt = (hz: number): number => {
    const octaves = Math.log2(Math.max(hz, 20) / 200);
    let tilt = -3 * octaves;
    tilt = clamp(tilt, -14, 6);
    return meanDb + tilt;
  };

  // 3) Desviación por banda medida, y suavizado para no crear dientes de sierra.
  const deviation = measured.map((db, i) => {
    if (db <= -100) return 0;              // banda sin contenido: no tocar
    return db - targetAt(centers[i]);
  });
  const smooth = deviation.map((d, i) => {
    const prev = i > 0 ? deviation[i - 1] : d;
    const next = i + 1 < deviation.length ? deviation[i + 1] : d;
    return (prev + 2 * d + next) / 4;
  });

  // 4) Interpolar la corrección a las bandas del ecualizador y aplicarla con
  //    signo invertido (si sobra energía, se recorta).
  const gains = bandFreqs.map((hz) => {
    // Banda de medición más cercana en escala logarítmica.
    let lo = 0;
    while (lo + 1 < centers.length && centers[lo + 1] < hz) lo++;
    const hi = Math.min(lo + 1, centers.length - 1);
    const span = Math.log2(centers[hi] / centers[lo]) || 1;
    const t = clamp(Math.log2(hz / centers[lo]) / span, 0, 1);
    const dev = smooth[lo] * (1 - t) + smooth[hi] * t;

    // Corrección parcial (0.45): corregir el 100% suena artificial y elimina el
    // carácter de la mezcla. Buscamos acertar, no aplanar.
    return -dev * 0.45;
  });

  // 5) Quitar el desplazamiento global: lo que interesa es la FORMA de la curva,
  //    no subir o bajar el volumen general (de eso ya se encarga el preamp).
  const avg = gains.reduce((a, b) => a + b, 0) / (gains.length || 1);
  const centered = gains.map((g) => g - avg);

  // 6) Ajustar a los límites ESCALANDO toda la curva, no recortándola banda por
  //    banda. Recortar dejaba varias bandas contiguas pegadas al techo y borraba
  //    el matiz — justo lo que hace que una corrección suene genérica. Escalar
  //    conserva la forma relativa y respeta el tope de realce.
  const maxPos = Math.max(0, ...centered);
  const maxNeg = Math.max(0, ...centered.map((g) => -g));
  let scale = 1;
  if (maxPos > maxBoostDb) scale = Math.min(scale, maxBoostDb / maxPos);
  if (maxNeg > maxCutDb) scale = Math.min(scale, maxCutDb / maxNeg);

  return centered.map((g) => clamp(g * scale, -maxCutDb, maxBoostDb));
}

/**
 * Preamp sugerido: compensa el realce máximo aplicado para que la corrección
 * nunca empuje la salida a recorte.
 */
export function suggestPreampDb(gains: number[]): number {
  const maxBoost = Math.max(0, ...gains);
  return -Math.min(3, maxBoost);
}
