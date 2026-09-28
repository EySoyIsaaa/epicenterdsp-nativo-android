package com.epicenter.hifi.nativeaudio;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 31-band, 1/3-octave PCM16 equalizer. */
@OptIn(markerClass = UnstableApi.class)
public class EqAudioProcessor extends BaseAudioProcessor {
  private static final String TAG = "EpicenterEqProc";

  public static final float[] EQ_FREQUENCIES = new float[] {
      20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f,
      315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f,
      3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
  };
  public static final int NUM_BANDS = 31;
  private static final float GAIN_CLAMP_DB = 24f;
  private static final float BAND_GAIN_CLAMP_DB = 8f;
  private static final double EQ_BANDWIDTH_OCTAVES = 1.0 / 3.0;

  /** Immutable control snapshot published once per UI change. */
  private static final class EqConfig {
    final float preampGain;
    final float headroomGain;
    final float[] a1, a2, b0, b1, b2;
    final int[] activeBands;

    EqConfig(float preampGain, float headroomGain, float[] a1, float[] a2,
        float[] b0, float[] b1, float[] b2, int[] activeBands) {
      this.preampGain = preampGain;
      this.headroomGain = headroomGain;
      this.a1 = a1;
      this.a2 = a2;
      this.b0 = b0;
      this.b1 = b1;
      this.b2 = b2;
      this.activeBands = activeBands;
    }
  }

  // Control state is guarded by this; queueInput reads only volatile snapshots.
  private volatile boolean enabled = false;
  private float preampDb = 0f;
  private final float[] bandsDb = new float[NUM_BANDS];
  private float headroomDb = 0f;
  private float headroomGain = 1f;
  private volatile EqConfig config;
  private final AtomicBoolean resetRequested = new AtomicBoolean(false);
  private final AtomicInteger bandResetMask = new AtomicInteger(0);

  // Filter state belongs exclusively to Media3's audio thread.
  private float[][] x1, x2, y1, y2;
  private int sampleRate = 0;
  private int channelCount = 0;
  private volatile int lastEncoding = C.ENCODING_INVALID;
  private volatile long processedBufferCount = 0;
  private volatile long bypassBufferCount = 0;
  private volatile String lastError = null;

  public EqAudioProcessor() {
    config = buildConfig();
  }

  public synchronized void setEnabled(boolean next) {
    if (enabled == next) return;
    enabled = next;
    resetRequested.set(true);
    Log.i(TAG, "setEnabled " + next);
  }

  public boolean isEnabled() { return enabled; }
  public synchronized float getPreampDb() { return preampDb; }
  public synchronized float getHeadroomDb() { return headroomDb; }
  public synchronized float[] getBandsDb() { return bandsDb.clone(); }
  public synchronized int getSampleRate() { return sampleRate; }
  public synchronized int getChannelCount() { return channelCount; }
  public long getProcessedBufferCount() { return processedBufferCount; }
  public long getBypassBufferCount() { return bypassBufferCount; }
  public String getLastError() { return lastError; }

  public synchronized void setPreampDb(float db) {
    preampDb = clamp(db, -GAIN_CLAMP_DB, GAIN_CLAMP_DB);
    config = buildConfig();
  }

  public synchronized void setBandGain(int index, float gainDb) {
    if (index < 0 || index >= NUM_BANDS) return;
    final boolean wasActive = isBandActive(index, bandsDb[index]);
    bandsDb[index] = clamp(gainDb, -BAND_GAIN_CLAMP_DB, BAND_GAIN_CLAMP_DB);
    recomputeHeadroom();
    config = buildConfig();
    if (wasActive != isBandActive(index, bandsDb[index])) requestBandReset(index);
  }

