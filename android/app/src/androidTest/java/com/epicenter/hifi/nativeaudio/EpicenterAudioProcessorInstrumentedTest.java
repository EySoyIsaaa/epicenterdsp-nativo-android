package com.epicenter.hifi.nativeaudio;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.epicenter.hifi.dsp.EpicenterDSPNative;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
public class EpicenterAudioProcessorInstrumentedTest {
    @Test
    public void controlsApplyToNext10msBlockWithoutReinitializingOrDroppingInput() throws Exception {
        for (int rate : new int[] {44100, 192000}) {
            EpicenterAudioProcessor processor = new EpicenterAudioProcessor();
            try {
                processor.configure(new AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT));
                processor.flush();
                long initializations = EpicenterDSPNative.getInitCount();
                int blockBytes = rate / 100 * 4;
                ByteBuffer input = ByteBuffer.allocateDirect(rate * 4 * 3).order(ByteOrder.LITTLE_ENDIAN);
                for (int frame = 0; frame < rate * 3; frame++) {
                    short sample = (short) (4000 * Math.sin(2 * Math.PI * 55 * frame / rate));
                    input.putShort(sample).putShort(sample);
                }
                input.flip();
                int originalLimit = input.limit();
                processor.queueInput(input);
                assertEquals("Do not preprocess seconds of audio ahead of user controls", blockBytes, input.position());
                assertEquals(originalLimit, input.limit());
                assertBypassEqualsInput(input, 0, processor.getOutput(), blockBytes);

                for (int mode = 0; mode < 2; mode++) {
                    processor.setEpicenterMode(mode == 0);
                    processor.setEpicenterEnabled(true);
                    for (float intensity : new float[] {0f, 100f, 25f, 80f}) {
                        processor.setEpicenterParams(intensity, 45f, 50f, 100f, 100f);
                        int before = input.position();
                        long processed = processor.getProcessedBufferCount();
                        processor.queueInput(input);
                        ByteBuffer output = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN);
                        assertEquals(blockBytes, input.position() - before);
                        assertEquals(processed + 1, processor.getProcessedBufferCount());
                        assertEquals(blockBytes, output.remaining());
                        boolean changed = false;
                        for (int byteOffset = 0; byteOffset < blockBytes; byteOffset += 2) {
                            if (output.getShort(byteOffset) != input.getShort(before + byteOffset)) changed = true;
                        }
                        assertTrue("Enabled engine should change the next block", changed);
                    }
                    processor.setEpicenterEnabled(false);
                    int before = input.position();
                    processor.queueInput(input);
                    assertBypassEqualsInput(input, before, processor.getOutput(), blockBytes);
                }
                assertEquals("Controls must keep the engine alive", initializations, EpicenterDSPNative.getInitCount());
                assertNull(processor.getLastError());
            } finally { processor.reset(); }
        }
    }

    private static void assertBypassEqualsInput(ByteBuffer input, int start, ByteBuffer output, int bytes) {
        assertEquals(bytes, output.remaining());
        for (int i = 0; i < bytes; i++) assertEquals(input.get(start + i), output.get(i));
    }
}
