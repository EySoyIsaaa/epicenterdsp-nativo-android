package com.epicenter.hifi.nativeaudio;

import android.util.Log;

import com.epicenter.hifi.dsp.EpicenterDSPNative;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

/** Media3 PCM16 bridge to the native Epicenter engines. */
public class EpicenterAudioProcessor extends BaseAudioProcessor {
  private static final String TAG = "EpicenterAudioProcessor";
  private static final int PREALLOCATED_FLOAT_SAMPLES = 65536;
  private static final int CONTROL_BLOCK_MS = 10;

  private final SpectrumAnalyzer spectrumAnalyzer = new SpectrumAnalyzer();
  private volatile EpicenterDSPNative dspNative;
  private volatile boolean epicenterEnabled = false;
  private volatile boolean spectrumAnalysisEnabled = false;
  private volatile boolean headphonesMode = false;
  private float intensity = 100f;
  private float sweepFreq = 45f;
  private float width = 50f;
  private float balance = 100f;
  private float volume = 100f;

  private volatile int sampleRate = 44100;
  private volatile int channelCount = 2;
  private volatile int lastEncoding = C.ENCODING_INVALID;
  private float[] floatBuffer = new float[0];
  private volatile long processedBufferCount = 0;
  private volatile long bypassBufferCount = 0;
  private volatile String lastError = null;
  private volatile boolean onConfigureCalledOnce = false;
  private volatile boolean queueInputCalledOnce = false;
  private volatile boolean getOutputCalledOnce = false;

  public boolean wasOnConfigureCalled() { return onConfigureCalledOnce; }
  public boolean wasQueueInputCalled() { return queueInputCalledOnce; }
  public boolean wasGetOutputCalled() { return getOutputCalledOnce; }

  public synchronized void setEpicenterEnabled(boolean enabled) {
    epicenterEnabled = enabled;
    final EpicenterDSPNative dsp = dspNative;
    // Alternar solo publica el estado. Reiniciar aquí los seguidores de
    // envolvente y el oscilador hacía que el efecto tardara en recuperarse al
    // activarse, especialmente en modo Audífonos. El reset sigue reservado
    // para cambios de pista/formato, donde sí se necesita limpiar el historial.
    if (dsp != null) dsp.setEnabled(enabled);
    Log.i(TAG, "setEpicenterEnabled enabled=" + enabled);
  }

  public synchronized void setEpicenterParams(
      float intensity, float sweepFreq, float width, float balance, float volume) {
    this.intensity = intensity;
    this.sweepFreq = sweepFreq;
    this.width = width;
    this.balance = balance;
    this.volume = volume;
    final EpicenterDSPNative dsp = dspNative;
    if (dsp != null) dsp.setParams(intensity, sweepFreq, width, balance, volume);
  }

  public synchronized void setEpicenterMode(boolean headphones) {
    headphonesMode = headphones;
    final EpicenterDSPNative dsp = dspNative;
    if (dsp != null) {
      try { dsp.setHeadphonesMode(headphones); } catch (Throwable t) {
        Log.w(TAG, "setHeadphonesMode failed", t);
      }
    }
    Log.i(TAG, "setEpicenterMode headphones=" + headphones);
  }

  public boolean isHeadphonesMode() { return headphonesMode; }

  /** Requests a filter-state reset at the next native buffer boundary. */
  public void resetDsp() {
    spectrumAnalyzer.reset();
    final EpicenterDSPNative dsp = dspNative;
    if (dsp != null) {
      try { dsp.reset(); } catch (Throwable t) { Log.w(TAG, "resetDsp failed", t); }
    }
  }

  public SpectrumAnalyzer getSpectrumAnalyzer() { return spectrumAnalyzer; }

  /** Enables FFT work only for the short automatic-tuning measurement window. */
  public void setSpectrumAnalysisEnabled(boolean enabled) {
    if (enabled && !spectrumAnalysisEnabled) spectrumAnalyzer.reset();
    spectrumAnalysisEnabled = enabled;
  }

  public boolean isSpectrumAnalysisEnabled() { return spectrumAnalysisEnabled; }
  public int getSampleRate() { return sampleRate; }
  public int getChannelCount() { return channelCount; }
  public int getLastEncoding() { return lastEncoding; }
  public boolean isEpicenterEnabled() { return epicenterEnabled; }
  public long getProcessedBufferCount() { return processedBufferCount; }
  public long getBypassBufferCount() { return bypassBufferCount; }
  public String getLastError() { return lastError; }

