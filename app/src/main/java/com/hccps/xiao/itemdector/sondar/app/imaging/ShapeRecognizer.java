package com.hccps.xiao.itemdector.sondar.app.imaging;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.RotatedRect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Implements basic shape recognition using OpenCV functions
 * as described in the SONDAR paper.
 */
public class ShapeRecognizer {
    private static final String TAG = "SONDAR_ShapeRecognizer";

    public enum ShapeType {
        UNKNOWN,
        CIRCLE,
        RECTANGLE,
        SQUARE,
        TRIANGLE,
        ELLIPSE,
        POLYGON
    }

    private boolean isInitialized = false;

    /**
     * Constructor that initializes OpenCV if available
     */
    public ShapeRecognizer() {
        // Initialize OpenCV when first used
        if (!isInitialized) {
            isInitialized = initOpenCV();
        }
    }

    /**
     * Initializes OpenCV library
     *
     * @return true if initialization successful, false otherwise
     */
    private boolean initOpenCV() {
        boolean success = OpenCVLoader.initDebug();
        if (success) {
            Log.i(TAG, "OpenCV initialization successful");
        } else {
            Log.e(TAG, "OpenCV initialization failed");
        }
        return success;
    }

    /**
     * Recognizes the shape of an object in the physical space image
     *
     * @param physicalImage The physical space image
     * @param threshold Threshold value for shape detection
     * @return The detected shape type
     */
    public ShapeType recognizeShape(float[][] physicalImage, float threshold) {
        if (!isInitialized) {
            Log.e(TAG, "OpenCV not initialized");
            return ShapeType.UNKNOWN;
        }

        if (physicalImage == null || physicalImage.length == 0) {
            Log.e(TAG, "Invalid physical image");
            return ShapeType.UNKNOWN;
        }

        try {
            // Convert the float array to a bitmap
            Bitmap bmp = convertToBitmap(physicalImage, threshold);

            // Convert bitmap to OpenCV Mat
            Mat imageMat = new Mat();
            Utils.bitmapToMat(bmp, imageMat);

            // Convert to grayscale
            Mat grayMat = new Mat();
            Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGR2GRAY);

            // Apply threshold to create binary image
            Mat binaryMat = new Mat();
            Imgproc.threshold(grayMat, binaryMat, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binaryMat.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // If no contours found, return unknown
            if (contours.isEmpty()) {
                Log.d(TAG, "No contours found");
                return ShapeType.UNKNOWN;
            }

            // Find the largest contour
            int largestContourIdx = 0;
            double maxArea = 0;

            for (int i = 0; i < contours.size(); i++) {
                double area = Imgproc.contourArea(contours.get(i));
                if (area > maxArea) {
                    maxArea = area;
                    largestContourIdx = i;
                }
            }

            // Get the largest contour
            MatOfPoint contour = contours.get(largestContourIdx);

            // Convert MatOfPoint to MatOfPoint2f
            MatOfPoint2f contour2f = new MatOfPoint2f();
            contour.convertTo(contour2f, CvType.CV_32FC2);

            // Create approx curve
            MatOfPoint2f approxCurve = new MatOfPoint2f();

            // Calculate arc length and approximate the polygon
            double epsilon = 0.04 * Imgproc.arcLength(contour2f, true);
            Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true);

            // Get the number of vertices
            int vertices = (int) approxCurve.total();
            Log.d(TAG, "Shape has " + vertices + " vertices");

            // Classify shape based on vertices
            ShapeType shapeType;

            if (vertices == 3) {
                shapeType = ShapeType.TRIANGLE;
            } else if (vertices == 4) {
                // Distinguish between rectangle and square
                Rect boundingRect = Imgproc.boundingRect(contour);
                double aspectRatio = boundingRect.width / (double) boundingRect.height;

                if (aspectRatio >= 0.9 && aspectRatio <= 1.1) {
                    shapeType = ShapeType.SQUARE;
                } else {
                    shapeType = ShapeType.RECTANGLE;
                }
            } else if (vertices >= 5 && vertices <= 10) {
                // Check if it's a circle/ellipse or polygon
                double contourArea = Imgproc.contourArea(contour);

                // Find minimum enclosing circle
                Point center = new Point();
                float[] radius = new float[1];
                Imgproc.minEnclosingCircle(contour2f, center, radius);
                double circleArea = Math.PI * radius[0] * radius[0];

                double areaRatio = Math.abs(contourArea / circleArea);

                if (areaRatio > 0.8 && contour.rows() >= 5) {  // Close to a circle & enough points for ellipse fitting
                    // Check if it's more like a circle or an ellipse
                    RotatedRect rotatedRect = Imgproc.fitEllipse(contour2f);
                    double majorAxis = Math.max(rotatedRect.size.width, rotatedRect.size.height);
                    double minorAxis = Math.min(rotatedRect.size.width, rotatedRect.size.height);
                    double eccentricity = minorAxis / majorAxis;

                    if (eccentricity > 0.8) {  // Close to 1 means it's a circle
                        shapeType = ShapeType.CIRCLE;
                    } else {
                        shapeType = ShapeType.ELLIPSE;
                    }
                } else {
                    shapeType = ShapeType.POLYGON;
                }
            } else {
                shapeType = ShapeType.POLYGON;
            }

            // Clean up OpenCV resources
            imageMat.release();
            grayMat.release();
            binaryMat.release();
            hierarchy.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
            contour2f.release();
            approxCurve.release();

            Log.i(TAG, "Shape recognized as: " + shapeType.name());
            return shapeType;

        } catch (Exception e) {
            Log.e(TAG, "Error in shape recognition: " + e.getMessage(), e);
            return ShapeType.UNKNOWN;
        }
    }

    /**
     * Converts the float array physical image to a bitmap for OpenCV processing
     *
     * @param physicalImage The physical space image
     * @param threshold Threshold value for visualization
     * @return Bitmap representation of the image
     */
    private Bitmap convertToBitmap(float[][] physicalImage, float threshold) {
        int height = physicalImage.length;
        int width = physicalImage[0].length;

        // Create a bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Fill with black background
        canvas.drawColor(Color.BLACK);

        // Find max value for normalization
        float maxValue = 0.001f; // Small non-zero default to avoid division by zero
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                maxValue = Math.max(maxValue, physicalImage[i][j]);
            }
        }

        // Create paint for drawing
        Paint paint = new Paint();

        // Draw the physical image
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                // Normalize and threshold the value
                float normalizedValue = physicalImage[i][j] / maxValue;

                if (normalizedValue > threshold) {
                    // Use white for values above threshold (object)
                    paint.setColor(Color.WHITE);
                } else {
                    // Use black for values below threshold (background)
                    paint.setColor(Color.BLACK);
                }

                // Draw a pixel
                canvas.drawPoint(j, i, paint);
            }
        }

        return bitmap;
    }

    /**
     * Creates a bitmap visualization of the shape recognition process
     *
     * @param physicalImage The physical space image
     * @param threshold Threshold for visualization
     * @return Bitmap showing the recognized contours
     */
    public Bitmap createBitmapFromPhysicalImage(float[][] physicalImage, float threshold) {
        if (!isInitialized || physicalImage == null || physicalImage.length == 0) {
            return null;
        }

        try {
            Bitmap inputBmp = convertToBitmap(physicalImage, threshold);

            // Convert to OpenCV and process
            Mat imageMat = new Mat();
            Utils.bitmapToMat(inputBmp, imageMat);

            // Convert to grayscale
            Mat grayMat = new Mat();
            Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_BGR2GRAY);

            // Threshold
            Mat binaryMat = new Mat();
            Imgproc.threshold(grayMat, binaryMat, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binaryMat.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            // Create output visualization
            Mat outputMat = Mat.zeros(imageMat.size(), CvType.CV_8UC3);

            // Draw original image
            for (int i = 0; i < imageMat.rows(); i++) {
                for (int j = 0; j < imageMat.cols(); j++) {
                    double[] pixel = imageMat.get(i, j);
                    outputMat.put(i, j, pixel);
                }
            }

            // Draw contours
            for (int i = 0; i < contours.size(); i++) {
                Scalar color = new Scalar(255, 0, 0); // Red contour
                Imgproc.drawContours(outputMat, contours, i, color, 2);

                // If it's the largest contour, do additional processing
                if (i == 0) {
                    MatOfPoint contour = contours.get(i);

                    // Convert to MatOfPoint2f
                    MatOfPoint2f contour2f = new MatOfPoint2f();
                    contour.convertTo(contour2f, CvType.CV_32FC2);

                    // Approximate polygon
                    MatOfPoint2f approxCurve = new MatOfPoint2f();
                    double epsilon = 0.04 * Imgproc.arcLength(contour2f, true);
                    Imgproc.approxPolyDP(contour2f, approxCurve, epsilon, true);

                    // Convert back to MatOfPoint for drawing
                    MatOfPoint approxPoints = new MatOfPoint();
                    approxCurve.convertTo(approxPoints, CvType.CV_32S);

                    // Draw approximated polygon
                    Imgproc.drawContours(outputMat, List.of(approxPoints), 0, new Scalar(0, 255, 0), 3);

                    // Draw bounding shapes based on detected type
                    if (approxCurve.rows() >= 5) {
                        // For circles/ellipses
                        try {
                            RotatedRect ellipse = Imgproc.fitEllipse(contour2f);
                            Imgproc.ellipse(outputMat, ellipse, new Scalar(0, 0, 255), 2);
                        } catch (Exception e) {
                            Log.d(TAG, "Not enough points for ellipse fitting");
                        }
                    }

                    // Draw bounding rectangle
                    Rect boundingRect = Imgproc.boundingRect(contour);
                    Imgproc.rectangle(outputMat, boundingRect.tl(), boundingRect.br(), new Scalar(255, 255, 0), 2);

                    // Clean up
                    contour2f.release();
                    approxCurve.release();
                    approxPoints.release();
                }
            }

            // Convert back to bitmap
            Bitmap outputBitmap = Bitmap.createBitmap(outputMat.cols(), outputMat.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(outputMat, outputBitmap);

            // Clean up
            imageMat.release();
            grayMat.release();
            binaryMat.release();
            outputMat.release();
            hierarchy.release();
            for (MatOfPoint c : contours) {
                c.release();
            }

            return outputBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error creating bitmap visualization: " + e.getMessage(), e);
            return convertToBitmap(physicalImage, threshold); // Fall back to basic conversion
        }
    }
}