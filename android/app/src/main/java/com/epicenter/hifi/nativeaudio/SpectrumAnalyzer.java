package com.epicenter.hifi.nativeaudio;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Analizador del PCM de entrada usado por el ajuste automÃ¡tico.
 *
 * El estado de cÃ¡lculo pertenece exclusivamente al hilo de audio. Los getters
 * leen snapshots publicados y nunca toman un lock que pueda bloquearlo.
 */
public class SpectrumAnalyzer {

  private static final int FFT_SIZE = 2048;
  private static final long ANALYSIS_INTERVAL_MS = 350;
  private static final float TARGET_ANALYSIS_RATE = 48000f;
  private static final float ANALYSIS_LOWPASS_HZ = 18000f;

  private static final float[] BAND_EDGES = {
      20, 40, 60, 90, 125, 180, 250, 400, 800, 2000, 4000, 8000, 16000
  };
  public static final int BAND_COUNT = BAND_EDGES.length - 1;

  private final float[] window = new float[FFT_SIZE];
  private final float[] re = new float[FFT_SIZE];
  private final float[] im = new float[FFT_SIZE];
  private final float[] acc = new float[FFT_SIZE];
  private final double[] bandSum = new double[BAND_COUNT];
  private final double[] energy = new double[BAND_COUNT];
  private final int[] binCount = new int[BAND_COUNT];
  private final float[][] publishedBands = {
      new float[BAND_COUNT], new float[BAND_COUNT]
  };

  private final AtomicBoolean resetRequested = new AtomicBoolean(true);
  private volatile int requestedSampleRate = 44100;
  private volatile int publishedBandIndex = -1;
  private volatile int publishedFrames = 0;
  private volatile float publishedPeak = 0f;
  private volatile float publishedRmsDb = -120f;
  private volatile float publishedCrestDb = 0f;

  // Audio-thread-owned working state.
  private int sampleRate = 44100;
  private float analysisSampleRate = 44100f;
  private int decimationFactor = 1;
  private float antiAliasAlpha = 1f;
  private float lowpassState = 0f;
  private float decimationSum = 0f;
  private int decimationCount = 0;
  private int frames = 0;
  private int fill = 0;
  private long lastAnalysisMs = 0;
  private double peak = 0.0;
  private double rmsSum = 0.0;
  private long rmsCount = 0;

  public SpectrumAnalyzer() {
    for (int i = 0; i < FFT_SIZE; i++) {
      window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
    }
  }

  public void setSampleRate(int sr) {
    if (sr > 0 && requestedSampleRate != sr) {
      requestedSampleRate = sr;
      reset();
    }
  }

  /** Publica un perfil vacÃ­o; el estado interno se limpia en el siguiente buffer. */
  public void reset() {
    publishedBandIndex = -1;
    publishedFrames = 0;
    publishedPeak = 0f;
    publishedRmsDb = -120f;
    publishedCrestDb = 0f;
    resetRequested.set(true);
  }

  /** Alimenta muestras intercaladas sin bloquear ni modificar el audio. */
  public void feed(float[] interleaved, int sampleCount, int channels) {
    if (interleaved == null || sampleCount <= 0 || channels <= 0) return;
    applyPendingConfiguration();

    final int frameCount = sampleCount / channels;
    if (frameCount <= 0) return;

    for (int i = 0; i < frameCount; i++) {
      float mono = 0f;
      final int base = i * channels;
      for (int c = 0; c < channels && base + c < sampleCount; c++) {
        mono += interleaved[base + c];
      }
      mono /= channels;

      final double absolute = Math.abs(mono);
      if (absolute > peak) peak = absolute;
      rmsSum += (double) mono * mono;
      rmsCount++;

      // A 96/192 kHz analizamos cerca de 48 kHz. Esto mantiene resoluciÃ³n de
      // graves (23.4 Hz/bin) y evita cuadruplicar el coste de la FFT.
      lowpassState += antiAliasAlpha * (mono - lowpassState);
      decimationSum += lowpassState;
      decimationCount++;
      if (decimationCount >= decimationFactor) {
        if (fill < FFT_SIZE) acc[fill++] = decimationSum / decimationCount;
        decimationSum = 0f;
        decimationCount = 0;
      }
    }

    publishLevels();
    final long now = System.currentTimeMillis();
    if (fill >= FFT_SIZE) {
      if ((now - lastAnalysisMs) >= ANALYSIS_INTERVAL_MS) {
        lastAnalysisMs = now;
        analyze();
      }
      fill = 0;
    }
  }

