package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalUtils;

import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MIN_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MAX_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.SAMPLE_RATE;

public class DopplerEstimator {
    // Constants
    private static final double SPEED_OF_SOUND = 343.0; // m/s
    private static final double MIN_VELOCITY = -5.0; // m/s
    private static final double MAX_VELOCITY = 5.0; // m/s
    private static final int VELOCITY_STEPS = 41; // Number of velocity hypotheses to test

    private double lastVelocity = 0;

    /**
     * Estimates the Doppler velocity from the received signal.
     * This is critical for accurate echo alignment.
     *
     * @param receivedSignal The complex received signal
     * @param chirpTemplate The reference chirp template
     * @return The estimated velocity in m/s
     */
    public double estimateVelocity(Complex[] receivedSignal, Complex[] chirpTemplate) {
        double bestCorrelation = -Double.MAX_VALUE;
        double bestVelocity = 0;

        // Step through velocity hypotheses
        double velocityStep = (MAX_VELOCITY - MIN_VELOCITY) / (VELOCITY_STEPS - 1);

        for (int i = 0; i < VELOCITY_STEPS; i++) {
            double testVelocity = MIN_VELOCITY + i * velocityStep;

            // Scale the chirp template according to this velocity hypothesis
            Complex[] scaledTemplate = scaleTemplate(chirpTemplate, testVelocity);

            // Calculate correlation between scaled template and received signal
            double correlation = calculateCorrelation(receivedSignal, scaledTemplate);

            // Keep track of best match
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestVelocity = testVelocity;
            }
        }

        // Refine velocity estimate with a finer search around the best match
        bestVelocity = refineVelocityEstimate(receivedSignal, chirpTemplate, bestVelocity);

        // Apply temporal smoothing (simple EMA filter)
        lastVelocity = 0.7 * lastVelocity + 0.3 * bestVelocity;

        return lastVelocity;
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

        // Search with finer resolution
        for (double velocity = refinedMin; velocity <= refinedMax; velocity += refinedStep) {
            Complex[] scaledTemplate = scaleTemplate(chirpTemplate, velocity);
            double correlation = calculateCorrelation(receivedSignal, scaledTemplate);

            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                refinedVelocity = velocity;
            }
        }

        return refinedVelocity;
    }

    /**
     * Returns the last estimated velocity
     */
    public double getLastVelocity() {
        return lastVelocity;
    }
}