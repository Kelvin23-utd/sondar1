package com.hccps.xiao.itemdector.sondar.app.imaging;

import android.graphics.PointF;
import android.util.Log;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager;

/**
 * Implements the frequency-to-physical space mapping algorithm
 * as described in the SONDAR paper.
 */
public class ImageConverter {
    private static final String TAG = "SONDAR_ImgConverter";
    private static final double SPEED_OF_SOUND = 343.0; // m/s

    // Target geometry constants
    private PointF targetCenter = new PointF(0, 0);
    private double rotationAngle = 0.0; // In radians

    /**
     * Converts a frequency-domain image (range-Doppler image) to physical space.
     *
     * @param rangeDopplerImage The input range-Doppler image from signal processing
     * @param distances Array of distances measured during imaging process
     * @return Physical space image (in mm)
     */
    public float[][] convertToPhysicalSpace(float[][] rangeDopplerImage, double[] distances) {
        if (rangeDopplerImage == null || rangeDopplerImage.length == 0) {
            Log.e(TAG, "Invalid range-Doppler image");
            return null;
        }

        int rows = rangeDopplerImage.length;
        int cols = rangeDopplerImage[0].length;

        // Step 1: Calculate motion parameters
        calculateMotionParameters(distances);

        // Step 2: Calculate frequency resolutions
        double rangeResolution = calculateRangeResolution();
        double azimuthResolution = calculateAzimuthResolution();

        Log.d(TAG, "Range resolution: " + rangeResolution + " mm");
        Log.d(TAG, "Azimuth resolution: " + azimuthResolution + " mm");
        Log.d(TAG, "Rotation angle: " + Math.toDegrees(rotationAngle) + " degrees");

        // Calculate physical dimensions based on the resolutions
        double physicalHeight = rows * rangeResolution;
        double physicalWidth = cols * azimuthResolution;

        Log.d(TAG, "Physical dimensions: " + physicalWidth + "mm x " + physicalHeight + "mm");

        // Step 3: Map frequency-domain image to physical space
        // We'll keep the same dimensions but apply proper scaling
        float[][] physicalImage = new float[rows][cols];

        // Find range and azimuth indices of the strongest reflections
        // to center the object in the physical image
        int maxIntensityRow = 0;
        int maxIntensityCol = 0;
        float maxIntensity = 0;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (rangeDopplerImage[i][j] > maxIntensity) {
                    maxIntensity = rangeDopplerImage[i][j];
                    maxIntensityRow = i;
                    maxIntensityCol = j;
                }
            }
        }

        // Center offset to place the strongest reflection at the center
        int centerRow = rows / 2;
        int centerCol = cols / 2;
        int rowOffset = centerRow - maxIntensityRow;
        int colOffset = centerCol - maxIntensityCol;

        // Apply resolution scaling while preserving the original image dimensions
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                // Apply centering offset
                int sourceRow = i - rowOffset;
                int sourceCol = j - colOffset;

                // Ensure we're within bounds of the source image
                if (sourceRow >= 0 && sourceRow < rows && sourceCol >= 0 && sourceCol < cols) {
                    physicalImage[i][j] = rangeDopplerImage[sourceRow][sourceCol];
                } else {
                    physicalImage[i][j] = 0; // Out of bounds
                }
            }
        }

        // Store the resolution values in the image metadata
        // This will be used by extractSize() to calculate physical dimensions
        targetCenter.x = centerCol;
        targetCenter.y = centerRow;

        return physicalImage;
    }

    /**
     * Calculates the range resolution for frequency-to-physical space mapping.
     * Based on the formula: ρr = (vs · Tc)/(2BT)
     *
     * @return Range resolution in mm
     */
    private double calculateRangeResolution() {
        double vs = SPEED_OF_SOUND * 1000; // Convert to mm/s
        double Tc = SondarAudioManager.CHIRP_DURATION / 1000.0; // In seconds
        double B = SondarAudioManager.CHIRP_MAX_FREQ - SondarAudioManager.CHIRP_MIN_FREQ; // Bandwidth
        double T = (SondarAudioManager.CHIRP_DURATION + 20) / 1000.0; // Total profile duration (chirp + gap)

        return (vs * Tc) / (2 * B * T);
    }

    /**
     * Calculates the azimuth resolution for frequency-to-physical space mapping.
     * Based on the formula: ρa = λ/(2Θ)
     *
     * @return Azimuth resolution in mm
     */
    private double calculateAzimuthResolution() {
        double lambda = SPEED_OF_SOUND * 1000 / SondarAudioManager.CHIRP_MIN_FREQ; // Wavelength in mm
        return lambda / (2 * rotationAngle);
    }

    /**
     * Calculates motion parameters needed for physical space mapping.
     * Specifically, it estimates the rotation angle from the distances array.
     *
     * @param distances Array of distances measured during imaging
     */
    private void calculateMotionParameters(double[] distances) {
        if (distances == null || distances.length < 3) {
            Log.e(TAG, "Insufficient distance measurements for motion parameter estimation");
            rotationAngle = Math.toRadians(15.0); // Default fallback value
            return;
        }

        // Find the minimum distance and its index
        double minDistance = Double.MAX_VALUE;
        int minIndex = 0;

        for (int i = 0; i < distances.length; i++) {
            if (distances[i] < minDistance) {
                minDistance = distances[i];
                minIndex = i;
            }
        }

        // Get distances at the start, min point, and end for azimuth angle calculation
        double distanceStart = distances[0];
        double distanceMin = minDistance;
        double distanceEnd = distances[distances.length - 1];

        // Calculate azimuth angle as per SONDAR paper
        // θ = θl + θr = arccos(Dmin/Dl) + arccos(Dmin/Dr)
        double thetaLeft = Math.acos(distanceMin / distanceStart);
        double thetaRight = Math.acos(distanceMin / distanceEnd);
        rotationAngle = thetaLeft + thetaRight;

        Log.d(TAG, "Motion parameter estimation - rotation angle: " + Math.toDegrees(rotationAngle) + " degrees");
    }

    /**
     * Extracts the estimated target size from the physical image.
     *
     * @param physicalImage The physical space image
     * @param threshold Threshold value for boundary detection (typically 6dB)
     * @return Size information as [length, width] in mm
     */
    /**
     * Fixed extractSize method for more reasonable measurements
     * This addresses the extreme dimensions issue
     */
    public double[] extractSize(float[][] physicalImage, float threshold) {
        if (physicalImage == null || physicalImage.length == 0) {
            return new double[] {0, 0};
        }

        int rows = physicalImage.length;
        int cols = physicalImage[0].length;

        // Calculate the average signal level
        float avgSignal = 0;
        int count = 0;
        float maxSignal = 0;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (physicalImage[i][j] > 0) {
                    avgSignal += physicalImage[i][j];
                    maxSignal = Math.max(maxSignal, physicalImage[i][j]);
                    count++;
                }
            }
        }

        avgSignal = count > 0 ? avgSignal / count : 0;

        // If no meaningful signal, return zeros
        if (maxSignal < 0.001f) {
            Log.w(TAG, "No meaningful signal detected in image");
            return new double[] {0, 0};
        }

        // Set the threshold relative to the max signal for better boundary detection
        // Using max signal instead of average gives more consistent results
        float boundaryThreshold = maxSignal * 0.3f; // 30% of max signal
        Log.d(TAG, "Boundary threshold set to: " + boundaryThreshold + " (30% of max signal: " + maxSignal + ")");

        // Find boundaries in range direction (length)
        int minRow = rows;
        int maxRow = 0;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (physicalImage[i][j] > boundaryThreshold) {
                    minRow = Math.min(minRow, i);
                    maxRow = Math.max(maxRow, i);
                    break;
                }
            }
        }

        // Find boundaries in azimuth direction (width)
        int minCol = cols;
        int maxCol = 0;

        for (int j = 0; j < cols; j++) {
            for (int i = 0; i < rows; i++) {
                if (physicalImage[i][j] > boundaryThreshold) {
                    minCol = Math.min(minCol, j);
                    maxCol = Math.max(maxCol, j);
                    break;
                }
            }
        }

        // Check for valid boundaries
        if (minRow >= maxRow || minCol >= maxCol) {
            Log.w(TAG, "Invalid boundaries detected: rows [" + minRow + ", " + maxRow +
                    "], cols [" + minCol + ", " + maxCol + "]");
            return new double[] {0, 0};
        }

        // Calculate physical dimensions using the resolutions with sanity check
        double rangeResolution = calculateRangeResolution();
        double azimuthResolution = calculateAzimuthResolution();

        // Apply a maximum reasonable size limit (e.g., 1 meter)
        double maxReasonableSize = 1000.0; // 1000 mm = 1 meter

        double rawLength = (maxRow - minRow) * rangeResolution;
        double rawWidth = (maxCol - minCol) * azimuthResolution;

        // Apply sanity check to dimensions
        double length = Math.min(rawLength, maxReasonableSize);
        double width = Math.min(rawWidth, maxReasonableSize);

        // If original dimensions were unreasonable, log a warning
        if (rawLength > maxReasonableSize || rawWidth > maxReasonableSize) {
            Log.w(TAG, "Unreasonable dimensions calculated: " + rawLength + " x " + rawWidth +
                    " mm (capped to " + maxReasonableSize + " mm)");
        }

        Log.d(TAG, "Extracted dimensions - Length: " + length + " mm, Width: " + width + " mm");
        Log.d(TAG, "Pixel boundaries - Rows: [" + minRow + ", " + maxRow + "], Cols: [" + minCol + ", " + maxCol + "]");

        return new double[] {length, width};
    }
}