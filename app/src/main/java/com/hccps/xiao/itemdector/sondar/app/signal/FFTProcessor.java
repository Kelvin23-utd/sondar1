package com.hccps.xiao.itemdector.sondar.app.signal;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;

public class FFTProcessor {

    /**
     * Computes the Fast Fourier Transform of the input signal.
     * Optimized implementation for mobile performance.
     *
     * @param input The complex input signal
     * @return Complex array containing the FFT result
     */
    public Complex[] computeFFT(Complex[] input) {
        int n = input.length;

        // Check if length is a power of 2
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("FFT length must be a power of 2");
        }

        // Make a copy of the input to avoid modifying it
        Complex[] output = new Complex[n];
        for (int i = 0; i < n; i++) {
            output[i] = new Complex(input[i].real, input[i].imag);
        }

        // Perform bit-reversal permutation
        int shift = 1 + Integer.numberOfLeadingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> shift;
            if (j > i) {
                // Swap elements
                Complex temp = output[i];
                output[i] = output[j];
                output[j] = temp;
            }
        }

        // Cooley-Tukey FFT algorithm
        for (int size = 2; size <= n; size *= 2) {
            double angle = -2 * Math.PI / size;
            Complex wn = new Complex(Math.cos(angle), Math.sin(angle));

            for (int start = 0; start < n; start += size) {
                Complex w = new Complex(1, 0);
                for (int k = 0; k < size / 2; k++) {
                    int idx1 = start + k;
                    int idx2 = start + k + size / 2;

                    Complex t = w.multiply(output[idx2]);
                    Complex u = output[idx1];

                    output[idx1] = new Complex(u.real + t.real, u.imag + t.imag);
                    output[idx2] = new Complex(u.real - t.real, u.imag - t.imag);

                    w = w.multiply(wn);
                }
            }
        }

        return output;
    }

    /**
     * Computes the Inverse Fast Fourier Transform.
     *
     * @param input The complex frequency domain signal
     * @return Complex array containing the time domain signal
     */
    public Complex[] computeIFFT(Complex[] input) {
        int n = input.length;

        // Conjugate the input
        Complex[] conjugated = new Complex[n];
        for (int i = 0; i < n; i++) {
            conjugated[i] = new Complex(input[i].real, -input[i].imag);
        }

        // Compute FFT of conjugated input
        Complex[] output = computeFFT(conjugated);

        // Conjugate the result and scale
        for (int i = 0; i < n; i++) {
            output[i] = new Complex(output[i].real / n, -output[i].imag / n);
        }

        return output;
    }

    /**
     * Computes 2D Fast Fourier Transform of the input matrix.
     *
     * @param input The complex 2D input
     * @return Complex 2D array containing the 2D FFT result
     */
    public Complex[][] compute2DFFT(Complex[][] input) {
        int rows = input.length;
        int cols = input[0].length;
        Complex[][] output = new Complex[rows][cols];

        // Step 1: Compute FFT for each row
        for (int i = 0; i < rows; i++) {
            Complex[] row = new Complex[cols];
            for (int j = 0; j < cols; j++) {
                row[j] = input[i][j];
            }

            Complex[] fftRow = computeFFT(row);
            for (int j = 0; j < cols; j++) {
                output[i][j] = fftRow[j];
            }
        }

        // Step 2: Compute FFT for each column
        for (int j = 0; j < cols; j++) {
            Complex[] col = new Complex[rows];
            for (int i = 0; i < rows; i++) {
                col[i] = output[i][j];
            }

            Complex[] fftCol = computeFFT(col);
            for (int i = 0; i < rows; i++) {
                output[i][j] = fftCol[i];
            }
        }

        return output;
    }

    /**
     * Computes the magnitude of a complex array.
     *
     * @param complex The complex input array
     * @return Float array containing the magnitudes
     */
    public float[] computeMagnitude(Complex[] complex) {
        int n = complex.length;
        float[] magnitude = new float[n];

        for (int i = 0; i < n; i++) {
            magnitude[i] = (float) Math.sqrt(complex[i].real * complex[i].real + complex[i].imag * complex[i].imag);
        }

        return magnitude;
    }

    /**
     * Computes the magnitude of a 2D complex array.
     *
     * @param complex The complex 2D input
     * @return Float 2D array containing the magnitudes
     */
    public float[][] computeMagnitude2D(Complex[][] complex) {
        int rows = complex.length;
        int cols = complex[0].length;
        float[][] magnitude = new float[rows][cols];

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                magnitude[i][j] = (float) Math.sqrt(
                        complex[i][j].real * complex[i][j].real +
                                complex[i][j].imag * complex[i][j].imag);
            }
        }

        return magnitude;
    }
}