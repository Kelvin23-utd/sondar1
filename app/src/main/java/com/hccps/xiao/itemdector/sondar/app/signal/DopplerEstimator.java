package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalLogger;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalUtils;

import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MIN_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MAX_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.SAMPLE_RATE;

import android.util.Log;

public class DopplerEstimator {
    private static final String TAG = "SONDAR_DopplerEst";

    // Constants
    private static final double SPEED_OF_SOUND = 343.0; // m/s
    private static final double MIN_VELOCITY = -5.0; // m/s
    private static final double MAX_VELOCITY = 5.0; // m/s
    private static final int VELOCITY_STEPS = 41; // Number of velocity hypotheses to test

    private double lastVelocity = 0;
    private int sampleCounter = 0;

    public double lastCorrelationScore;

    /**
     * Estimates the Doppler velocity from the received signal.
     * This is critical for accurate echo alignment.
     *
     * @param receivedSignal The complex received signal
     * @param chirpTemplate  The reference chirp template
     * @return The estimated velocity in m/s
     */
    public double estimateVelocity(Complex[] receivedSignal, Complex[] chirpTemplate) {
        double bestCorrelation = -Double.MAX_VALUE;
        double bestVelocity = 0;

        // For debugging - track all correlation scores
        double[] correlationScores = new double[VELOCITY_STEPS];
        double[] velocities = new double[VELOCITY_STEPS];

        // Step through velocity hypotheses
        double velocityStep = (MAX_VELOCITY - MIN_VELOCITY) / (VELOCITY_STEPS - 1);

        for (int i = 0; i < VELOCITY_STEPS; i++) {
            double testVelocity = MIN_VELOCITY + i * velocityStep;
            velocities[i] = testVelocity;

            // Scale the chirp template according to this velocity hypothesis
            Complex[] scaledTemplate = scaleTemplate(chirpTemplate, testVelocity);

            // Calculate correlation between scaled template and received signal
            double correlation = calculateCorrelation(receivedSignal, scaledTemplate);
            correlationScores[i] = correlation;

            // Keep track of best match
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestVelocity = testVelocity;
            }
        }

        // Log detailed correlation scores for the highest matches
        // Find top 3 correlation scores for logging
        int[] topIndices = findTopNIndices(correlationScores, 3);
        StringBuilder correlationLog = new StringBuilder("Top velocity candidates: ");
        for (int idx : topIndices) {
            correlationLog.append(String.format("[v=%.2f m/s, corr=%.2f] ",
                    velocities[idx], correlationScores[idx]));
        }
        Log.d(TAG, correlationLog.toString());

        // Refine velocity estimate with a finer search around the best match
        bestVelocity = refineVelocityEstimate(receivedSignal, chirpTemplate, bestVelocity);

        // Inside estimateVelocity, before applying smoothing:
        Log.d(TAG, "Refined velocity before smoothing: " + String.format("%.4f", bestVelocity) + " m/s");

        // Calculate correlation score for the final velocity estimate
        Complex[] finalTemplate = scaleTemplate(chirpTemplate, bestVelocity);
        double finalCorrelation = calculateCorrelation(receivedSignal, finalTemplate);

        // Apply temporal smoothing (simple EMA filter)
        double rawVelocity = bestVelocity; // Save raw value for logging
        lastVelocity = 0.7 * lastVelocity + 0.3 * bestVelocity;
        Log.d(TAG, "Smoothed velocity: " + String.format("%.4f", lastVelocity) + " m/s");

        // Log the correlation score for the best match
        Log.d(TAG, "Final correlation score: " + String.format("%.4f", finalCorrelation));
        lastCorrelationScore = finalCorrelation;

        // Log with sample counter for sequencing
        SignalLogger.logVelocityEstimation(
                rawVelocity,
                lastVelocity,
                finalCorrelation,
                sampleCounter++
        );

