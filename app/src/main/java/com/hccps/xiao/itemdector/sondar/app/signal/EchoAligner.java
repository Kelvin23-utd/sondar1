package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalLogger;
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
//        SignalLogger.logComplexSignal(complexSignal, sampleCounter, "BeforeVelocityEstimation");

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
    /**
     * Aligns echo signals to compensate for object movement.
     * This is the core of the SONDAR system.
     */
    public Complex[] alignEchoes(Complex[] complexSignal) {
        int signalLength = complexSignal.length;
        if (signalLength == 0) {
            Log.e(TAG, "Empty signal received in alignEchoes");
            return new Complex[0];
        }

        // Log input signal stats to verify we have data
        double maxMagnitude = 0;
        double avgMagnitude = 0;
        for (Complex c : complexSignal) {
            if (c == null) continue;
            double mag = c.magnitude();
            maxMagnitude = Math.max(maxMagnitude, mag);
            avgMagnitude += mag;
        }
        avgMagnitude /= signalLength;

        Log.d(TAG, "Starting echo alignment... Input signal: length=" + signalLength
                + ", maxMag=" + maxMagnitude
                + ", avgMag=" + avgMagnitude);

        // Skip processing if signal is too weak
        if (maxMagnitude < 1.0) { // Adjust threshold as needed
            Log.w(TAG, "Signal too weak for alignment, maxMagnitude=" + maxMagnitude);
            return complexSignal; // Return original instead of zeros
        }

        // Step 1: Estimate Doppler velocity with robust fallback
        double velocity = 0;
        double corrScore = 0;
        try {
            velocity = dopplerEstimator.estimateVelocity(complexSignal, chirpTemplate);
            corrScore = dopplerEstimator.getLastCorrelationScore(); // Add this getter to DopplerEstimator

            Log.d(TAG, "Estimated Doppler Velocity: " + String.format("%.4f", velocity)
                    + " m/s, correlation score: " + corrScore);

            // Check if velocity estimation was reliable
            if (corrScore < 1000) { // Adjust threshold as needed
                Log.w(TAG, "Low correlation score, using default velocity of 0");
                velocity = 0; // Use safe default
            }

            // Clamp velocity to reasonable range
            if (Math.abs(velocity) > 10.0) {
                Log.w(TAG, "Extreme velocity detected, clamping: " + velocity);
                velocity = Math.signum(velocity) * 10.0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in velocity estimation", e);
            velocity = 0; // Safe fallback
        }

        // Step 2: Apply velocity-based alignment
        double scaleFactor = 1 + velocity / SPEED_OF_SOUND;
        Log.i(TAG, "Applying alignment with scale factor: " + scaleFactor);

        Complex[] alignedSignal = new Complex[signalLength];

        try {
            // Apply time scaling to compensate for Doppler effect
            for (int i = 0; i < signalLength; i++) {
                // Calculate original time index before Doppler effect
                double originalIdx = i * scaleFactor;
                int lowerIdx = (int) Math.floor(originalIdx);
                int upperIdx = (int) Math.ceil(originalIdx);
                double fraction = originalIdx - lowerIdx;

                // Enhanced boundary checking and handling
                if (lowerIdx >= 0 && upperIdx < signalLength &&
                        complexSignal[lowerIdx] != null && complexSignal[upperIdx] != null) {
                    Complex lowerSample = complexSignal[lowerIdx];
                    Complex upperSample = complexSignal[upperIdx];

                    // Linear interpolation between samples
                    double realPart = lowerSample.real * (1 - fraction) + upperSample.real * fraction;
                    double imagPart = lowerSample.imag * (1 - fraction) + upperSample.imag * fraction;

                    alignedSignal[i] = new Complex(realPart, imagPart);
                } else if (lowerIdx >= 0 && lowerIdx < signalLength && complexSignal[lowerIdx] != null) {
                    // Just use the lower sample if upper is out of bounds
                    alignedSignal[i] = new Complex(complexSignal[lowerIdx].real, complexSignal[lowerIdx].imag);
                } else if (upperIdx >= 0 && upperIdx < signalLength && complexSignal[upperIdx] != null) {
                    // Just use the upper sample if lower is out of bounds
                    alignedSignal[i] = new Complex(complexSignal[upperIdx].real, complexSignal[upperIdx].imag);
                } else {
                    // If truly out of bounds, use zeros but log this case
                    alignedSignal[i] = new Complex(0, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during signal alignment", e);
            // On error, return original signal instead of zeros
            return complexSignal;
        }

        // Verify the aligned signal isn't all zeros
        boolean allZeros = true;
        for (Complex c : alignedSignal) {
            if (c != null && (Math.abs(c.real) > 1e-10 || Math.abs(c.imag) > 1e-10)) {
                allZeros = false;
                break;
            }
        }

        if (allZeros) {
            Log.e(TAG, "Alignment resulted in all zeros - using original signal instead");
            return complexSignal; // Return original signal as fallback
        }

        // Step 3: Compensate for device latency
        int latencySamples = (int) (DEVICE_LATENCY * SAMPLE_RATE / 1000);
        Log.d(TAG, "Applying latency compensation: " + latencySamples + " samples");

        Complex[] latencyCompensated = SignalUtils.removeLatency(alignedSignal, latencySamples);

        // Final verification
        double finalMaxMag = 0;
        for (Complex c : latencyCompensated) {
            if (c != null) {
                finalMaxMag = Math.max(finalMaxMag, c.magnitude());
            }
        }

        Log.d(TAG, "Echo alignment complete. Output max magnitude: " + finalMaxMag);
        return latencyCompensated;
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