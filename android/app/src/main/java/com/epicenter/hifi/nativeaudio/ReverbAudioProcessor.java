package com.epicenter.hifi.nativeaudio;

import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Two serial Freeverb stages approximating the iOS room and hall effects. */
@OptIn(markerClass = UnstableApi.class)
public class ReverbAudioProcessor extends BaseAudioProcessor {
  private static final String TAG = "EpicenterReverb";
  private static final int[] COMB_L = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
  private static final int[] COMB_R = {1139, 1211, 1300, 1379, 1445, 1514, 1580, 1640};
  private static final int[] ALLPASS_L = {225, 341, 441, 556};
  private static final int[] ALLPASS_R = {341, 461, 587, 721};
  private static final float FIXED_GAIN = 0.015f;
  private static final float SCALE_WET = 3.0f;
  private static final float ALLPASS_COEFF = 0.5f;
  private static final float REVERB_WET_MAX = 0.55f;
  private static final float HALL_WET_MAX = 0.45f;
  private static final float ROOM_SIZE_REVERB = 0.50f;
  private static final float DAMPING_REVERB = 0.50f;
  private static final float ROOM_SIZE_HALL = 0.85f;
  private static final float DAMPING_HALL = 0.25f;

  private volatile boolean reverbEnabled = false;
  private volatile boolean concertHallEnabled = false;
  private volatile float reverbWet = 0.35f;
  private volatile float concertHallWet = 0.45f;
  private volatile float outputHeadroomDb = 0f;
  private volatile float outputTrimGain = 1f;

  private final FreeverbStage reverbStage = new FreeverbStage();
  private final FreeverbStage hallStage = new FreeverbStage();
  private final float[] wetScratch = new float[2];
  private int sampleRate = 44100;
  private int channelCount = 2;
  private volatile long processedBufferCount = 0;
  private volatile long bypassBufferCount = 0;

  public void setReverbEnabled(boolean enabled) {
    reverbEnabled = enabled;
    Log.i(TAG, "setReverbEnabled=" + enabled);
  }

  public void setConcertHallEnabled(boolean enabled) {
    concertHallEnabled = enabled;
    Log.i(TAG, "setConcertHallEnabled=" + enabled);
  }

  public void setReverbAmount(float amount) {
    reverbWet = clamp(amount, 0f, 100f) / 100f;
  }

  public void setConcertHallAmount(float amount) {
    concertHallWet = clamp(amount, 0f, 100f) / 100f;
  }

  public void setOutputHeadroomDb(float db) {
    outputHeadroomDb = clamp(db, 0f, 12f);
    outputTrimGain = outputHeadroomDb <= 0.001f
        ? 1f : (float) Math.pow(10.0, -outputHeadroomDb / 20.0);
  }

  public boolean isReverbEnabled() { return reverbEnabled; }
  public boolean isConcertHallEnabled() { return concertHallEnabled; }
  public float getReverbAmount() { return reverbWet * 100f; }
  public float getConcertHallAmount() { return concertHallWet * 100f; }
  public float getOutputHeadroomDb() { return outputHeadroomDb; }
  public long getProcessedBufferCount() { return processedBufferCount; }
  public long getBypassBufferCount() { return bypassBufferCount; }

  @Override
  public AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    sampleRate = inputAudioFormat.sampleRate;
    channelCount = inputAudioFormat.channelCount;
    reverbStage.allocate(sampleRate);
    hallStage.allocate(sampleRate);
    Log.i(TAG, "onConfigure sr=" + sampleRate + " ch=" + channelCount);
    return inputAudioFormat;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    final int inputSize = inputBuffer.remaining();
    if (inputSize <= 0) return;

    final boolean doReverb = reverbEnabled;
    final boolean doHall = concertHallEnabled;
    final float wetR = reverbWet * REVERB_WET_MAX;
    final float wetH = concertHallWet * HALL_WET_MAX;
    final float trimGain = outputTrimGain;

    final ByteBuffer output = replaceOutputBuffer(inputSize);
    if (!doReverb && !doHall && trimGain >= 0.9999f) {
      output.put(inputBuffer);
      output.flip();
      bypassBufferCount++;
      return;
    }

    inputBuffer.order(ByteOrder.LITTLE_ENDIAN);
    output.order(ByteOrder.LITTLE_ENDIAN);
    final int channels = Math.max(1, channelCount);
    final int frames = inputSize / 2 / channels;

