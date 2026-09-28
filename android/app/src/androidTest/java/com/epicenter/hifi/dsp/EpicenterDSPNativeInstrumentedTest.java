package com.epicenter.hifi.dsp;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EpicenterDSPNativeInstrumentedTest {
    @Test
    public void runSelfTest_returnsTrue() {
        assertTrue(EpicenterDSPNative.runSelfTest());
    }
}
