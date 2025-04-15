package com.hccps.xiao.itemdector.sondar.app.audio;

import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_DURATION;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MAX_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.CHIRP_MIN_FREQ;
import static com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager.SAMPLE_RATE;

public class ChirpGenerator {

    /**
     * Generates a linear frequency modulated chirp signal.
     * Uses Hamming window for improved SNR based on Phase 1 findings.
     *
     * @return short array containing the chirp signal samples
     */
    public short[] generateChirp() {
        int numSamples = (int) (SAMPLE_RATE * CHIRP_DURATION / 1000.0);
        short[] chirpSignal = new short[numSamples];

        // Chirp rate (Hz/s)
        double chirpRate = (double) (CHIRP_MAX_FREQ - CHIRP_MIN_FREQ) / (CHIRP_DURATION / 1000.0);

        // Generate chirp with Hamming window
        for (int i = 0; i < numSamples; i++) {
            double time = (double) i / SAMPLE_RATE;
            double instantFreq = CHIRP_MIN_FREQ + chirpRate * time;
            double phase = 2 * Math.PI * (CHIRP_MIN_FREQ * time + 0.5 * chirpRate * time * time);

            // Apply Hamming window
            double window = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (numSamples - 1));

            // Apply windowed sinusoid
            double amplitude = Short.MAX_VALUE * 0.8 * window; // 80% of max amplitude
            chirpSignal[i] = (short) (amplitude * Math.sin(phase));
        }

        return chirpSignal;
    }

    /**
     * Generates a reference chirp template for echo alignment.
     * This is used for correlation with received signals.
     *
     * @return complex array containing the reference chirp template
     */
    public Complex[] generateChirpTemplate() {
        short[] realChirp = generateChirp();
        int numSamples = realChirp.length;
        Complex[] complexTemplate = new Complex[numSamples];

        // Convert real-valued chirp to analytical signal using Hilbert transform
        // This is simplified for mobile implementation
        for (int i = 0; i < numSamples; i++) {
            complexTemplate[i] = new Complex(realChirp[i], 0);
        }

        // For more accuracy, we would apply a full Hilbert transform here
        // But for performance reasons on mobile, we use this simpler approach

        return complexTemplate;
    }

    /**
     * Generates a modulated downchirp for down-conversion
     * Used in baseband processing
     *
     * @return complex array containing the modulated downchirp
     */
    public Complex[] generateDownchirp() {
        int numSamples = (int) (SAMPLE_RATE * CHIRP_DURATION / 1000.0);
        Complex[] downchirp = new Complex[numSamples];

        double chirpRate = (double) (CHIRP_MAX_FREQ - CHIRP_MIN_FREQ) / (CHIRP_DURATION / 1000.0);

        for (int i = 0; i < numSamples; i++) {
            double time = (double) i / SAMPLE_RATE;
            double phase = 2 * Math.PI * (CHIRP_MIN_FREQ * time + 0.5 * chirpRate * time * time);

            // Negative phase for downchirp
            downchirp[i] = new Complex(Math.cos(-phase), Math.sin(-phase));
        }

        return downchirp;
    }

    // Simple Complex number class for signal processing
    public static class Complex {
        public double real;
        public double imag;

        public Complex(double real, double imag) {
            this.real = real;
            this.imag = imag;
        }

        public Complex multiply(Complex other) {
            double newReal = this.real * other.real - this.imag * other.imag;
            double newImag = this.real * other.imag + this.imag * other.real;
            return new Complex(newReal, newImag);
        }

        public Complex add(Complex other) {
            return new Complex(this.real + other.real, this.imag + other.imag);
        }

        public double magnitude() {
            return Math.sqrt(real * real + imag * imag);
        }

        public double phase() {
            return Math.atan2(imag, real);
        }
    }
}