  private void applyPendingConfiguration() {
    final int nextRate = requestedSampleRate;
    if (!resetRequested.getAndSet(false) && sampleRate == nextRate) return;

    sampleRate = Math.max(1, nextRate);
    decimationFactor = Math.max(1, Math.round(sampleRate / TARGET_ANALYSIS_RATE));
    analysisSampleRate = (float) sampleRate / decimationFactor;
    antiAliasAlpha = decimationFactor == 1
        ? 1f
        : (float) (1.0 - Math.exp(-2.0 * Math.PI * ANALYSIS_LOWPASS_HZ / sampleRate));
    Arrays.fill(bandSum, 0.0);
    frames = 0;
    fill = 0;
    lastAnalysisMs = 0;
    peak = 0.0;
    rmsSum = 0.0;
    rmsCount = 0;
    lowpassState = 0f;
    decimationSum = 0f;
    decimationCount = 0;
  }

  private void analyze() {
    for (int i = 0; i < FFT_SIZE; i++) {
      re[i] = acc[i] * window[i];
      im[i] = 0f;
    }
    fft(re, im);

    Arrays.fill(energy, 0.0);
    Arrays.fill(binCount, 0);
    final double binHz = analysisSampleRate / FFT_SIZE;
    for (int bin = 1; bin < FFT_SIZE / 2; bin++) {
      final double hz = bin * binHz;
      if (hz < BAND_EDGES[0] || hz >= BAND_EDGES[BAND_COUNT]) continue;
      for (int band = 0; band < BAND_COUNT; band++) {
        if (hz >= BAND_EDGES[band] && hz < BAND_EDGES[band + 1]) {
          final double magnitudeSquared =
              (double) re[bin] * re[bin] + (double) im[bin] * im[bin];
          energy[band] += magnitudeSquared;
          binCount[band]++;
          break;
        }
      }
    }

    for (int band = 0; band < BAND_COUNT; band++) {
      if (binCount[band] > 0) {
        bandSum[band] += Math.sqrt(energy[band] / binCount[band]);
      }
    }
    frames++;

    final int nextIndex = publishedBandIndex == 0 ? 1 : 0;
    final float[] snapshot = publishedBands[nextIndex];
    for (int band = 0; band < BAND_COUNT; band++) {
      snapshot[band] = (float) (20.0 * Math.log10(bandSum[band] / frames + 1e-12));
    }
    publishedFrames = frames;
    publishedBandIndex = nextIndex; // volatile publish must be last
  }

  private void publishLevels() {
    publishedPeak = (float) peak;
    if (rmsCount == 0) {
      publishedRmsDb = -120f;
      publishedCrestDb = 0f;
      return;
    }
    final double rms = Math.sqrt(rmsSum / rmsCount);
    publishedRmsDb = (float) (20.0 * Math.log10(rms + 1e-12));
    publishedCrestDb = (float) (20.0 * Math.log10((peak + 1e-12) / (rms + 1e-12)));
  }

  public float[] getBandsDb() {
    final int index = publishedBandIndex;
    return index < 0 ? null : publishedBands[index].clone();
  }

  public int getFrameCount() { return publishedFrames; }
  public float getPeak() { return publishedPeak; }
  public float getRmsDb() { return publishedRmsDb; }
  public float getCrestDb() { return publishedCrestDb; }
  public static float[] bandEdges() { return BAND_EDGES.clone(); }

  private static void fft(float[] re, float[] im) {
    final int n = re.length;
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >> 1;
      for (; (j & bit) != 0; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        float t = re[i]; re[i] = re[j]; re[j] = t;
        t = im[i]; im[i] = im[j]; im[j] = t;
      }
    }
    for (int len = 2; len <= n; len <<= 1) {
      final double angle = -2.0 * Math.PI / len;
      final float wr = (float) Math.cos(angle), wi = (float) Math.sin(angle);
      for (int i = 0; i < n; i += len) {
        float cr = 1f, ci = 0f;
        for (int k = 0; k < len / 2; k++) {
          final int a = i + k, b = i + k + len / 2;
          final float xr = re[b] * cr - im[b] * ci;
          final float xi = re[b] * ci + im[b] * cr;
          re[b] = re[a] - xr; im[b] = im[a] - xi;
          re[a] += xr; im[a] += xi;
          final float nextCr = cr * wr - ci * wi;
          ci = cr * wi + ci * wr;
          cr = nextCr;
        }
      }
    }
  }
}
