package com.hccps.xiao.itemdector.sondar.app.processing;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.signal.FFTProcessor;

import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.SAMPLE_RATE;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MIN_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MAX_FREQ;

import android.util.Log;

public class SignalProcessor {
    private FFTProcessor fftProcessor;
    private BandpassFilter bandpassFilter;
    private BackgroundSubtractor backgroundSubtractor;
    private PhaseCompensator phaseCompensator;

    public SignalProcessor() {
        fftProcessor = new FFTProcessor();
        bandpassFilter = new BandpassFilter(CHIRP_MIN_FREQ, CHIRP_MAX_FREQ, SAMPLE_RATE);
        backgroundSubtractor = new BackgroundSubtractor();
        phaseCompensator = new PhaseCompensator();
    }

    /**
     * Processes the raw audio signal through the minimum viable pipeline.
     *
     * @param rawSignal The raw audio signal
     * @return Processed complex signal ready for echo alignment
     */
    public Complex[] preprocess(short[] rawSignal) {
        // Step 1: Convert to complex numbers
        // Add this line at the beginning of the preprocess method
        Log.d("SONDAR_SignalProc", "Starting preprocessing..."); // Use a specific tag for this class
        Complex[] complexSignal = new Complex[rawSignal.length];
        for (int i = 0; i < rawSignal.length; i++) {
            complexSignal[i] = new Complex(rawSignal[i], 0);
        }

        // Step 2: Apply bandpass filter (focus on ultrasonic frequency range)
        complexSignal = bandpassFilter.apply(complexSignal);

        // Add this line before applying the filter
        Log.d("SONDAR_SignalProc", "Applying bandpass filter (Low: " + CHIRP_MIN_FREQ + " Hz, High: " + CHIRP_MAX_FREQ + " Hz)...");
        complexSignal = bandpassFilter.apply(complexSignal);

        return complexSignal;
    }

    /**
     * Applies background subtraction to the frequency-time image.
     * This helps isolate the reflections from moving objects.
     *
     * @param timeFreqImage The time-frequency image
     * @return Background-subtracted image
     */
    public Complex[][] removeBackground(Complex[][] timeFreqImage) {
        // Add log before the call
        Log.d("SONDAR_SignalProc", "Applying background subtraction..."); // Use the specific tag for this class

        // Store the result
        Complex[][] result = backgroundSubtractor.removeBackground(timeFreqImage);

        // Add log after the call completes
        Log.d("SONDAR_SignalProc", "Background subtraction complete.");

        // Return the result
        return result;
    }

    /**
     * Applies basic phase compensation to improve image clarity.
     *
     * @param rangeDopplerImage The range-Doppler image
     * @param velocity The estimated velocity
     * @return Phase-compensated image
     */
    public float[][] compensatePhase(float[][] rangeDopplerImage, double velocity) {
        Log.i("SONDAR_SignalProc", "Applying phase compensation (Basic column shift based on velocity: " + String.format("%.4f", velocity) + " m/s). Not MEA.");
        return phaseCompensator.compensate(rangeDopplerImage, velocity);
    }

    /**
     * Complete signal processing pipeline.
     *
     * @param rawSignal The raw audio signal
     * @return Processed range-Doppler image
     */
    public float[][] processFull(short[] rawSignal, double velocity) {
        // Preprocessing
        Complex[] complexSignal = preprocess(rawSignal);

        // We'd normally do more here, but for MVP we're simplifying

        return new float[1][1]; // Placeholder - full implementation would return processed image
    }
}

/**
 * Implements a basic bandpass filter focused on the ultrasonic frequency range.
 */
class BandpassFilter {
    private final double lowFreq;
    private final double highFreq;
    private final int sampleRate;
    private Complex[] filterKernel;

    public BandpassFilter(double lowFreq, double highFreq, int sampleRate) {
        this.lowFreq = lowFreq;
        this.highFreq = highFreq;
        this.sampleRate = sampleRate;

        // Create filter kernel (simple FIR filter)
        createFilterKernel();
    }

