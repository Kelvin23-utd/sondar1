package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator;
import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;

public class Downconverter {
    private Complex[] downchirp;
    private FFTProcessor fftProcessor;

    public Downconverter() {
        ChirpGenerator generator = new ChirpGenerator();
        downchirp = generator.generateDownchirp();
        fftProcessor = new FFTProcessor();
    }

    /**
     * Performs down-conversion of the aligned echo signal to baseband.
     * This is critical for efficient signal processing within mobile hardware constraints.
     *
     * @param alignedSignal The aligned echo signal
     * @return Down-converted baseband signal
     */
    public Complex[] downconvert(Complex[] alignedSignal) {
        int signalLength = alignedSignal.length;
        Complex[] baseband = new Complex[signalLength];

        // Mix with down-chirp to shift to baseband
        // This effectively cancels out the chirp modulation
        for (int i = 0; i < signalLength; i++) {
            if (i < downchirp.length) {
                baseband[i] = alignedSignal[i].multiply(downchirp[i]);
            } else {
                baseband[i] = new Complex(0, 0);
            }
        }

        return baseband;
    }

    /**
     * Processes the baseband signal to create a 2D frequency-time image.
     * This forms the basis for object detection and measurement.
     *
     * @param baseband The down-converted baseband signal
     * @return 2D FFT matrix representing the frequency-time image
     */
    public Complex[][] createFrequencyTimeImage(Complex[] baseband) {
        int signalLength = baseband.length;

        // Configure sliding window parameters
        int windowSize = 512; // Power of 2 for efficient FFT
        int windowStep = 128; // 75% overlap between windows
        int numWindows = (signalLength - windowSize) / windowStep + 1;

        // Create frequency-time image
        Complex[][] timeFreqImage = new Complex[numWindows][windowSize / 2];

        // Apply windowed FFT along the signal
        for (int window = 0; window < numWindows; window++) {
            int startIdx = window * windowStep;

            // Extract window
            Complex[] windowData = new Complex[windowSize];
            for (int i = 0; i < windowSize; i++) {
                if (startIdx + i < signalLength) {
                    // Apply Hann window for reduced spectral leakage
                    double windowCoeff = 0.5 * (1 - Math.cos(2 * Math.PI * i / (windowSize - 1)));
                    windowData[i] = new Complex(
                            baseband[startIdx + i].real * windowCoeff,
                            baseband[startIdx + i].imag * windowCoeff);
                } else {
                    windowData[i] = new Complex(0, 0);
                }
            }

            // Compute FFT for this window
            Complex[] fftResult = fftProcessor.computeFFT(windowData);

            // Store only the positive frequencies (half of FFT output)
            // This is sufficient due to symmetry of real signals
            for (int freq = 0; freq < windowSize / 2; freq++) {
                timeFreqImage[window][freq] = fftResult[freq];
            }
        }

        return timeFreqImage;
    }

    /**
     * Computes the range-Doppler image using 2D FFT.
     * This is used for object detection and tracking.
     *
     * @param timeFreqImage The time-frequency image
     * @return 2D range-Doppler image
     */
    public float[][] computeRangeDopplerImage(Complex[][] timeFreqImage) {
        int timeSteps = timeFreqImage.length;
        int freqBins = timeFreqImage[0].length;

        // Make our dimensions power of 2 for efficient FFT
        int paddedTimeSteps = nextPowerOfTwo(timeSteps);

        // Create output image
        float[][] rangeDopplerImage = new float[freqBins][paddedTimeSteps];

        // For each frequency bin, perform FFT across time dimension
        for (int freq = 0; freq < freqBins; freq++) {
            // Extract time sequence for this frequency
            Complex[] timeSequence = new Complex[paddedTimeSteps];
            for (int t = 0; t < paddedTimeSteps; t++) {
                if (t < timeSteps) {
                    timeSequence[t] = timeFreqImage[t][freq];
                } else {
                    timeSequence[t] = new Complex(0, 0); // Zero padding
                }
            }

            // Compute FFT across time dimension
            Complex[] fftResult = fftProcessor.computeFFT(timeSequence);

            // Compute magnitude and store in output image
            for (int t = 0; t < paddedTimeSteps; t++) {
                rangeDopplerImage[freq][t] = (float) fftResult[t].magnitude();
            }
        }

        return rangeDopplerImage;
    }

    /**
     * Find the next power of two greater than or equal to n
     */
    private int nextPowerOfTwo(int n) {
        int power = 1;
        while (power < n) {
            power *= 2;
        }
        return power;
    }
}