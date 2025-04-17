package com.hccps.xiao.itemdector.sondar.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.imaging.ImageConverter;
import com.hccps.xiao.itemdector.sondar.app.imaging.ShapeRecognizer;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SondarProcessor.SondarResultCallback {
    private static final String TAG = "SONDAR_MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO
    };

    private SondarProcessor sondarProcessor;
    private Button startStopButton;
    private ImageView imageView;
    private TextView statusText;
    private TextView velocityText;
    private TextView sizeText;
    private TextView shapeText;

    private boolean isRunning = false;

    // Phase 3 components
    private ImageConverter imageConverter;
    private ShapeRecognizer shapeRecognizer;
    private List<Double> distanceList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        startStopButton = findViewById(R.id.start_stop_button);
        imageView = findViewById(R.id.image_view);
        statusText = findViewById(R.id.status_text);
        velocityText = findViewById(R.id.velocity_text);
        sizeText = findViewById(R.id.size_text);
        shapeText = findViewById(R.id.shape_text);

        // Set up button click listener
        startStopButton.setOnClickListener(v -> {
            if (isRunning) {
                stopSondar();
            } else {
                startSondar();
            }
        });

        // Create SONDAR processor
        sondarProcessor = new SondarProcessor();

        // Initialize Phase 3 components
        imageConverter = new ImageConverter();
        shapeRecognizer = new ShapeRecognizer();

        // Check permissions
        if (!hasPermissions()) {
            requestPermissions();
        }
        startStopButton.setOnClickListener(v -> {
            if (isRunning) {
                stopSondar();
                stopExperiment();
            } else {
                startExperiment("velocity_test_" + System.currentTimeMillis());
                startSondar();
            }
        });

    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startExperiment(String name) {
        // Create a directory in the app's external files directory
        File logDir = new File(getExternalFilesDir(null), "sondar_logs");
        SignalLogger.startExperiment(name, logDir);
    }

    private void stopExperiment() {
        SignalLogger.saveExperiment();
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                statusText.setText("Ready to start");
                startStopButton.setEnabled(true);
            } else {
                statusText.setText("Permissions required");
                startStopButton.setEnabled(false);
            }
        }
    }

    private void startSondar() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }

        // Clear previous distance measurements
        distanceList.clear();

        sondarProcessor.start(this);
        isRunning = true;
        startStopButton.setText("Stop SONDAR");
        statusText.setText("SONDAR Running");

        // Reset result texts
        sizeText.setText("Size: Measuring...");
        shapeText.setText("Shape: Analyzing...");
    }

    private void stopSondar() {
        sondarProcessor.stop();
        isRunning = false;
        startStopButton.setText("Start SONDAR");
        statusText.setText("SONDAR Stopped");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sondarProcessor != null) {
            sondarProcessor.release();
        }
    }


    /**
     * Calculate the approximate distance based on velocity measurements
     * This is used for motion parameter estimation
     */
    private double calculateDistanceFromVelocity(float velocity) {
        // Use the relationship between measured velocity and distance
        // For a target moving across the device's field of view

        // Start with the default reference distance (30 cm)
        double referenceDistance = 30.0; // cm

        // Adjust based on current velocity measurement
        // When target is passing perpendicular, velocity is near zero
        // When far away, velocity magnitude is higher
        return referenceDistance + Math.abs(velocity) * 10.0; // Approximate conversion
    }

    private void displayRangeDopplerImage(float[][] image) {
        if (image == null || image.length == 0) return;

        int height = image.length;    // 256
        int width = image[0].length;  // 4

        // Use larger scaling factors
        int scaleX = 100;  // Make each column 100px wide
        int scaleY = 3;    // Make each row 3px tall

        // Create a bitmap with scaled dimensions
        Bitmap bitmap = Bitmap.createBitmap(width * scaleX, height * scaleY, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Fill with black background
        canvas.drawColor(Color.BLACK);

        // Find max value for normalization
        float maxValue = 0.001f; // Small non-zero default to avoid division by zero
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                maxValue = Math.max(maxValue, image[i][j]);
            }
        }

        // Create paint objects for drawing
        Paint paint = new Paint();
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(48);

        // Draw a border to make the visualization area obvious
        Paint borderPaint = new Paint();
        borderPaint.setColor(Color.RED);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(10);
        canvas.drawRect(0, 0, width * scaleX, height * scaleY, borderPaint);

        // Normalize and draw larger rectangles
        if (maxValue > 0) {
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    // Enhance contrast by using power function
                    float normalizedValue = (float) Math.pow(image[i][j] / maxValue, 0.5);

                    // Use a more vibrant color scheme
                    int color = getEnhancedHeatMapColor(normalizedValue);

                    // Draw a wider rectangle
                    paint.setColor(color);
                    canvas.drawRect(
                            j * scaleX,
                            i * scaleY,
                            (j + 1) * scaleX,
                            (i + 1) * scaleY,
                            paint
                    );
                }
            }
        }

        // Draw guide lines to help see the structure
        paint.setColor(Color.GRAY);
        paint.setStrokeWidth(2);
        for (int j = 1; j < width; j++) {
            canvas.drawLine(j * scaleX, 0, j * scaleX, height * scaleY, paint);
        }

        // Draw column labels
        for (int j = 0; j < width; j++) {
            canvas.drawText("" + j, j * scaleX + 10, 50, textPaint);
        }

        // Draw debug info
        canvas.drawText("Max value: " + maxValue, 10, height * scaleY - 20, textPaint);
        // With this fixed version:
        String velocityStr = velocityText.getText().toString();
        float velocityValue = 0;
        try {
            // Extract the number from "Velocity: X.XX m/s"
            if (velocityStr.contains("Velocity:")) {
                velocityStr = velocityStr.replace("Velocity:", "").replace("m/s", "").trim();
                velocityValue = Float.parseFloat(velocityStr);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing velocity: " + velocityStr, e);
        }

        canvas.drawText("Velocity: " + String.format("%.2f m/s", velocityValue),
                10, height * scaleY - 80, textPaint);

        // Update image view with larger dimensions
        // Get the current LayoutParams (assuming the parent is ConstraintLayout)
        android.view.ViewGroup.LayoutParams currentParams = imageView.getLayoutParams();

        // Check if it's already the correct type, otherwise create new ones
        // (Though ideally it should already be ConstraintLayout.LayoutParams if defined in XML)
        if (currentParams instanceof androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) {
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams clParams =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) currentParams;
            clParams.width = width * scaleX;
            clParams.height = height * scaleY;
            imageView.setLayoutParams(clParams);
        } else {
            // Fallback or error handling if the parent isn't a ConstraintLayout unexpectedly.
            // This might indicate an issue in your XML layout.
            // For a simple fix assuming it IS a ConstraintLayout, you could create new params:
            Log.w(TAG, "ImageView LayoutParams were not ConstraintLayout.LayoutParams. Creating new ones.");
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams newParams =
                    new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(width * scaleX, height * scaleY);

            // Important: You might need to copy constraints from the old params
            // or set default constraints if creating brand new params like this.
            // For example, to constrain it to the parent:
            newParams.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
            newParams.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
            newParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;
            newParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID;

            imageView.setLayoutParams(newParams);
        }

        // Request layout update after changing params
        imageView.requestLayout();

        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(bitmap);

        Log.d(TAG, "Displaying range-Doppler image: " + width + "x" + height +
                " (scaled to " + (width * scaleX) + "x" + (height * scaleY) + ")");
    }

    // In MainActivity.java, modify the displayPhysicalImage method

    /**
     * Displays the physical space image with proper dimensions and visualization
     */
    /**
     * Enhanced method to display physical space image with better scaling and visibility
     */
    private void displayPhysicalImage(float[][] physicalImage) {
        if (physicalImage == null || physicalImage.length == 0) return;

        int height = physicalImage.length;
        int width = physicalImage[0].length;

        // Log original dimensions for debugging
        Log.d(TAG, "Processing physical image: " + width + "x" + height);

        // Safety check - ensure non-zero dimensions
        if (height <= 0 || width <= 0) {
            Log.e(TAG, "Invalid physical image dimensions");
            return;
        }

        // Check if the image is properly oriented (wider than tall for most objects)
        // If not, transpose the matrix for proper display
        boolean needsTranspose = (width < height && width < 10);

        if (needsTranspose) {
            Log.d(TAG, "Transposing image for better display (original was " + width + "x" + height + ")");
            float[][] transposed = new float[width][height];
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    transposed[j][i] = physicalImage[i][j];
                }
            }
            physicalImage = transposed;
            int temp = width;
            width = height;
            height = temp;
        }

        // Apply larger scaling factors for better visualization
        // Since we have a very wide but short image (256x4), we need to emphasize the height
        int scaleX = 4;
        int scaleY = 30;  // Much larger vertical scaling to make the image more visible
        int scaledWidth = width * scaleX;
        int scaledHeight = height * scaleY;

        Log.d(TAG, "Using scaling factors: " + scaleX + "x, " + scaleY + "y (scaled size: " +
                scaledWidth + "x" + scaledHeight + ")");

        try {
            // Create a bitmap of the final scaled size directly
            Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // Fill with different background color for better visibility
            canvas.drawColor(Color.DKGRAY);  // Dark gray background instead of black

            // Find max value for normalization
            float maxValue = 0.001f; // Small non-zero default to avoid division by zero
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    maxValue = Math.max(maxValue, physicalImage[i][j]);
                }
            }

            // Enhance contrast for better visibility
            float contrastFactor = 2.0f; // Increase for more contrast
            maxValue = maxValue / contrastFactor;

            Log.d(TAG, "Image max value: " + maxValue);

            // Create paint for drawing
            Paint paint = new Paint();
            paint.setStyle(Paint.Style.FILL);

            // Check if the image contains non-zero data
            boolean hasData = false;

            // Draw the image using rects instead of points for better performance and visibility
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    // Normalize the value
                    float normalizedValue = Math.min(1.0f, physicalImage[i][j] / maxValue);

                    if (normalizedValue > 0.01f) {
                        hasData = true;
                    }

                    // Use enhanced heat map color
                    int color = getEnhancedHeatMapColor(normalizedValue);

                    // Calculate the rect for this "pixel"
                    int left = j * scaleX;
                    int top = i * scaleY;
                    int right = left + scaleX;
                    int bottom = top + scaleY;

                    // Draw a filled rectangle for each "pixel"
                    paint.setColor(color);
                    canvas.drawRect(left, top, right, bottom, paint);
                }
            }

            // If no data, draw a warning message in the center
            if (!hasData) {
                Log.w(TAG, "No visible data in image - displaying warning");
                canvas.drawColor(Color.DKGRAY); // Clear to dark gray
                paint.setColor(Color.YELLOW);   // Bright text color
                paint.setTextSize(40);          // Larger text
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("No signal detected", scaledWidth/2, scaledHeight/2, paint);
            }

            // Draw a white border around the image for better visibility
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
            canvas.drawRect(0, 0, scaledWidth-1, scaledHeight-1, paint);

            // Update ImageView size and constraints
            android.view.ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();

            // Set minimum height to ensure the image is visible
            if (layoutParams instanceof android.view.ViewGroup.MarginLayoutParams) {
                // Make sure the height is at least 200dp for visibility
                int minHeightInPixels = (int) (200 * getResources().getDisplayMetrics().density);
                int desiredHeight = Math.max(scaledHeight, minHeightInPixels);

                // Update layout if needed
                if (layoutParams.height != desiredHeight) {
                    layoutParams.height = desiredHeight;
                    imageView.setLayoutParams(layoutParams);
                }
            }

            // Update ImageView
            imageView.setImageBitmap(bitmap);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

            // Add a border to the ImageView itself for debugging
            imageView.setBackgroundColor(Color.rgb(50, 50, 50));

            Log.d(TAG, "Successfully displayed physical space image: " + width + "x" + height +
                    " (scaled to " + scaledWidth + "x" + scaledHeight + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error displaying physical image", e);

            // Fallback to a simple error message
            try {
                Bitmap errorBitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(errorBitmap);
                canvas.drawColor(Color.RED);

                Paint paint = new Paint();
                paint.setColor(Color.WHITE);
                paint.setTextSize(24);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Error displaying image", 200, 100, paint);

                imageView.setImageBitmap(errorBitmap);
            } catch (Exception e2) {
                Log.e(TAG, "Error creating error bitmap", e2);
            }
        }
    }

    // Add this method to MainActivity.java to create a more detailed visualizer

    /**
     * Creates a high-resolution heat map visualization of the range-Doppler image
     * This visualization is more detailed than the transposed image
     */
    private void displayDetailedHeatMap(float[][] rangeDopplerImage) {
        if (rangeDopplerImage == null || rangeDopplerImage.length == 0) return;

        int height = rangeDopplerImage.length;
        int width = rangeDopplerImage[0].length;

        Log.d(TAG, "Creating detailed heat map: " + width + "x" + height);

        // Use larger scaling factors for a more detailed view
        int scaleX = 4;
        int scaleY = 4;

        // For very tall, narrow images (e.g., 16x256 after our modification)
        // we might want to display it in landscape orientation
        boolean useLandscapeOrientation = height > width * 2;

        int scaledWidth, scaledHeight;
        if (useLandscapeOrientation) {
            // Swap dimensions for landscape display
            scaledWidth = height * scaleY;
            scaledHeight = width * scaleX;
        } else {
            scaledWidth = width * scaleX;
            scaledHeight = height * scaleY;
        }

        // Create a bitmap with appropriate size
        Bitmap bitmap = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.DKGRAY);

        // Find max value for normalization
        float maxValue = 0.001f;
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                maxValue = Math.max(maxValue, rangeDopplerImage[i][j]);
            }
        }

        // Apply contrast enhancement
        float contrastFactor = 2.0f;
        maxValue = maxValue / contrastFactor;

        Log.d(TAG, "Heat map max value: " + maxValue);

        // Create paint for drawing
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);

        // Draw the heat map with smoother interpolation
        if (useLandscapeOrientation) {
            // Draw in landscape orientation (transposed)
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    // Normalize the value
                    float normalizedValue = Math.min(1.0f, rangeDopplerImage[i][j] / maxValue);

                    // Use enhanced heat map color
                    int color = getEnhancedHeatMapColor(normalizedValue);

                    // Draw with smooth interpolation between pixels
                    paint.setColor(color);

                    // In landscape mode, i becomes x coordinate and j becomes y
                    int left = i * scaleY;
                    int top = j * scaleX;
                    int right = left + scaleY;
                    int bottom = top + scaleX;

                    canvas.drawRect(left, top, right, bottom, paint);
                }
            }
        } else {
            // Draw in normal orientation
            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    // Normalize the value
                    float normalizedValue = Math.min(1.0f, rangeDopplerImage[i][j] / maxValue);

                    // Use enhanced heat map color
                    int color = getEnhancedHeatMapColor(normalizedValue);

                    // Draw with smooth interpolation between pixels
                    paint.setColor(color);

                    int left = j * scaleX;
                    int top = i * scaleY;
                    int right = left + scaleX;
                    int bottom = top + scaleY;

                    canvas.drawRect(left, top, right, bottom, paint);
                }
            }
        }

        // Draw a grid overlay to show the pixel structure
        paint.setColor(Color.argb(50, 255, 255, 255)); // Translucent white
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);

        if (useLandscapeOrientation) {
            // Draw grid lines for landscape orientation
            for (int i = 0; i <= height; i++) {
                canvas.drawLine(i * scaleY, 0, i * scaleY, scaledHeight, paint);
            }
            for (int j = 0; j <= width; j++) {
                canvas.drawLine(0, j * scaleX, scaledWidth, j * scaleX, paint);
            }
        } else {
            // Draw grid lines for normal orientation
            for (int i = 0; i <= height; i++) {
                canvas.drawLine(0, i * scaleY, scaledWidth, i * scaleY, paint);
            }
            for (int j = 0; j <= width; j++) {
                canvas.drawLine(j * scaleX, 0, j * scaleX, scaledHeight, paint);
            }
        }

        // Draw a border around the image
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(4);
        canvas.drawRect(0, 0, scaledWidth-1, scaledHeight-1, paint);

        // Draw labels showing coordinates
        paint.setColor(Color.WHITE);
        paint.setTextSize(24);

        // Ensure the image view has enough size to display the full heat map
        android.view.ViewGroup.LayoutParams layoutParams = imageView.getLayoutParams();

        // Set minimum height to ensure the image is visible
        if (layoutParams instanceof android.view.ViewGroup.MarginLayoutParams) {
            // Make sure the height is sufficient
            int minHeightInPixels = (int) (200 * getResources().getDisplayMetrics().density);
            int desiredHeight = Math.max(scaledHeight, minHeightInPixels);

            // Update layout if needed
            if (layoutParams.height != desiredHeight) {
                layoutParams.height = desiredHeight;
                imageView.setLayoutParams(layoutParams);
            }
        }

        // Update ImageView
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // Add a border to the ImageView itself for debugging
        imageView.setBackgroundColor(Color.rgb(50, 50, 50));

        Log.d(TAG, "Successfully displayed detailed heat map: " +
                (useLandscapeOrientation ? "transposed " : "") +
                scaledWidth + "x" + scaledHeight);
    }