    private void createFilterKernel() {
        int kernelSize = 101; // Odd number for symmetric kernel
        filterKernel = new Complex[kernelSize];

        double lowNormalized = 2.0 * Math.PI * lowFreq / sampleRate;
        double highNormalized = 2.0 * Math.PI * highFreq / sampleRate;

        // Create bandpass filter kernel
        for (int i = 0; i < kernelSize; i++) {
            int n = i - kernelSize / 2;

            if (n == 0) {
                // Center point
                filterKernel[i] = new Complex(highNormalized - lowNormalized, 0);
            } else {
                // Sinc function for bandpass
                double highSinc = Math.sin(highNormalized * n) / (Math.PI * n);
                double lowSinc = Math.sin(lowNormalized * n) / (Math.PI * n);

                // Apply Hamming window for side lobe reduction
                double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (kernelSize - 1));
                filterKernel[i] = new Complex((highSinc - lowSinc) * window, 0);
            }
        }
    }

    /**
     * Applies the bandpass filter to the input signal.
     */
    public Complex[] apply(Complex[] signal) {
        int signalLength = signal.length;
        int kernelLength = filterKernel.length;
        Complex[] filteredSignal = new Complex[signalLength];

        // Apply convolution
        for (int i = 0; i < signalLength; i++) {
            double real = 0;
            double imag = 0;

            for (int j = 0; j < kernelLength; j++) {
                int signalIdx = i - j + kernelLength / 2;

                if (signalIdx >= 0 && signalIdx < signalLength) {
                    real += signal[signalIdx].real * filterKernel[j].real;
                    imag += signal[signalIdx].imag * filterKernel[j].real;
                }
            }

            filteredSignal[i] = new Complex(real, imag);
        }

        return filteredSignal;
    }
}
/**
 * Implements improved background subtraction with null checks.
 */
class BackgroundSubtractor {
    private Complex[][] background = null;
    private static final double ALPHA = 0.05; // Background adaptation rate

    /**
     * Removes the background from a time-frequency image.
     * Added null checks to prevent NullPointerException.
     */
    public Complex[][] removeBackground(Complex[][] currentFrame) {
        // Safety check for null input
        if (currentFrame == null) {
            return null;
        }

        int rows = currentFrame.length;
        if (rows == 0) {
            return new Complex[0][0];
        }

        int cols = currentFrame[0].length;

        // Initialize background model if needed
        if (background == null) {
            background = new Complex[rows][cols];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    // Handle null values in input frame
                    if (currentFrame[i][j] != null) {
                        background[i][j] = new Complex(currentFrame[i][j].real, currentFrame[i][j].imag);
                    } else {
                        background[i][j] = new Complex(0, 0);
                    }
                }
            }
            return currentFrame; // First frame - no subtraction
        }

        // Subtract background and update model
        Complex[][] result = new Complex[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // Safety check for null values
                if (currentFrame[i][j] == null) {
                    result[i][j] = new Complex(0, 0);
                    continue;
                }

                if (background[i][j] == null) {
                    background[i][j] = new Complex(0, 0);
                }

                // Subtract background
                result[i][j] = new Complex(
                        currentFrame[i][j].real - background[i][j].real,
                        currentFrame[i][j].imag - background[i][j].imag);

                // Update background model (slowly adapt to changes)
                background[i][j] = new Complex(
                        background[i][j].real * (1 - ALPHA) + currentFrame[i][j].real * ALPHA,
                        background[i][j].imag * (1 - ALPHA) + currentFrame[i][j].imag * ALPHA);
            }
        }

        return result;
    }
}

/**
 * Implements basic phase compensation.
 */
class PhaseCompensator {
    private static final double SPEED_OF_SOUND = 343.0; // m/s

    /**
     * Compensates for phase shifts in the range-Doppler image.
     */
    public float[][] compensate(float[][] image, double velocity) {
        int rows = image.length;
        int cols = image[0].length;
        float[][] compensated = new float[rows][cols];

        // Simple velocity-based compensation
        // For MVP, we just apply a basic correction based on velocity
        double compensationFactor = 1.0 + velocity / SPEED_OF_SOUND;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // Apply compensation (simplified approach)
                int compensatedCol = (int) (j * compensationFactor);
                if (compensatedCol >= 0 && compensatedCol < cols) {
                    compensated[i][compensatedCol] = image[i][j];
                }
            }
        }

        return compensated;
    }
}