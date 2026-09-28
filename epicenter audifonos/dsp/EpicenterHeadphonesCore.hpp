#pragma once
//
// EpicenterHeadphonesCore — "Modo Audifonos" bass engine.
//
// The classic Epicenter (EpicenterDSPCore) restores deep sub energy that only a subwoofer
// can reproduce: spectacular in a car, odd on headphones / small speakers. This engine is
// tuned to translate on small drivers instead:
//   * clean SUBSONIC sine locked to half the bass fundamental (no rectified buzz)
//   * synthesized only for genuinely DEEP bass (fades out on higher notes -> no warble)
//   * frequency glide (removes per-cycle jitter)
//   * scoop 80-250 Hz (removes mud/boom)
//   * hard sub compression (density = perceived depth)
//   * bass summed to MONO (tight, powerful)
//
// The DSP math here is exactly the configuration validated in the desktop lab.
//
#include "EpicenterDSPCore.hpp"
#include <algorithm>
#include <cmath>

namespace epicenter {

// RBJ peaking EQ (the core only provides LP/HP/BP).
class PeakFilter {
public:
    void set(double sr, float freq, float gainDb, float q) {
        const float A = std::pow(10.0f, gainDb / 40.0f);
        const float w0 = 6.2831853f * freq / float(sr);
        const float c = std::cos(w0), s = std::sin(w0), alpha = s / (2.0f * q);
        const float a0 = 1 + alpha / A;
        b0_ = (1 + alpha * A) / a0; b1_ = (-2 * c) / a0; b2_ = (1 - alpha * A) / a0;
        a1_ = (-2 * c) / a0; a2_ = (1 - alpha / A) / a0;
    }
    float process(float x) {
        const float y = b0_ * x + b1_ * x1_ + b2_ * x2_ - a1_ * y1_ - a2_ * y2_;
        x2_ = x1_; x1_ = x; y2_ = y1_; y1_ = y; return y;
    }
    void reset() { x1_ = x2_ = y1_ = y2_ = 0.0f; }
private:
    float b0_=1,b1_=0,b2_=0,a1_=0,a2_=0,x1_=0,x2_=0,y1_=0,y2_=0;
};

struct HeadphonesBassParams {           // defaults == the approved "fuerte" preset
    float subGenHz        = 78.0f;
    float deepHz          = 46.0f;
    float subGen          = 2.4f;
    float subBoostDb      = 18.0f;
    float scoopDb         = 10.0f;
    float subDepth        = 0.9f;
    float monoHz          = 105.0f;
    float fundFadeStartHz = 90.0f;      // full synthesis below this bass fundamental
    float fundFadeEndHz   = 125.0f;     // no synthesis above this
};

class HeadphonesBassCore {
public:
    void prepare(double sr, int channelCount) {
        sr_ = sr > 1.0 ? sr : 44100.0;
        ch_ = std::max(1, std::min(channelCount, 2));
        apply();
        bassLP_.prepare(sr_, BiquadFilter::Type::Lowpass, p_.subGenHz, 0.707f);
        deepLP_.prepare(sr_, BiquadFilter::Type::Lowpass, p_.deepHz, 0.707f);
        rawSubLP_.prepare(sr_, BiquadFilter::Type::Lowpass, 62.0f, 0.707f);
        subDC_.prepare(sr_, BiquadFilter::Type::Highpass, 18.0f, 0.707f);
        subEnv_.prepare(sr_, 8.0f, 140.0f);
        bassAmpEnv_.prepare(sr_, 15.0f, 120.0f);
        period_ = float(sr_ / 55.0);
        smoothFreq_ = 0.5f * float(sr_) / period_;
    }

    void reset() {
        for (int c = 0; c < 2; ++c) { scoopA_[c].reset(); scoopB_[c].reset(); hp_[c].reset(); }
        bassLP_.reset(); deepLP_.reset(); rawSubLP_.reset(); subDC_.reset();
        subEnv_.reset(); bassAmpEnv_.reset();
        lastBass_ = 0.0f; phase_ = 0.0f; bassCount_ = 0.0f;
        period_ = float(sr_ / 55.0);
        smoothFreq_ = 0.5f * float(sr_) / period_;
    }

    /// Intensity knob (0..100). 100 == the validated "fuerte" preset.
    void setIntensity(float intensity) {
        const float n = std::max(0.0f, std::min(intensity / 100.0f, 1.0f));
        p_.subBoostDb = 6.0f + n * 12.0f;   // 6 .. 18 dB
        p_.subGen     = 0.8f + n * 1.6f;    // 0.8 .. 2.4
        p_.subDepth   = 0.5f + n * 0.4f;    // 0.5 .. 0.9
        p_.scoopDb    = 4.0f + n * 6.0f;    // 4 .. 10 dB
        apply();
    }

