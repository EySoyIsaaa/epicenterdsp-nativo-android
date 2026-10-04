package com.epicenter.hifi.nativeaudio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlaybackException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

@RunWith(AndroidJUnit4.class)
public class NativePlaybackQueueStressInstrumentedTest {
    @Test
    public void mixedWaveQueueRecoversReordersAndActuallyPlaysAll112Files() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File stressDirectory = new File(context.getCacheDir(), "epicenter-playback-stress-test");
        if (!stressDirectory.exists()) assertTrue(stressDirectory.mkdirs());
        List<NativeAudioTrack> libraryTracks = createWaveTracks(stressDirectory, 112);

        Set<String> validIds = new HashSet<>();
        for (NativeAudioTrack track : libraryTracks) validIds.add(track.id);

        NativeAudioTrack badTrack = new NativeAudioTrack(
            "invalid-playback-stress-item", "Unavailable test item", "", "", 0L,
            Uri.fromFile(new File(stressDirectory, "missing-audio.wav")).toString(), ""
        );
        List<NativeAudioTrack> queue = new ArrayList<>(libraryTracks.size() + 1);
        queue.add(badTrack);
        queue.addAll(libraryTracks);

        AtomicReference<NativePlaybackController> controllerRef = new AtomicReference<>();
        CountDownLatch recoveredIntoLibrary = new CountDownLatch(1);
        CountDownLatch playbackEnded = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        Set<String> playedIds = ConcurrentHashMap.newKeySet();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                NativePlaybackController controller = new NativePlaybackController(context, null);
                controllerRef.set(controller);
                controller.getEpicenterAudioProcessor().setEpicenterMode(true);
                controller.getEpicenterAudioProcessor().setEpicenterEnabled(true);
                controller.addPlayerListener(new Player.Listener() {
                    @Override public void onMediaItemTransition(MediaItem item, int reason) {
                        if (item != null && validIds.contains(item.mediaId)) playedIds.add(item.mediaId);
                    }
                    @Override public void onPlaybackStateChanged(int state) {
                        MediaItem item = controller.getPlayer().getCurrentMediaItem();
                        if (state == Player.STATE_READY && item != null && validIds.contains(item.mediaId)) {
                            recoveredIntoLibrary.countDown();
                        }
                        if (state == Player.STATE_ENDED) playbackEnded.countDown();
                    }
                    @Override public void onPlayerError(PlaybackException error) { errors.incrementAndGet(); }
                });
                controller.setQueue(queue, 0);
                controller.play();
            });

            assertTrue("Playback did not recover past the unavailable first item", recoveredIntoLibrary.await(20, TimeUnit.SECONDS));
            NativePlaybackController controller = controllerRef.get();
            assertNotNull(controller);
            AtomicInteger recoveredIndex = new AtomicInteger(-1);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> recoveredIndex.set(controller.getCurrentIndex()));
            assertTrue("Recovery should select a generated valid WAV", recoveredIndex.get() > 0);
            assertEquals("Only the missing sentinel should fail", 1, errors.get());

            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                controller.pause();
                int invalidIndex = -1;
                List<NativeAudioTrack> current = controller.getQueue();
                for (int i = 0; i < current.size(); i++) {
                    if (badTrack.id.equals(current.get(i).id)) { invalidIndex = i; break; }
                }
                if (invalidIndex >= 0) controller.removeTrack(invalidIndex);

                int size = controller.getQueue().size();
                for (int i = 0; i < 120; i++) {
                    int from = (i * 17 + 3) % size;
                    int to = (i * 29 + 11) % size;
                    if (from != to) controller.moveTrack(from, to);
                }
                assertEquals(libraryTracks.size(), controller.getPlayer().getMediaItemCount());
                assertEquals(libraryTracks.size(), controller.getQueue().size());
                for (int i = 0; i < size; i++) {
                    assertEquals(controller.getQueue().get(i).id, controller.getPlayer().getMediaItemAt(i).mediaId);
                }
                playedIds.clear();
                controller.skipToIndex(0);
                playedIds.add(controller.getQueue().get(0).id);
                controller.play();
            });

            // Change controls while the decoder, sink and format transitions run.
            for (int i = 0; i < 24; i++) {
                final int change = i;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    EpicenterAudioProcessor processor = controller.getEpicenterAudioProcessor();
                    processor.setEpicenterMode(change % 2 == 0);
                    processor.setEpicenterEnabled(change % 3 != 0);
                    processor.setEpicenterParams((change * 17) % 101, 45f, 50f, 100f, 100f);
                });
                Thread.sleep(70L);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> controller.getEpicenterAudioProcessor().setEpicenterEnabled(true));
            assertTrue("The entire mixed-format queue should reach ENDED", playbackEnded.await(120, TimeUnit.SECONDS));
            assertEquals("Every generated file must play without additional errors", 1, errors.get());
            assertEquals("All 112 files should complete playback", validIds, playedIds);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                NativePlaybackController activeController = controllerRef.get();
                assertNotNull(activeController);
                assertFalse(activeController.getQueue().isEmpty());
                assertNull(activeController.getPlayer().getPlayerError());
                EpicenterAudioProcessor processor = activeController.getEpicenterAudioProcessor();
                assertTrue("Audio must enter the custom processing chain", processor.wasQueueInputCalled());
                assertTrue("Processed PCM must reach the sink", processor.wasGetOutputCalled());
                assertTrue("The enabled DSP must actually process PCM", processor.getProcessedBufferCount() > 0);
                assertNull(processor.getLastError());
                activeController.stop();
            });
        } finally {
            NativePlaybackController controller = controllerRef.get();
            if (controller != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(controller::release);
            }
            File[] generatedFiles = stressDirectory.listFiles();
            if (generatedFiles != null) {
                for (File file : generatedFiles) file.delete();
            }
            stressDirectory.delete();
        }
    }

    private static List<NativeAudioTrack> createWaveTracks(File directory, int count) throws Exception {
        List<NativeAudioTrack> tracks = new ArrayList<>();
        final int[] rates = {16_000, 44_100, 48_000, 96_000, 192_000};
        for (int trackIndex = 0; trackIndex < count; trackIndex++) {
            final int sampleRate = rates[trackIndex % rates.length];
            final int channels = trackIndex % 2 + 1;
            final int bits = trackIndex % 3 == 0 ? 24 : 16;
            final int bytesPerFrame = channels * bits / 8;
            final int frameCount = sampleRate / 5;
            final int dataBytes = frameCount * bytesPerFrame;
            File wave = new File(directory, "stress-track-" + trackIndex + ".wav");
            try (FileOutputStream output = new FileOutputStream(wave)) {
                writeAscii(output, "RIFF");
                writeIntLe(output, 36 + dataBytes);
                writeAscii(output, "WAVEfmt ");
                writeIntLe(output, 16);
                writeShortLe(output, 1);
                writeShortLe(output, channels);
                writeIntLe(output, sampleRate);
                writeIntLe(output, sampleRate * bytesPerFrame);
                writeShortLe(output, bytesPerFrame);
                writeShortLe(output, bits);
                writeAscii(output, "data");
                writeIntLe(output, dataBytes);
                double frequency = 45.0 + (trackIndex % 16) * 7.0;
                for (int frame = 0; frame < frameCount; frame++) {
                    int sample = (int) ((bits == 24 ? 768_000 : 3_000) * Math.sin(2.0 * Math.PI * frequency * frame / sampleRate));
                    for (int channel = 0; channel < channels; channel++) {
                        writeShortLe(output, sample);
                        if (bits == 24) output.write((sample >>> 16) & 0xff);
                    }
                }
            }
            tracks.add(new NativeAudioTrack(
                "stress-track-" + trackIndex,
                "Playback Stress " + trackIndex,
                "Instrumented Test",
                "Native Queue Stress",
                200L,
                Uri.fromFile(wave).toString(),
                ""
            ));
        }
        return tracks;
    }

    private static void writeAscii(FileOutputStream output, String value) throws Exception {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeIntLe(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShortLe(FileOutputStream output, int value) throws Exception {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