    for (int frame = 0; frame < frames; frame++) {
      float left = inputBuffer.getShort() / 32768.0f;
      float right = channels >= 2 ? inputBuffer.getShort() / 32768.0f : left;

      if (doReverb) {
        reverbStage.processWet(left, right, ROOM_SIZE_REVERB, DAMPING_REVERB, wetScratch);
        final float dry = 1f - wetR;
        left = left * dry + wetScratch[0] * wetR;
        right = right * dry + wetScratch[1] * wetR;
      }
      if (doHall) {
        hallStage.processWet(left, right, ROOM_SIZE_HALL, DAMPING_HALL, wetScratch);
        final float dry = 1f - wetH;
        left = left * dry + wetScratch[0] * wetH;
        right = right * dry + wetScratch[1] * wetH;
      }

      output.putShort((short) (clamp(left * trimGain, -1f, 1f) * 32767f));
      if (channels >= 2) {
        output.putShort((short) (clamp(right * trimGain, -1f, 1f) * 32767f));
      }
      // The bass/room chain is stereo. Preserve extra surround channels.
      for (int channel = 2; channel < channels; channel++) {
        output.putShort(inputBuffer.getShort());
      }
    }
    output.flip();
    processedBufferCount++;
  }

  @Override
  protected void onFlush() {
    reverbStage.clear();
    hallStage.clear();
  }

  @Override
  protected void onReset() {
    reverbStage.clear();
    hallStage.clear();
    processedBufferCount = 0;
    bypassBufferCount = 0;
  }

  public void release() {
    reverbStage.clear();
    hallStage.clear();
  }

  private static float clamp(float value, float min, float max) {
    return value < min ? min : (value > max ? max : value);
  }

  private static final class FreeverbStage {
    private float[][] combL, combR;
    private int[] combIdxL, combIdxR;
    private float[] storeL, storeR;
    private float[][] apL, apR;
    private int[] apIdxL, apIdxR;

    void allocate(int sampleRate) {
      final float scale = (float) sampleRate / 44100f;
      combL = new float[COMB_L.length][];
      combR = new float[COMB_R.length][];
      combIdxL = new int[COMB_L.length];
      combIdxR = new int[COMB_R.length];
      storeL = new float[COMB_L.length];
      storeR = new float[COMB_R.length];
      for (int i = 0; i < COMB_L.length; i++) {
        combL[i] = new float[Math.max(1, Math.round(COMB_L[i] * scale))];
        combR[i] = new float[Math.max(1, Math.round(COMB_R[i] * scale))];
      }
      apL = new float[ALLPASS_L.length][];
      apR = new float[ALLPASS_R.length][];
      apIdxL = new int[ALLPASS_L.length];
      apIdxR = new int[ALLPASS_R.length];
      for (int i = 0; i < ALLPASS_L.length; i++) {
        apL[i] = new float[Math.max(1, Math.round(ALLPASS_L[i] * scale))];
        apR[i] = new float[Math.max(1, Math.round(ALLPASS_R[i] * scale))];
      }
    }

    void clear() {
      if (combL == null) return;
      for (float[] buffer : combL) Arrays.fill(buffer, 0f);
      for (float[] buffer : combR) Arrays.fill(buffer, 0f);
      for (float[] buffer : apL) Arrays.fill(buffer, 0f);
      for (float[] buffer : apR) Arrays.fill(buffer, 0f);
      Arrays.fill(combIdxL, 0);
      Arrays.fill(combIdxR, 0);
      Arrays.fill(apIdxL, 0);
      Arrays.fill(apIdxR, 0);
      Arrays.fill(storeL, 0f);
      Arrays.fill(storeR, 0f);
    }

    void processWet(float inL, float inR, float roomSize, float damping, float[] out) {
      final float monoInput = (inL + inR) * FIXED_GAIN;
      float outL = 0f, outR = 0f;

      for (int i = 0; i < combL.length; i++) {
        final float delayedL = combL[i][combIdxL[i]];
        storeL[i] = delayedL * (1f - damping) + storeL[i] * damping;
        combL[i][combIdxL[i]] = monoInput + storeL[i] * roomSize;
        if (++combIdxL[i] == combL[i].length) combIdxL[i] = 0;
        outL += delayedL;

        final float delayedR = combR[i][combIdxR[i]];
        storeR[i] = delayedR * (1f - damping) + storeR[i] * damping;
        combR[i][combIdxR[i]] = monoInput + storeR[i] * roomSize;
        if (++combIdxR[i] == combR[i].length) combIdxR[i] = 0;
        outR += delayedR;
      }

      for (int i = 0; i < apL.length; i++) {
        final float delayedL = apL[i][apIdxL[i]];
        apL[i][apIdxL[i]] = outL + delayedL * ALLPASS_COEFF;
        outL = delayedL - outL;
        if (++apIdxL[i] == apL[i].length) apIdxL[i] = 0;

        final float delayedR = apR[i][apIdxR[i]];
        apR[i][apIdxR[i]] = outR + delayedR * ALLPASS_COEFF;
        outR = delayedR - outR;
        if (++apIdxR[i] == apR[i].length) apIdxR[i] = 0;
      }
      out[0] = outL * SCALE_WET;
      out[1] = outR * SCALE_WET;
    }
  }
}
