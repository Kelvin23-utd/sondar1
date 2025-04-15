package com.hccps.xiao.itemdector.sondar.app.utils;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;

public class SignalUtils {

    /**
     * Converts a short array to a complex array.
     *
     * @param shortArray The input short array
     * @return Complex array with real parts set to short values
     */
    public static Complex[] shortToComplex(short[] shortArray) {
        if (shortArray == null) return null;

        Complex[] complexArray = new Complex[shortArray.length];
        for (int i = 0; i < shortArray.length; i++) {
            complexArray[i] = new Complex(shortArray[i], 0);
        }

        return complexArray;
    }

    /**
     * Removes latency from a signal by shifting samples.
     *
     * @param signal The input signal
     * @param latencySamples The number of samples to shift
     * @return Shifted signal with latency removed
     */
    public static Complex[] removeLatency(Complex[] signal, int latencySamples) {
        if (signal == null || latencySamples <= 0) return signal;

        int length = signal.length;
        Complex[] shifted = new Complex[length];

        // Shift samples to remove latency
        for (int i = 0; i < length; i++) {
            if (i + latencySamples < length) {
                shifted[i] = signal[i + latencySamples];
            } else {
                shifted[i] = new Complex(0, 0);
            }
        }

        return shifted;
    }

    /**
     * Applies a window function to a signal.
     *
     * @param signal The input signal
     * @param windowType The type of window to apply
     * @return Windowed signal
     */
    public static Complex[] applyWindow(Complex[] signal, WindowType windowType) {
        if (signal == null) return null;

        int length = signal.length;
        Complex[] windowed = new Complex[length];

        for (int i = 0; i < length; i++) {
            double windowCoeff = 1.0;

            switch (windowType) {
                case HAMMING:
                    windowCoeff = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (length - 1));
                    break;
                case HANN:
                    windowCoeff = 0.5 * (1 - Math.cos(2 * Math.PI * i / (length - 1)));
                    break;
                case BLACKMAN:
                    windowCoeff = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (length - 1))
                            + 0.08 * Math.cos(4 * Math.PI * i / (length - 1));
                    break;
                case RECTANGULAR:
                default:
                    windowCoeff = 1.0;
                    break;
            }

            windowed[i] = new Complex(
                    signal[i].real * windowCoeff,
                    signal[i].imag * windowCoeff);
        }

        return windowed;
    }

    /**
     * Normalizes a complex signal to have maximum magnitude of 1.0.
     *
     * @param signal The input signal
     * @return Normalized signal
     */
    public static Complex[] normalize(Complex[] signal) {
        if (signal == null) return null;

        // Find maximum magnitude
        double maxMagnitude = 0;
        for (Complex sample : signal) {
            double magnitude = Math.sqrt(sample.real * sample.real + sample.imag * sample.imag);
            maxMagnitude = Math.max(maxMagnitude, magnitude);
        }

        // Normalize signal
        if (maxMagnitude > 0) {
            Complex[] normalized = new Complex[signal.length];
            for (int i = 0; i < signal.length; i++) {
                normalized[i] = new Complex(
                        signal[i].real / maxMagnitude,
                        signal[i].imag / maxMagnitude);
            }
            return normalized;
        } else {
            return signal; // Cannot normalize, return original
        }
    }

    /**
     * Computes the magnitude of a complex signal.
     *
     * @param signal The complex signal
     * @return Array of magnitude values
     */
    public static double[] computeMagnitude(Complex[] signal) {
        if (signal == null) return null;

        double[] magnitude = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            magnitude[i] = Math.sqrt(signal[i].real * signal[i].real + signal[i].imag * signal[i].imag);
        }

        return magnitude;
    }

    /**
     * Computes the phase of a complex signal.
     *
     * @param signal The complex signal
     * @return Array of phase values in radians
     */
    public static double[] computePhase(Complex[] signal) {
        if (signal == null) return null;

        double[] phase = new double[signal.length];
        for (int i = 0; i < signal.length; i++) {
            phase[i] = Math.atan2(signal[i].imag, signal[i].real);
        }

        return phase;
    }

    /**
     * Enum for window function types.
     */
    public enum WindowType {
        RECTANGULAR,
        HAMMING,
        HANN,
        BLACKMAN
    }
}