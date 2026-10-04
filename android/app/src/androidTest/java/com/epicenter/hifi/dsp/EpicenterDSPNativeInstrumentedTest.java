package com.epicenter.hifi.dsp;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class EpicenterDSPNativeInstrumentedTest {
    @Test
    public void runSelfTest_returnsTrue() {
        assertTrue(EpicenterDSPNative.runSelfTest());
    }

    @Test
    public void releaseCanOverlapProcessingAndControlCallsWithoutDeletingLivePointer() throws Exception {
        for (int round = 0; round < 16; round++) {
            EpicenterDSPNative dsp = new EpicenterDSPNative(192000, 2);
            long releaseCount = EpicenterDSPNative.getReleaseCount();
            CountDownLatch started = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread audio = new Thread(() -> {
                try {
                    float[] buffer = new float[8192];
                    started.countDown();
                    for (int i = 0; i < 100; i++) dsp.processFloatBuffer(buffer, 2, buffer.length);
                } catch (Throwable failure) { error.set(failure); }
            });
            audio.start();
            started.await();
            dsp.setEnabled(true);
            dsp.setHeadphonesMode(true);
            dsp.setParams(75f, 45f, 50f, 100f, 100f);
            dsp.release();
            dsp.release();
            dsp.reset();
            dsp.setParams(10f, 45f, 50f, 100f, 100f);
            audio.join(5000);
            assertTrue("Processing thread must finish", !audio.isAlive());
            assertNull(error.get());
            assertEquals(releaseCount + 1, EpicenterDSPNative.getReleaseCount());
        }
    }
}