  public synchronized void setBandsGain(float[] gainsDb) {
    if (gainsDb == null) return;
    int changedTopology = 0;
    final int count = Math.min(NUM_BANDS, gainsDb.length);
    for (int i = 0; i < count; i++) {
      final boolean wasActive = isBandActive(i, bandsDb[i]);
      bandsDb[i] = clamp(gainsDb[i], -BAND_GAIN_CLAMP_DB, BAND_GAIN_CLAMP_DB);
      if (wasActive != isBandActive(i, bandsDb[i])) changedTopology |= (1 << i);
    }
    recomputeHeadroom();
    config = buildConfig();
    if (changedTopology != 0) {
      final int topologyMask = changedTopology;
      bandResetMask.getAndUpdate(mask -> mask | topologyMask);
    }
  }

  public synchronized void resetEq() {
    Arrays.fill(bandsDb, 0f);
    preampDb = 0f;
    recomputeHeadroom();
    config = buildConfig();
    resetRequested.set(true);
    Log.i(TAG, "resetEq");
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    synchronized (this) {
      sampleRate = inputAudioFormat.sampleRate;
      channelCount = inputAudioFormat.channelCount;
      lastEncoding = inputAudioFormat.encoding;
      allocateStateBuffers();
      config = buildConfig();
      lastError = null;
    }
    Log.i(TAG, "onConfigure sr=" + sampleRate + " ch=" + channelCount);
    return inputAudioFormat;
  }

  @Override
  public boolean isActive() { return true; }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    if (!inputBuffer.hasRemaining()) return;
    final int byteSize = inputBuffer.remaining();
    final ByteBuffer output = replaceOutputBuffer(byteSize);

    if (!enabled) {
      output.put(inputBuffer);
      output.flip();
      bypassBufferCount++;
      return;
    }

    applyPendingStateResets();
    final EqConfig current = config;
    if (current.activeBands.length == 0
        && Math.abs(current.preampGain * current.headroomGain - 1f) < 1e-6f) {
      output.put(inputBuffer);
      output.flip();
      bypassBufferCount++;
      return;
    }

