// Copyright 2026 Courville Software
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.archos.mediacenter.video.player;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import androidx.preference.PreferenceManager;

import com.archos.mediacenter.video.CustomApplication;
import com.archos.mediacenter.video.player.upscaling.UpscalingMode;
import com.archos.mediacenter.video.player.upscaling.UpscalingRenderer;
import com.archos.mediacenter.video.utils.PlaybackDiagnostics;
import com.archos.medialib.IMediaPlayer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 30)
@LooperMode(LooperMode.Mode.PAUSED)
public class PlaybackRouteRecoveryTest {
    private Player player;
    private IMediaPlayer media;
    private Handler handler;
    private Runnable prepared;
    private Runnable refresh;
    private MockedStatic<CustomApplication> application;
    private MockedStatic<PlaybackDiagnostics> diagnostics;

    @Before
    public void setUp() throws Exception {
        // Run the real recovery methods without constructing the native renderer.
        player = mock(Player.class, CALLS_REAL_METHODS);
        media = mock(IMediaPlayer.class);
        handler = new Handler(Looper.getMainLooper());
        prepared = mock(Runnable.class);
        refresh = mock(Runnable.class);
        set("mContext", RuntimeEnvironment.getApplication());
        PreferenceManager.getDefaultSharedPreferences(RuntimeEnvironment.getApplication())
                .edit()
                .clear()
                .commit();
        set("mUpscalingUnavailable", "");
        diagnostics = mockStatic(PlaybackDiagnostics.class);
        PlaybackDiagnostics recorder = mock(PlaybackDiagnostics.class);
        diagnostics.when(() -> PlaybackDiagnostics.get(any())).thenReturn(recorder);
        set("mHandler", handler);
        set("mPreparedAsync", prepared);
        set("mRefreshRateCheckerAsync", refresh);
        set("mMediaPlayer", media);
        set("mUri", Uri.parse("file:///video.mkv"));
        set("mSurfaceHolder", mock(SurfaceHolder.class));
        set("mCurrentState", 5); // PLAYING
        set("mTargetState", 5);
        set("mMetadataReady", true);
        set("mHasAudio", true);
        set("mSessionPrepared", true);
        set("mAudioOutputSignature", "HDMI-A");
        application = mockStatic(CustomApplication.class);
        application.when(CustomApplication::getAudioOutputSignature).thenReturn("HDMI-A");
    }

    @After
    public void tearDown() {
        application.close();
        diagnostics.close();
    }

    @Test
    public void exitCancelsPendingWorkAndRejectsRecoveryWithoutReleasingSurface() throws Exception {
        set("mOpenGeneration", 7);
        handler.postDelayed(prepared, 100);
        handler.postDelayed(refresh, 100);

        player.beginPlaybackExit();
        player.beginPlaybackExit();
        player.onAudioOutputChanged();
        player.onAudioBecomingNoisy();
        player.onPrepared(media);
        player.start(PlayerController.STATE_NORMAL);
        player.openVideo();
        player.setVideoURI(Uri.parse("file:///late-source.mkv"), null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));