// Then in onResultReady, modify to use this improved visualization:

    @Override
    public void onResultReady(SondarProcessor.SondarResult result) {
        runOnUiThread(() -> {
            // Display velocity
            float velocity = (float)result.getVelocity();
            velocityText.setText(String.format("Velocity: %.2f m/s", velocity));

            // Store distance for motion parameter estimation
            double distance = calculateDistanceFromVelocity(velocity);
            distanceList.add(distance);

            // Get the range-Doppler image
            float[][] rangeDopplerImage = result.getRangeDopplerImage();

            // Only proceed with Phase 3 processing if we have enough data
            if (rangeDopplerImage != null && distanceList.size() >= 3) {
                // Step 1: Convert to physical space
                double[] distanceArray = distanceList.stream().mapToDouble(d -> d).toArray();
                float[][] physicalImage = imageConverter.convertToPhysicalSpace(rangeDopplerImage, distanceArray);

                // Step 2: Calculate object size
                if (physicalImage != null) {
                    double[] size = imageConverter.extractSize(physicalImage, 0.3f);
                    sizeText.setText(String.format("Size: %.1f x %.1f mm", size[0], size[1]));

                    // Step 3: Recognize shape
                    ShapeRecognizer.ShapeType shape = shapeRecognizer.recognizeShape(physicalImage, 0.3f);
                    shapeText.setText("Shape: " + shape.name());

                    // Instead of just displaying the physical image, use our detailed heatmap
                    // for better visualization
                    displayDetailedHeatMap(physicalImage);
                } else {
                    // If physical image conversion failed, display the range-Doppler image
                    displayDetailedHeatMap(rangeDopplerImage);
                }
            } else {
                // If we don't have enough data yet, display the range-Doppler image as a heatmap
                displayDetailedHeatMap(rangeDopplerImage);
            }
        });
    }

    // Enhanced heat map with more vibrant colors
    private int getEnhancedHeatMapColor(float value) {
        // Clamp value between 0 and 1
        value = Math.max(0, Math.min(1, value));

        float r, g, b;

        // Use a more vibrant color scheme
        if (value < 0.2f) {
            // Dark blue to blue
            r = 0;
            g = 0;
            b = value * 5;
        } else if (value < 0.4f) {
            // Blue to cyan
            r = 0;
            g = (value - 0.2f) * 5;
            b = 1;
        } else if (value < 0.6f) {
            // Cyan to green
            r = 0;
            g = 1;
            b = 1 - (value - 0.4f) * 5;
        } else if (value < 0.8f) {
            // Green to yellow
            r = (value - 0.6f) * 5;
            g = 1;
            b = 0;
        } else {
            // Yellow to red
            r = 1;
            g = 1 - (value - 0.8f) * 5;
            b = 0;
        }

        return Color.rgb((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    /**
     * Converts a normalized value (0-1) to a heat map color.
     * Fixed double to float type conversions with explicit casts
     */
    private int getHeatMapColor(float value) {
        // Clamp value
        value = Math.max(0, Math.min(1, value));

        float r, g, b;

        // Blue (cold) to Red (hot) gradient
        if (value < 0.25f) {
            r = 0;
            g = 4 * value;
            b = 1;
        } else if (value < 0.5f) {
            r = 0;
            g = 1;
            b = 1 - 4 * (value - 0.25f);
        } else if (value < 0.75f) {
            r = 4 * (value - 0.5f);
            g = 1;
            b = 0;
        } else {
            r = 1;
            g = 1 - 4 * (value - 0.75f);
            b = 0;
        }

        return Color.rgb((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }
}