  @Override
  public AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    Log.i(TAG, "onConfigure sr=" + inputAudioFormat.sampleRate
        + " ch=" + inputAudioFormat.channelCount
        + " encoding=" + inputAudioFormat.encoding);
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    sampleRate = inputAudioFormat.sampleRate;
    channelCount = inputAudioFormat.channelCount;
    lastEncoding = inputAudioFormat.encoding;
    spectrumAnalyzer.setSampleRate(sampleRate);
    spectrumAnalyzer.reset();
    onConfigureCalledOnce = true;
    // Reserve outside steady-state processing, including enough for unusually
    // large decoder buffers at 192 kHz.
    ensureFloatCapacity(PREALLOCATED_FLOAT_SAMPLES);
    reinitializeDsp();
    return inputAudioFormat;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    // A decoder can supply seconds of PCM in one buffer (especially WAV/FLAC).
    // Consume only 10 ms per call, so pending output never locks in an obsolete
    // intensity/bypass setting for the rest of that entire decoder buffer.
    final int bytesPerFrame = Math.max(1, channelCount) * 2;
    final int blockBytes = Math.max(1, sampleRate * CONTROL_BLOCK_MS / 1000) * bytesPerFrame;
    final int inputSize = Math.min(inputBuffer.remaining(), blockBytes);
    if (inputSize <= 0) return;

    if (!queueInputCalledOnce) {
      queueInputCalledOnce = true;
      Log.i(TAG, "queueInput first buffer bytes=" + inputSize
          + " sr=" + sampleRate + " ch=" + channelCount);
    }

    final ByteBuffer output = replaceOutputBuffer(inputSize);
    final EpicenterDSPNative dsp = dspNative;
    final boolean analyzeInput = spectrumAnalysisEnabled;

    if (!epicenterEnabled || dsp == null) {
      bypassBufferCount++;
      if (analyzeInput) {
        final int samples = inputSize / 2;
        ensureFloatCapacity(samples);
        final int start = inputBuffer.position();
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
          floatBuffer[i] = inputBuffer.getShort(start + i * 2) / 32768.0f;
        }
        spectrumAnalyzer.feed(floatBuffer, samples, channelCount);
      }
      // Absolute analysis reads leave the position untouched: bypass is bit exact.
      final int originalLimit = inputBuffer.limit();
      inputBuffer.limit(inputBuffer.position() + inputSize);
      output.put(inputBuffer);
      inputBuffer.limit(originalLimit);
      output.flip();
      return;
    }

    final int samples = inputSize / 2;
    ensureFloatCapacity(samples);
    inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      floatBuffer[i] = inputBuffer.getShort() / 32768.0f;
    }
    if (analyzeInput) spectrumAnalyzer.feed(floatBuffer, samples, channelCount);

    try {
      dsp.processFloatBuffer(floatBuffer, channelCount, samples);
      processedBufferCount++;
    } catch (Throwable throwable) {
      lastError = "process_error: " + throwable.getMessage();
      Log.e(TAG, "processFloatBuffer failed", throwable);
    }

    output.order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < samples; i++) {
      float sample = floatBuffer[i];
      if (sample > 1f) sample = 1f;
      else if (sample < -1f) sample = -1f;
      output.putShort((short) (sample * 32767f));
    }
    output.flip();
  }

  @Override
  public ByteBuffer getOutput() {
    final ByteBuffer output = super.getOutput();
    if (!getOutputCalledOnce && output.hasRemaining()) {
      getOutputCalledOnce = true;
      Log.i(TAG, "getOutput first output bytes=" + output.remaining());
    }
    return output;
  }

  @Override
  protected void onFlush() {
    spectrumAnalyzer.reset();
    final EpicenterDSPNative dsp = dspNative;
    if (dsp != null) dsp.reset();
  }

  @Override
  protected void onReset() {
    releaseDspNative();
    floatBuffer = new float[0];
    processedBufferCount = 0;
    bypassBufferCount = 0;
    lastEncoding = C.ENCODING_INVALID;
    lastError = null;
    spectrumAnalysisEnabled = false;
  }

  public synchronized void release() {
    Log.i(TAG, "release()");
    releaseDspNative();
  }

  private synchronized void releaseDspNative() {
    final EpicenterDSPNative dsp = dspNative;
    dspNative = null;
    if (dsp != null) {
      try { dsp.release(); } catch (Throwable throwable) {
        Log.w(TAG, "dspNative.release failed", throwable);
      }
    }
  }

  private synchronized void reinitializeDsp() {
    releaseDspNative();
    final EpicenterDSPNative next = new EpicenterDSPNative(sampleRate, channelCount);
    next.setEnabled(epicenterEnabled);
    next.setParams(intensity, sweepFreq, width, balance, volume);
    next.setHeadphonesMode(headphonesMode);
    dspNative = next;
    lastError = null;
    Log.i(TAG, "DSP reinit sr=" + sampleRate + " ch=" + channelCount
        + " enabled=" + epicenterEnabled + " headphones=" + headphonesMode);
  }

  private void ensureFloatCapacity(int samples) {
    if (floatBuffer.length < samples) floatBuffer = new float[samples];
  }
}
