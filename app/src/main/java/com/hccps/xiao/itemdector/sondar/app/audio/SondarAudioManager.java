package com.hccps.xiao.itemdector.sondar.app.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

public class SondarAudioManager {
    private static final String TAG = "SONDAR_AudioManager";

    // Audio configuration based on Phase 1 & 2 findings
    public static final int SAMPLE_RATE = 48000; // Hz
    public static final int CHIRP_MIN_FREQ = 15000; // Hz
    public static final int CHIRP_MAX_FREQ = 17000; // Hz
    public static final int CHIRP_DURATION = 20; // ms
    public static final int BUFFER_SIZE = SAMPLE_RATE / 50; // 20ms buffer
    public static final float DEVICE_LATENCY = 132.78f; // ms, from Phase 2 tests

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isRecording = false;
    private boolean isPlaying = false;

    public SondarAudioManager() {
        initAudio();
    }

    private void initAudio() {
        // Initialize recording
        int recordBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2; // Double buffer for safety

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED, // Raw audio source
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize);
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioRecord: " + e.getMessage());
            // Fallback to default source if UNPROCESSED is not supported
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufferSize);
        }

        // Initialize playback
        int playBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2; // Double buffer for safety

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(playBufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) // Lower latency
                .build();
    }

    public void startRecording(AudioDataCallback callback) {
        if (isRecording) return;

        isRecording = true;
        audioRecord.startRecording();

        new Thread(() -> {
            short[] buffer = new short[BUFFER_SIZE];
            while (isRecording) {
                int readSize = audioRecord.read(buffer, 0, BUFFER_SIZE);
                if (readSize > 0 && callback != null) {
                    callback.onAudioDataReceived(buffer, readSize);
                }
            }
        }).start();
    }

    public void stopRecording() {
        isRecording = false;
        if (audioRecord != null && audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.stop();
        }
    }

    public void playChirp(short[] chirpData) {
        if (isPlaying || chirpData == null) return;

        isPlaying = true;
        audioTrack.play();

        new Thread(() -> {
            audioTrack.write(chirpData, 0, chirpData.length);
            isPlaying = false;
        }).start();
    }

    public void release() {
        stopRecording();

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    public interface AudioDataCallback {
        void onAudioDataReceived(short[] data, int size);
    }
}