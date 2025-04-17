package com.hccps.xiao.itemdector.sondar.app.utils;

import android.util.Log;
import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced signal logging utility for SONDAR to help track signal changes across processing stages.
 * This class allows detailed logging of signal information at different stages of processing
 * and exports the data for visualization and analysis.
 */
public class SignalLogger {
    private static final String TAG = "SONDAR_SignalLogger";
    private static final int BUFFER_SIZE = 10; // Number of recent samples to keep in memory
    private static final AtomicInteger experimentCounter = new AtomicInteger(0);

    // Data structures to store signal information
    private static JSONObject currentExperimentData = new JSONObject();
    private static JSONArray samplesArray = new JSONArray();
    private static JSONObject velocityData = new JSONObject();
    private static JSONObject experimentMeta = new JSONObject();

    // Flags
    private static boolean isLoggingEnabled = false;
    private static String experimentName = "";
    private static File outputDir = null;

    /**
     * Initialize logging for a new experiment
     * @param name Descriptive name for the experiment
     * @param directory Directory where log files will be saved
     */
    public static void startExperiment(String name, File directory) {
        try {
            // Reset data structures
            experimentCounter.incrementAndGet();
            currentExperimentData = new JSONObject();
            samplesArray = new JSONArray();
            velocityData = new JSONObject();
            experimentMeta = new JSONObject();

            // Set experiment metadata
            experimentName = name;
            outputDir = directory;
            isLoggingEnabled = true;

            // Record experiment start time
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            experimentMeta.put("name", name);
            experimentMeta.put("startTime", timestamp);
            experimentMeta.put("experimentId", experimentCounter.get());

            // Add these to experimentMeta in startExperiment()
            experimentMeta.put("chirpMinFreq", SondarAudioManager.CHIRP_MIN_FREQ);
            experimentMeta.put("chirpMaxFreq", SondarAudioManager.CHIRP_MAX_FREQ);
            experimentMeta.put("chirpDuration", SondarAudioManager.CHIRP_DURATION);
            experimentMeta.put("sampleRate", SondarAudioManager.SAMPLE_RATE);
            experimentMeta.put("objectType", "box");  // Add actual object type
            experimentMeta.put("objectWidth", 120);   // Add actual width in mm
            experimentMeta.put("objectHeight", 215);  // Add actual height in mm



            currentExperimentData.put("metadata", experimentMeta);
            currentExperimentData.put("samples", samplesArray);

            Log.i(TAG, "Started experiment logging: " + name);
        } catch (JSONException e) {
            Log.e(TAG, "Error initializing experiment data", e);
        }
    }

    /**
     * Log raw signal data
     * @param rawSignal The raw signal data from AudioRecord
     * @param sampleIndex Sample index
     */
    public static void logRawSignal(short[] rawSignal, int sampleIndex) {
        if (!isLoggingEnabled) return;

        try {
            JSONObject sample = new JSONObject();
            sample.put("sampleIndex", sampleIndex);

            // Only log a subset of points to avoid excessive memory usage
            JSONArray signalPoints = new JSONArray();
            int step = rawSignal.length / 100;  // Log about 100 points
            if (step < 1) step = 1;

            for (int i = 0; i < rawSignal.length; i += step) {
                signalPoints.put(rawSignal[i]);
            }

            sample.put("rawSignal", signalPoints);
            addSample(sample);

            // Log summary statistics
            double mean = calculateMean(rawSignal);
            double stdDev = calculateStdDev(rawSignal, mean);
            Log.d(TAG, String.format("Raw signal stats [%d]: mean=%.2f, stdDev=%.2f, len=%d",
                    sampleIndex, mean, stdDev, rawSignal.length));
        } catch (JSONException e) {
            Log.e(TAG, "Error logging raw signal", e);
        }
    }

    /**
     * Log complex signal data after preprocessing
     * @param complexSignal The complex signal after preprocessing
     * @param sampleIndex Sample index
     * @param stageName Name of the processing stage
     */
    public static void logComplexSignal(Complex[] complexSignal, int sampleIndex, String stageName) {
        if (!isLoggingEnabled) return;

        try {
            // Find the existing sample or create a new one
            JSONObject sample = findOrCreateSample(sampleIndex);

            // Only log a subset of points
            JSONArray magnitudes = new JSONArray();
            int step = complexSignal.length / 100;  // Log about 100 points
            if (step < 1) step = 1;

            for (int i = 0; i < complexSignal.length; i += step) {
                magnitudes.put(complexSignal[i].magnitude());
            }

            sample.put(stageName, magnitudes);
            updateSample(sample);

            // Log summary statistics
            double maxMag = 0;
            double avgMag = 0;
            for (Complex c : complexSignal) {
                double mag = c.magnitude();
                maxMag = Math.max(maxMag, mag);
                avgMag += mag;
            }
            avgMag /= complexSignal.length;

            Log.d(TAG, String.format("%s signal stats [%d]: avgMag=%.2f, maxMag=%.2f, len=%d",
                    stageName, sampleIndex, avgMag, maxMag, complexSignal.length));
        } catch (JSONException e) {
            Log.e(TAG, "Error logging complex signal", e);
        }
    }