    try {
      inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
      output.order(ByteOrder.LITTLE_ENDIAN);
      final int channels = Math.max(1, channelCount);
      final int frames = byteSize / 2 / channels;
      final float inputGain = current.preampGain;
      final float trim = current.headroomGain;

      for (int frame = 0; frame < frames; frame++) {
        for (int channel = 0; channel < channels; channel++) {
          float value = (inputBuffer.getShort() / 32768f) * inputGain;
          final float[] channelX1 = x1[channel];
          final float[] channelX2 = x2[channel];
          final float[] channelY1 = y1[channel];
          final float[] channelY2 = y2[channel];
          for (int band : current.activeBands) {
            final float input = value;
            float filtered = current.b0[band] * input
                + current.b1[band] * channelX1[band]
                + current.b2[band] * channelX2[band]
                - current.a1[band] * channelY1[band]
                - current.a2[band] * channelY2[band];
            if (!Float.isFinite(filtered) || Math.abs(filtered) < 1e-25f) filtered = 0f;
            channelX2[band] = channelX1[band];
            channelX1[band] = input;
            channelY2[band] = channelY1[band];
            channelY1[band] = filtered;
            value = filtered;
          }
          value *= trim;
          output.putShort((short) Math.round(clamp(value, -1f, 1f) * 32767f));
        }
      }
      output.flip();
      processedBufferCount++;
    } catch (Throwable throwable) {
      lastError = "eq_process_failed: " + throwable.getMessage();
      Log.e(TAG, "queueInput failed", throwable);
      output.flip();
    }
  }

  @Override
  protected void onFlush() { resetRequested.set(true); }

  @Override
  protected void onReset() {
    resetRequested.set(true);
    processedBufferCount = 0;
    bypassBufferCount = 0;
    lastEncoding = C.ENCODING_INVALID;
    lastError = null;
  }

  public void release() {
    Log.i(TAG, "release()");
    resetRequested.set(true);
  }

  private void allocateStateBuffers() {
    if (channelCount <= 0) return;
    x1 = new float[channelCount][NUM_BANDS];
    x2 = new float[channelCount][NUM_BANDS];
    y1 = new float[channelCount][NUM_BANDS];
    y2 = new float[channelCount][NUM_BANDS];
  }

  private void applyPendingStateResets() {
    if (resetRequested.getAndSet(false)) {
      clearAllState();
      bandResetMask.set(0);
      return;
    }
    final int mask = bandResetMask.getAndSet(0);
    if (mask == 0 || x1 == null) return;
    for (int channel = 0; channel < x1.length; channel++) {
      for (int band = 0; band < NUM_BANDS; band++) {
        if ((mask & (1 << band)) == 0) continue;
        x1[channel][band] = x2[channel][band] = 0f;
        y1[channel][band] = y2[channel][band] = 0f;
      }
    }
  }

  private void clearAllState() {
    if (x1 == null) return;
    for (int channel = 0; channel < x1.length; channel++) {
      Arrays.fill(x1[channel], 0f);
      Arrays.fill(x2[channel], 0f);
      Arrays.fill(y1[channel], 0f);
      Arrays.fill(y2[channel], 0f);
    }
  }

  private void requestBandReset(int index) {
    final int bit = 1 << index;
    bandResetMask.getAndUpdate(mask -> mask | bit);
  }

  private boolean isBandActive(int index, float gainDb) {
    return Math.abs(gainDb) > 1e-4f
        && sampleRate > 0
        && EQ_FREQUENCIES[index] < sampleRate * 0.49f;
  }

  private EqConfig buildConfig() {
    final float[] a1 = new float[NUM_BANDS];
    final float[] a2 = new float[NUM_BANDS];
    final float[] b0 = new float[NUM_BANDS];
    final float[] b1 = new float[NUM_BANDS];
    final float[] b2 = new float[NUM_BANDS];
    final int[] activeWork = new int[NUM_BANDS];
    int activeCount = 0;

    for (int band = 0; band < NUM_BANDS; band++) {
      b0[band] = 1f;
      if (!isBandActive(band, bandsDb[band])) continue;
      computeBand(band, bandsDb[band], a1, a2, b0, b1, b2);
      activeWork[activeCount++] = band;
    }
    final float preampGain = (float) Math.pow(10.0, preampDb / 20.0);
    return new EqConfig(preampGain, headroomGain, a1, a2, b0, b1, b2,
        Arrays.copyOf(activeWork, activeCount));
  }

  private void recomputeHeadroom() {
    float maxBoost = 0f, sum = 0f;
    int count = 0;
    for (float gain : bandsDb) {
      if (gain > 0f) {
        maxBoost = Math.max(maxBoost, gain);
        sum += gain;
        count++;
      }
    }
    if (count == 0) {
      headroomDb = 0f;
      headroomGain = 1f;
      return;
    }
    final float average = sum / count;
    final float density = (float) count / NUM_BANDS;
    headroomDb = Math.min(8f, maxBoost * 0.45f + average * density * 0.35f);
    headroomGain = (float) Math.pow(10.0, -headroomDb / 20.0);
  }

  private void computeBand(int index, float gainDb, float[] a1, float[] a2,
      float[] b0, float[] b1, float[] b2) {
    // Double intermediates avoid coefficient cancellation at 192 kHz.
    final double frequency = EQ_FREQUENCIES[index];
    final double amplitude = Math.pow(10.0, gainDb / 40.0);
    final double omega = 2.0 * Math.PI * frequency / Math.max(1, sampleRate);
    final double cosine = Math.cos(omega);
    final double sine = Math.sin(omega);
    final double sinhArg = (Math.log(2.0) / 2.0) * EQ_BANDWIDTH_OCTAVES
        * (omega / Math.max(1e-12, Math.abs(sine)));
    final double alpha = sine * Math.sinh(sinhArg);
    final double denominator = 1.0 + alpha / amplitude;
    b0[index] = (float) ((1.0 + alpha * amplitude) / denominator);
    b1[index] = (float) ((-2.0 * cosine) / denominator);
    b2[index] = (float) ((1.0 - alpha * amplitude) / denominator);
    a1[index] = (float) ((-2.0 * cosine) / denominator);
    a2[index] = (float) ((1.0 - alpha / amplitude) / denominator);
  }

  private static float clamp(float value, float min, float max) {
    return value < min ? min : (value > max ? max : value);
  }
}