    HeadphonesBassParams parameters() const { return p_; }

    void process(float* const* channels, int channelCount, std::size_t frameCount) {
        if (!channels || channelCount <= 0) return;
        const int ch = std::min(channelCount, 2);
        const float subBoost = std::pow(10.0f, p_.subBoostDb / 20.0f);
        const float d = std::max(0.0f, std::min(p_.subDepth, 1.0f));
        const float thrDb = -10.0f - d * 22.0f, ratio = 1.0f + d * 16.0f, makeupDb = d * 7.0f;

        for (std::size_t i = 0; i < frameCount; ++i) {
            const float L = channels[0][i];
            const float R = ch > 1 ? channels[1][i] : L;
            const float mono = 0.5f * (L + R);

            // Clean subsonic: pure SINE locked to half the bass fundamental, amplitude
            // modulated by a smooth envelope (no rectification, no hard sign flips).
            const float bass = bassLP_.process(mono);
            bassCount_ += 1.0f;
            if (lastBass_ <= 0.0f && bass > 0.0f) {              // rising zero-crossing = one period
                const float lo = float(sr_) / 180.0f, hi = float(sr_) / 28.0f;
                if (bassCount_ > lo && bassCount_ < hi) period_ = period_ * 0.82f + bassCount_ * 0.18f;
                bassCount_ = 0.0f;
            }
            lastBass_ = bass;
            const float targetFreq = 0.5f * float(sr_) / std::max(period_, 1.0f);   // octave below
            smoothFreq_ += (targetFreq - smoothFreq_) * 0.001f;  // glide out per-cycle jitter
            phase_ += 6.2831853f * smoothFreq_ / float(sr_);
            if (phase_ > 6.2831853f) phase_ -= 6.2831853f;
            const float amp = bassAmpEnv_.process(std::fabs(bass));
            // Synthesize only for deep bass; fade out on higher notes (avoids warble).
            const float fund = 2.0f * smoothFreq_;
            float wf = 1.0f;
            if (fund > p_.fundFadeStartHz) {
                const float span = std::max(1.0f, p_.fundFadeEndHz - p_.fundFadeStartHz);
                wf = std::max(0.0f, std::min(1.0f, (p_.fundFadeEndHz - fund) / span));
            }
            const float genSub = std::sin(phase_) * amp * 2.2f * wf;
            const float rawSub = rawSubLP_.process(mono);
            float sub = subDC_.process(rawSub + genSub * p_.subGen);

            const float env = subEnv_.process(std::fabs(sub));
            const float envDb = 20.0f * std::log10(env + 1e-9f);
            const float over = envDb - thrDb;
            const float grDb = over > 0.0f ? -over * (1.0f - 1.0f / ratio) : 0.0f;
            sub = std::tanh(sub * std::pow(10.0f, (grDb + makeupDb) / 20.0f) * subBoost);

            for (int c = 0; c < ch; ++c) {
                float s = channels[c][i];
                s = scoopA_[c].process(s);        // cut ~110 Hz
                s = scoopB_[c].process(s);        // cut ~200 Hz
                s = hp_[c].process(s);            // remove lows -> bass becomes mono
                float out = s + sub;              // mono sub added to both channels
                channels[c][i] = std::max(-1.0f, std::min(1.0f, out));
            }
        }
    }

private:
    void apply() {
        for (int c = 0; c < 2; ++c) {
            scoopA_[c].set(sr_, 110.0f, -p_.scoopDb, 0.9f);
            scoopB_[c].set(sr_, 200.0f, -p_.scoopDb * 0.45f, 1.0f);
            hp_[c].prepare(sr_, BiquadFilter::Type::Highpass, p_.monoHz, 0.707f);
        }
    }

    double sr_ = 44100.0;
    int ch_ = 2;
    HeadphonesBassParams p_;
    PeakFilter scoopA_[2], scoopB_[2];
    BiquadFilter hp_[2];
    BiquadFilter bassLP_, deepLP_, rawSubLP_, subDC_;
    EnvelopeFollower subEnv_, bassAmpEnv_;
    float lastBass_ = 0.0f, period_ = 800.0f, phase_ = 0.0f, bassCount_ = 0.0f, smoothFreq_ = 27.0f;
};

} // namespace epicenter