    /**
     * Log velocity estimation results
     * @param rawVelocity Raw velocity estimate before smoothing
     * @param smoothedVelocity Smoothed velocity estimate
     * @param correlationScore Correlation score for best velocity match
     * @param sampleIndex Sample index
     */
    public static void logVelocityEstimation(double rawVelocity, double smoothedVelocity,
                                             double correlationScore, int sampleIndex) {
        if (!isLoggingEnabled) return;

        try {
            JSONObject velSample = new JSONObject();
            velSample.put("sampleIndex", sampleIndex);
            velSample.put("rawVelocity", rawVelocity);
            velSample.put("smoothedVelocity", smoothedVelocity);
            velSample.put("correlationScore", correlationScore);


            // Find the existing sample or create a new one
            JSONObject sample = findOrCreateSample(sampleIndex);
            sample.put("velocityData", velSample);
            updateSample(sample);

            Log.d(TAG, String.format("Velocity estimation [%d]: raw=%.4f m/s, smoothed=%.4f m/s, corr=%.4f",
                    sampleIndex, rawVelocity, smoothedVelocity, correlationScore));
        } catch (JSONException e) {
            Log.e(TAG, "Error logging velocity data", e);
        }
    }

    /**
     * Log 2D signal data (like range-Doppler image)
     * @param image 2D image data
     * @param sampleIndex Sample index
     * @param stageName Name of the processing stage
     */

    /**
     * Log 2D signal data (like range-Doppler image) with full data capture
     * @param image 2D image data
     * @param sampleIndex Sample index
     * @param stageName Name of the processing stage
     */
    public static void log2DImage(float[][] image, int sampleIndex, String stageName) {
        if (!isLoggingEnabled || image == null || image.length == 0) return;

        try {
            JSONObject sample = findOrCreateSample(sampleIndex);

            // Store statistics as before
            float min = Float.MAX_VALUE;
            float max = Float.MIN_VALUE;
            float sum = 0;
            int count = 0;

            for (float[] row : image) {
                for (float val : row) {
                    min = Math.min(min, val);
                    max = Math.max(max, val);
                    sum += val;
                    count++;
                }
            }

            float mean = sum / count;

            JSONObject imageStats = new JSONObject();
            imageStats.put("min", min);
            imageStats.put("max", max);
            imageStats.put("mean", mean);
            imageStats.put("rows", image.length);
            imageStats.put("cols", image[0].length);

            sample.put(stageName + "_stats", imageStats);

            // NEW CODE: Store downsampled image data for visualization
            // We'll downsample to keep file size reasonable
            int rowStep = Math.max(1, image.length / 50);  // Target ~50 rows max
            int colStep = Math.max(1, image[0].length / 50);  // Target ~50 columns max

            JSONArray imageData = new JSONArray();
            for (int i = 0; i < image.length; i += rowStep) {
                JSONArray row = new JSONArray();
                for (int j = 0; j < image[0].length; j += colStep) {
                    row.put(image[i][j]);
                }
                imageData.put(row);
            }

            sample.put(stageName + "_image", imageData);
            updateSample(sample);

            Log.d(TAG, String.format("%s image logged [%d]: min=%.2f, max=%.2f, mean=%.2f, size=%dx%d (downsampled)",
                    stageName, sampleIndex, min, max, mean, image.length, image[0].length));
        } catch (JSONException e) {
            Log.e(TAG, "Error logging 2D image", e);
        }
    }

    /**
     * Save the experiment data to a file
     */
    public static void saveExperiment() {
        if (!isLoggingEnabled) return;

        try {
            // Add end time to metadata
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            experimentMeta.put("endTime", timestamp);

            // Create output file
            if (outputDir != null) {
                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                // To this:
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
                String filename = String.format("sondar_%s.json", timeStamp);

                File outputFile = new File(outputDir, filename);

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(currentExperimentData.toString(2).getBytes());
                    Log.i(TAG, "Saved experiment data to " + outputFile.getAbsolutePath());
                } catch (IOException e) {
                    Log.e(TAG, "Error saving experiment data", e);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error finalizing experiment data", e);
        } finally {
            isLoggingEnabled = false;
        }
    }

    /**
     * Add a new sample to the samples array
     */
    private static void addSample(JSONObject sample) throws JSONException {
        // If we've reached the buffer size, remove the oldest sample
        if (samplesArray.length() >= BUFFER_SIZE) {
            for (int i = 0; i < samplesArray.length() - BUFFER_SIZE + 1; i++) {
                samplesArray.remove(0);
            }
        }

        samplesArray.put(sample);
    }

    /**
     * Find an existing sample by index or create a new one
     */
    private static JSONObject findOrCreateSample(int sampleIndex) throws JSONException {
        // Check if this sample index already exists
        for (int i = 0; i < samplesArray.length(); i++) {
            JSONObject sample = samplesArray.getJSONObject(i);
            if (sample.getInt("sampleIndex") == sampleIndex) {
                return sample;
            }
        }

        // If not found, create a new sample
        JSONObject newSample = new JSONObject();
        newSample.put("sampleIndex", sampleIndex);
        return newSample;
    }

    /**
     * Update an existing sample in the samples array
     */
    private static void updateSample(JSONObject updatedSample) throws JSONException {
        int sampleIndex = updatedSample.getInt("sampleIndex");

        // Find and update the sample
        boolean found = false;
        for (int i = 0; i < samplesArray.length(); i++) {
            JSONObject sample = samplesArray.getJSONObject(i);
            if (sample.getInt("sampleIndex") == sampleIndex) {
                // Replace the sample
                samplesArray.put(i, updatedSample);
                found = true;
                break;
            }
        }

        // If not found, add it
        if (!found) {
            addSample(updatedSample);
        }
    }

    // Utility methods for calculating statistics
    private static double calculateMean(short[] data) {
        double sum = 0;
        for (short value : data) {
            sum += value;
        }
        return sum / data.length;
    }

    private static double calculateStdDev(short[] data, double mean) {
        double sumSquares = 0;
        for (short value : data) {
            double diff = value - mean;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares / data.length);
    }
}