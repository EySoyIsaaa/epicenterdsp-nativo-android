#import "EpicenterDSPBridge.h"
#import "EpicenterDSPCore.hpp"
#import "EpicenterHeadphonesCore.hpp"

@implementation EpicenterDSPBridge {
    epicenter::EpicenterDSPCore _core;              // classic (car audio / subwoofer)
    epicenter::HeadphonesBassCore _headphones;      // headphones & small speakers
    BOOL _headphonesMode;
    BOOL _enabled;
}

- (void)prepareWithSampleRate:(double)sampleRate channelCount:(NSInteger)channelCount maxFrames:(NSInteger)maxFrames {
    _core.prepare(sampleRate, (int)channelCount, (std::size_t)MAX(128, maxFrames));
    _headphones.prepare(sampleRate, (int)channelCount);
}

- (void)reset {
    _core.reset();
    _headphones.reset();
}

- (void)setEnabled:(BOOL)enabled {
    _enabled = enabled;
    _core.setEnabled(enabled);
}

- (void)setHeadphonesMode:(BOOL)enabled {
    if (_headphonesMode == enabled) { return; }
    _headphonesMode = enabled;
    // Clear state of both engines so switching modes never carries over stale filter/phase data.
    _core.reset();
    _headphones.reset();
}

- (void)setIntensity:(float)intensity sweepFreq:(float)sweepFreq width:(float)width balance:(float)balance volume:(float)volume {
    _core.setParameters(intensity, sweepFreq, width, balance, volume);
    _headphones.setIntensity(intensity);   // headphones engine is driven by Intensity only
}

- (void)processLeft:(float *)left right:(nullable float *)right frameCount:(NSInteger)frameCount {
    if (left == nil || frameCount <= 0) { return; }
    float *channels[2] = { left, right != nil ? right : left };
    const int channelCount = right != nil ? 2 : 1;
    if (_headphonesMode) {
        // Classic core is bypassed entirely in headphones mode.
        if (_enabled) { _headphones.process(channels, channelCount, (std::size_t)frameCount); }
        return;
    }
    _core.process(channels, channelCount, (std::size_t)frameCount);
}

- (NSDictionary<NSString *, id> *)stateDictionary {
    const auto params = _core.parameters();
    return @{
        @"enabled": @(params.enabled),
        @"mode": _headphonesMode ? @"headphones" : @"car",
        @"intensity": @(params.intensity),
        @"sweepFreq": @(params.sweepFreq),
        @"width": @(params.width),
        @"balance": @(params.balance),
        @"volume": @(params.volume)
    };
}

- (NSDictionary<NSString *, id> *)calibrationDictionary {
    const auto calibration = _core.calibration();
    return @{
        @"subDepth": @(calibration.subDepth),
        @"deepExtensionAmount": @(calibration.deepExtensionAmount),
        @"synthDepthGain": @(calibration.synthDepthGain),
        @"gateDetectorFloor": @(calibration.gateDetectorFloor),
        @"gateDetectorAuthority": @(calibration.gateDetectorAuthority),
        @"outputDcHighpassHz": @(calibration.outputDcHighpassHz),
        @"deepExtensionSubsonicHighpassHz": @(calibration.deepExtensionSubsonicHighpassHz),
        @"deepExtensionMixBase": @(calibration.deepExtensionMixBase),
        @"deepExtensionMixVoice": @(calibration.deepExtensionMixVoice)
    };
}

@end
