package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalUtils;

import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.SAMPLE_RATE;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.DEVICE_LATENCY;

import android.util.Log;

public class EchoAligner {
    private static final String TAG = "SONDAR_EchoAligner";

    // Configurable parameters
    private static final int MAX_VELOCITY = 5; // m/s - max expected velocity
    private static final int VELOCITY_STEPS = 21; // Number of velocity hypotheses to test
    private static final double SPEED_OF_SOUND = 343.0; // m/s

    private Complex[] chirpTemplate;
    private DopplerEstimator dopplerEstimator;

    public EchoAligner() {
        com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator generator = new com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator();
        chirpTemplate = generator.generateChirpTemplate();
        dopplerEstimator = new DopplerEstimator();
    }

    /**
     * Aligns echo signals to compensate for object movement.
     * This is the core of the SONDAR system.
     *
     * @param receivedSignal The received audio signal containing echoes (raw short array)
     * @return Aligned signal ready for further processing
     */
    public Complex[] alignEchoes(short[] receivedSignal) {
        // Convert received signal to complex form
        Complex[] complexSignal = SignalUtils.shortToComplex(receivedSignal);

        // Step 1: Estimate Doppler velocity
        double velocity = dopplerEstimator.estimateVelocity(complexSignal, chirpTemplate);

        // Step 2: Apply velocity-based alignment
        Complex[] alignedSignal = applyVelocityAlignment(complexSignal, velocity);

        // Step 3: Compensate for device latency
        int latencySamples = (int) (DEVICE_LATENCY * SAMPLE_RATE / 1000);
        alignedSignal = SignalUtils.removeLatency(alignedSignal, latencySamples);

        return alignedSignal;
    }

    /**
     * Overloaded method to accept Complex[] directly
     * This version is used when the signal has already been preprocessed
     *
     * @param complexSignal The preprocessed complex signal
     * @return Aligned signal ready for further processing
     */
    public Complex[] alignEchoes(Complex[] complexSignal) {
        Log.d(TAG, "Starting echo alignment..."); // TAG is already defined as "SONDAR_EchoAligner"
        // Step 1: Estimate Doppler velocity
        double velocity = dopplerEstimator.estimateVelocity(complexSignal, chirpTemplate);

        // Add this line after velocity estimation:
        Log.d(TAG, "Estimated Doppler Velocity: " + String.format("%.4f", velocity) + " m/s");

        // Step 2: Apply velocity-based alignment
        Log.i(TAG, "Applying alignment via direct time-scaling based on estimated velocity (Not the paper's modified template method). Scale factor: " + (1 + velocity / SPEED_OF_SOUND));
        Complex[] alignedSignal = applyVelocityAlignment(complexSignal, velocity);

        // Step 3: Compensate for device latency
        int latencySamples = (int) (DEVICE_LATENCY * SAMPLE_RATE / 1000);
        Log.d(TAG, "Applying latency compensation: " + latencySamples + " samples"); // Log latency removal
        alignedSignal = SignalUtils.removeLatency(alignedSignal, latencySamples);

        Log.d(TAG, "Echo alignment complete."); // Add log at the end

        return alignedSignal;
    }

    /**
     * Applies velocity-based alignment to the signal.
     * This compensates for Doppler effects in the echo.
     */
    private Complex[] applyVelocityAlignment(Complex[] signal, double velocity) {
        int signalLength = signal.length;
        Complex[] alignedSignal = new Complex[signalLength];

        // Calculate time scaling factor based on velocity
        double scaleFactor = 1 + velocity / SPEED_OF_SOUND;

        // Apply time scaling to compensate for Doppler effect
        for (int i = 0; i < signalLength; i++) {
            // Calculate original time index before Doppler effect
            double originalIdx = i * scaleFactor;
            int lowerIdx = (int) Math.floor(originalIdx);
            int upperIdx = (int) Math.ceil(originalIdx);
            double fraction = originalIdx - lowerIdx;

            // Interpolate between samples
            if (lowerIdx >= 0 && upperIdx < signalLength) {
                Complex lowerSample = signal[lowerIdx];
                Complex upperSample = signal[upperIdx];

                // Linear interpolation between samples
                double realPart = lowerSample.real * (1 - fraction) + upperSample.real * fraction;
                double imagPart = lowerSample.imag * (1 - fraction) + upperSample.imag * fraction;

                alignedSignal[i] = new Complex(realPart, imagPart);
            } else {
                // Handle edge cases
                alignedSignal[i] = new Complex(0, 0);
            }
        }

        return alignedSignal;
    }

    /**
     * Get the estimated velocity from the last alignment
     */
    public double getEstimatedVelocity() {
        return dopplerEstimator.getLastVelocity();
    }
}