        return lastVelocity;
    }
    /**
     * Returns the last estimated velocity
     * @return The most recent velocity estimate in m/s
     */
    public double getLastVelocity() {
        return lastVelocity;
    }

    /**
     * Find the top N indices in an array
     */
    private int[] findTopNIndices(double[] array, int n) {
        int[] indices = new int[n];
        double[] values = new double[n];

        // Initialize with smallest possible values
        for (int i = 0; i < n; i++) {
            indices[i] = -1;
            values[i] = -Double.MAX_VALUE;
        }

        // Find top N values
        for (int i = 0; i < array.length; i++) {
            for (int j = 0; j < n; j++) {
                if (array[i] > values[j]) {
                    // Shift lower values down
                    for (int k = n - 1; k > j; k--) {
                        values[k] = values[k - 1];
                        indices[k] = indices[k - 1];
                    }
                    values[j] = array[i];
                    indices[j] = i;
                    break;
                }
            }
        }

        return indices;
    }

    /**
     * Scales the chirp template according to a velocity hypothesis.
     * This simulates the Doppler effect for a given velocity.
     */
    private Complex[] scaleTemplate(Complex[] template, double velocity) {
        int length = template.length;
        Complex[] scaledTemplate = new Complex[length];

        // Calculate time scaling factor from velocity
        double scaleFactor = 1 + velocity / SPEED_OF_SOUND;

        // Scale the template in time
        for (int i = 0; i < length; i++) {
            // Calculate original time index before Doppler effect
            double originalIdx = i / scaleFactor;
            int lowerIdx = (int) Math.floor(originalIdx);
            int upperIdx = (int) Math.ceil(originalIdx);
            double fraction = originalIdx - lowerIdx;

            // Interpolate between samples
            if (lowerIdx >= 0 && upperIdx < length) {
                Complex lowerSample = template[lowerIdx];
                Complex upperSample = template[upperIdx];

                // Linear interpolation
                double realPart = lowerSample.real * (1 - fraction) + upperSample.real * fraction;
                double imagPart = lowerSample.imag * (1 - fraction) + upperSample.imag * fraction;

                scaledTemplate[i] = new Complex(realPart, imagPart);
            } else {
                // Handle edge cases
                scaledTemplate[i] = new Complex(0, 0);
            }
        }

        return scaledTemplate;
    }

    /**
     * Calculates correlation between two complex signals.
     */
    private double calculateCorrelation(Complex[] signal1, Complex[] signal2) {
        // Ensure signals are the same length
        int length = Math.min(signal1.length, signal2.length);

        // Calculate cross-correlation
        // For efficiency, we focus on the central part of the received signal
        int startIdx = length / 4;
        int endIdx = 3 * length / 4;

        double correlation = 0;
        for (int i = startIdx; i < endIdx; i++) {
            correlation += signal1[i].real * signal2[i].real + signal1[i].imag * signal2[i].imag;
        }

        return correlation;
    }

    /**
     * Refines the velocity estimate using a finer search around the initial estimate.
     */
    private double refineVelocityEstimate(Complex[] receivedSignal, Complex[] chirpTemplate, double initialVelocity) {
        // Define a narrower search range around the initial velocity
        double refinedMin = initialVelocity - 0.5;
        double refinedMax = initialVelocity + 0.5;
        double refinedStep = (refinedMax - refinedMin) / 9; // 10 steps for finer resolution

        double bestCorrelation = -Double.MAX_VALUE;
        double refinedVelocity = initialVelocity;

        // For debugging
        StringBuilder refiningLog = new StringBuilder("Refining velocity: ");
        refiningLog.append(String.format("Initial=%.4f, Range=[%.2f to %.2f]",
                initialVelocity, refinedMin, refinedMax));
        Log.d(TAG, refiningLog.toString());

        // Track all refined correlation scores for debugging
        double[] refinedScores = new double[10];
        double[] refinedVelocities = new double[10];
        int i = 0;

        // Search with finer resolution
        for (double velocity = refinedMin; velocity <= refinedMax; velocity += refinedStep) {
            refinedVelocities[i] = velocity;

            Complex[] scaledTemplate = scaleTemplate(chirpTemplate, velocity);
            double correlation = calculateCorrelation(receivedSignal, scaledTemplate);
            refinedScores[i] = correlation;

            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                refinedVelocity = velocity;
            }
            i++;
        }

        // Log the refinement results
        Log.d(TAG, String.format("Refined velocity: %.4f m/s (correlation: %.4f)",
                refinedVelocity, bestCorrelation));

        return refinedVelocity;
    }

    /**
     * Returns the correlation score from the last velocity estimation
     */
    public double getLastCorrelationScore() {
        return lastCorrelationScore; // Add this as a class member and update it in estimateVelocity
    }
}