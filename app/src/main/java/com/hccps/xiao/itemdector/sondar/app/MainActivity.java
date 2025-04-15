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

public class MainActivity extends AppCompatActivity implements SondarProcessor.SondarResultCallback {
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.RECORD_AUDIO
    };

    private SondarProcessor sondarProcessor;
    private Button startStopButton;
    private ImageView imageView;
    private TextView statusText;
    private TextView velocityText;

    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        startStopButton = findViewById(R.id.start_stop_button);
        imageView = findViewById(R.id.image_view);
        statusText = findViewById(R.id.status_text);
        velocityText = findViewById(R.id.velocity_text);

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

        // Check permissions
        if (!hasPermissions()) {
            requestPermissions();
        }
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

        sondarProcessor.start(this);
        isRunning = true;
        startStopButton.setText("Stop SONDAR");
        statusText.setText("SONDAR Running");
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

    @Override
    public void onResultReady(SondarProcessor.SondarResult result) {
        runOnUiThread(() -> {
            // Fixed type conversion issue - convert double to float with explicit cast
            velocityText.setText(String.format("Velocity: %.2f m/s", (float)result.getVelocity()));

            // Visualize the range-Doppler image
            displayRangeDopplerImage(result.getRangeDopplerImage());
        });
    }

    private void displayRangeDopplerImage(float[][] image) {
        if (image == null || image.length == 0) return;

        int height = image.length;    // 256
        int width = image[0].length;  // 4

        // Use much larger scaling factors
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
            Log.e("SONDAR_DEBUG", "Error parsing velocity: " + velocityStr, e);
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
            Log.w("SONDAR_DEBUG", "ImageView LayoutParams were not ConstraintLayout.LayoutParams. Creating new ones.");
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

            // Alternatively, log an error and don't change params if the type is wrong:
            // Log.e("SONDAR_DEBUG", "Cannot set LayoutParams: Parent is not a ConstraintLayout or params are incorrect type.");
        }

// Request layout update after changing params
        imageView.requestLayout();

        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(bitmap);

        Log.d("SONDAR_DEBUG", "Displaying range-Doppler image: " + width + "x" + height +
                " (scaled to " + (width * scaleX) + "x" + (height * scaleY) + ")");
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