        assertEquals(8, get("mOpenGeneration"));
        assertEquals(5, get("mTargetState"));
        assertSame(media, get("mMediaPlayer"));
        verifyNoInteractions(media, prepared, refresh);
        application.verifyNoInteractions();
    }

    @Test
    public void unchangedCapabilitiesDoNotReopenActivePlayer() {
        player.onAudioOutputChanged();
        verifyNoInteractions(media);
        verify(player, never()).openVideo();
    }

    @Test
    public void noisyEventStillPausesAnActiveSession() throws Exception {
        player.onAudioBecomingNoisy();
        verify(media).pause();
        assertEquals(6, get("mTargetState")); // PAUSED
    }

    @Test
    public void changedCapabilitiesPreservePlayingIntentAndPosition() throws Exception {
        assertRouteRecoveryPreservesTransport(5);
    }

    @Test
    public void changedCapabilitiesPreserveUserPauseAndPosition() throws Exception {
        assertRouteRecoveryPreservesTransport(6); // PAUSED
    }

    @Test
    public void startWaitsForGpuPreparationBeforeStartingAudio() throws Exception {
        UpscalingRenderer renderer = mock(UpscalingRenderer.class);
        set("mUpscalingRenderer", renderer);
        set("mCurrentState", 6);
        set("mTargetState", 6);
        set("mFocusGranted", true);
        player.start(PlayerController.STATE_NORMAL);
        player.start(PlayerController.STATE_NORMAL);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(renderer, times(1)).preparePipeline(callback.capture());
        verify(media, never()).start();

        when(renderer.isReadyForPlayback()).thenReturn(true);
        callback.getValue().run();
        shadowOf(Looper.getMainLooper()).idle();
        verify(media, times(1)).start();
        assertEquals(5, get("mCurrentState"));
    }

    @Test
    public void pauseDuringGpuPreparationDoesNotResumeWhenItFinishes() throws Exception {
        UpscalingRenderer renderer = mock(UpscalingRenderer.class);
        set("mUpscalingRenderer", renderer);
        player.start(PlayerController.STATE_NORMAL);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(renderer).preparePipeline(callback.capture());
        player.pause(PlayerController.STATE_NORMAL);
        when(renderer.isReadyForPlayback()).thenReturn(true);
        callback.getValue().run();
        shadowOf(Looper.getMainLooper()).idle();
        verify(media, never()).start();
        assertEquals(6, get("mTargetState"));
    }

    @Test
    public void exitingDuringGpuPreparationRejectsItsLateCallback() throws Exception {
        UpscalingRenderer renderer = mock(UpscalingRenderer.class);
        set("mUpscalingRenderer", renderer);
        player.start(PlayerController.STATE_NORMAL);
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(renderer).preparePipeline(callback.capture());
        player.beginPlaybackExit();
        when(renderer.isReadyForPlayback()).thenReturn(true);
        callback.getValue().run();
        shadowOf(Looper.getMainLooper()).idle();
        verify(media, never()).start();
    }

    @Test
    public void offUsesTheOriginalSurfaceWithoutAGpuBridge() throws Exception {
        var method = Player.class.getDeclaredMethod("useUpscalingRenderer");
        method.setAccessible(true);
        player.setUpscalingMode(UpscalingMode.OFF);
        assertEquals(false, method.invoke(player));
        player.setUpscalingMode(UpscalingMode.RAVU);
        assertEquals(true, method.invoke(player));
        set("mNativeHdrSource", true);
        assertEquals(false, method.invoke(player));
        verifyNoInteractions(media);
    }

    @Test
    public void leavingOffPreservesPlayingPositionAndTransport() throws Exception {
        set("mOpenedUpscalingMode", UpscalingMode.OFF);
        when(media.getCurrentPosition()).thenReturn(12_345);
        doNothing().when(player).openVideo();
        player.setUpscalingMode(UpscalingMode.RAVU);
        verify(media).release();
        verify(player).openVideo();
        assertEquals(12_345, get("mStopPosition"));
        assertEquals(5, get("mTargetState"));
        assertEquals(true, get("mRestoringSession"));
    }

    @Test
    public void choosingOffKeepsUserPauseAndPosition() throws Exception {
        set("mOpenedUpscalingMode", UpscalingMode.FSRCNNX);
        set("mCurrentState", 6);
        set("mTargetState", 6);
        when(media.getCurrentPosition()).thenReturn(54_321);
        doNothing().when(player).openVideo();
        player.setUpscalingMode(UpscalingMode.OFF);
        verify(media).release();
        verify(player).openVideo();
        assertEquals(54_321, get("mStopPosition"));
        assertEquals(6, get("mTargetState"));
        assertEquals(true, get("mRestoringSession"));
    }

    @Test
    public void changesBetweenGpuModesDoNotReopenPlayback() throws Exception {
        set("mOpenedUpscalingMode", UpscalingMode.RAVU);
        player.setUpscalingMode(UpscalingMode.FSRCNNX);
        player.setUpscalingMode(UpscalingMode.SGSR1);
        verifyNoInteractions(media);
        verify(player, never()).openVideo();
    }

    private void assertRouteRecoveryPreservesTransport(int target) throws Exception {
        set("mTargetState", target);
        set("mCurrentState", target);
        when(media.getCurrentPosition()).thenReturn(12_345);
        doNothing().when(player).openVideo();
        application.when(CustomApplication::getAudioOutputSignature).thenReturn("HDMI-B");

        player.onAudioOutputChanged();

        verify(media).release();
        verify(player).openVideo();
        assertEquals(target, get("mTargetState"));
        assertEquals(12_345, get("mStopPosition"));
        assertEquals(true, get("mRestoringSession"));
        assertEquals(false, get("mMetadataReady"));

        // A second route notification during replacement preparation must wait for metadata.
        IMediaPlayer replacement = mock(IMediaPlayer.class);
        set("mMediaPlayer", replacement);
        set("mCurrentState", 1); // PREPARING
        application.when(CustomApplication::getAudioOutputSignature).thenReturn("HDMI-C");
        player.onAudioOutputChanged();
        verifyNoInteractions(replacement);
        verify(player, times(1)).openVideo();
    }

    private void set(String name, Object value) throws Exception {
        field(name).set(player, value);
    }

    private Object get(String name) throws Exception {
        return field(name).get(player);
    }

    private Field field(String name) throws Exception {
        Field field = Player.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
