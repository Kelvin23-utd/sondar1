package com.hccps.xiao.itemdector.sondar.app;

import android.util.Log;

import com.hccps.xiao.itemdector.sondar.app.audio.SondarAudioManager;
import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator;
import com.hccps.xiao.itemdector.sondar.app.audio.ChirpGenerator.Complex;
import com.hccps.xiao.itemdector.sondar.app.processing.SignalProcessor;
import com.hccps.xiao.itemdector.sondar.app.signal.Downconverter;
import com.hccps.xiao.itemdector.sondar.app.signal.EchoAligner;
import com.hccps.xiao.itemdector.sondar.app.signal.FFTProcessor;
import com.hccps.xiao.itemdector.sondar.app.utils.SignalLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main processor class for the SONDAR system.
 * Coordinates audio capture, signal processing, and object detection.
 * Modified to use a single-thread executor for processing.
 */
public class SondarProcessor implements SondarAudioManager.AudioDataCallback {
    private static final String TAG = "SONDAR_Processor";

    // Components
    private final SondarAudioManager audioManager;
    private final ChirpGenerator chirpGenerator;
    private final SignalProcessor signalProcessor;
    private final EchoAligner echoAligner;
    private final Downconverter downconverter;
    private final FFTProcessor fftProcessor;

    // Processing state
    private short[] chirpSignal;
    private volatile boolean isProcessing = false; // Made volatile
    private SondarResultCallback resultCallback;

    // Echo data buffers (Consider thread-safety if accessed outside processing thread)
    private Complex[][] lastTimeFreqImage = null;
    private float[][] lastRangeDopplerImage = null;

    // Executor for handling processing tasks sequentially
    private ExecutorService processingExecutor;
    // Separate executor/thread for chirp emission
    private volatile boolean isEmitting = false;
    private Thread chirpEmitterThread;


    public SondarProcessor() {
        audioManager = new SondarAudioManager();
        chirpGenerator = new ChirpGenerator();
        signalProcessor = new SignalProcessor();
        echoAligner = new EchoAligner();
        downconverter = new Downconverter();
        fftProcessor = new FFTProcessor();

        // Generate chirp signal
        chirpSignal = chirpGenerator.generateChirp();
    }

    /**
     * Starts the SONDAR sensing system.
     *
     * @param callback Callback to receive processed results
     */
    public void start(SondarResultCallback callback) {
        if (isProcessing) return;
        Log.d(TAG, "Starting SONDAR Processor...");

        this.resultCallback = callback;
        isProcessing = true;

        // Initialize a single-thread executor for processing
        processingExecutor = Executors.newSingleThreadExecutor();

        // Start audio recording
        audioManager.startRecording(this);

        // Start chirp emission on a separate thread
        isEmitting = true;
        chirpEmitterThread = new Thread(() -> {
            while (isEmitting) {
                if (!isProcessing) break; // Check if processing stopped overall
                audioManager.playChirp(chirpSignal);
                try {
                    // Wait before sending next chirp (10 Hz sensing rate)
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Chirp emitter thread interrupted.");
                    Thread.currentThread().interrupt(); // Restore interrupt status
                    break; // Exit loop if interrupted
                }
            }
            Log.d(TAG, "Chirp emitter thread finished.");
        });
        chirpEmitterThread.start();
        Log.d(TAG, "SONDAR Processor Started.");
    }

    /**
     * Stops the SONDAR sensing system.
     */
    public void stop() {
        if (!isProcessing) return;
        Log.d(TAG, "Stopping SONDAR Processor...");
        isProcessing = false; // Signal processing tasks to stop checking callback

        // Stop chirp emission first
        isEmitting = false;
        if (chirpEmitterThread != null) {
            chirpEmitterThread.interrupt(); // Interrupt sleep
            try {
                chirpEmitterThread.join(500); // Wait briefly for it to finish
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            chirpEmitterThread = null;
        }

        // Stop audio recording
        audioManager.stopRecording();

        // Shutdown the processing executor gracefully
        if (processingExecutor != null) {
            processingExecutor.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!processingExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    Log.w(TAG, "Processing executor did not terminate gracefully, forcing shutdown.");
                    processingExecutor.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!processingExecutor.awaitTermination(1, TimeUnit.SECONDS))
                        Log.e(TAG, "Processing executor did not terminate after forced shutdown.");
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                processingExecutor.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
            processingExecutor = null;
        }
        Log.d(TAG, "SONDAR Processor Stopped.");
    }

    /**
     * Releases resources used by the SONDAR system.
     */
    public void release() {
        Log.d(TAG, "Releasing SONDAR Processor resources...");
        // Ensure stop is called first
        if (isProcessing) {
            stop();
        }
        // Release audio manager resources
        audioManager.release();
        Log.d(TAG, "SONDAR Processor resources released.");
    }

    /**
     * Callback for received audio data.
     * Submits the processing task to the executor service.
     */
    @Override
    public void onAudioDataReceived(short[] data, int size) {
        // Ignore data if not processing or executor is shut down/terminated
        if (!isProcessing || processingExecutor == null || processingExecutor.isShutdown()) {
            return;
        }

        // Crucial: Make a copy of the data buffer, as the original
        // buffer might be reused by AudioRecord immediately after this callback returns.
        final short[] dataCopy = new short[size];
        System.arraycopy(data, 0, dataCopy, 0, size);

        // Submit the processing task to the executor
        try {
            processingExecutor.submit(() -> {
                // Double-check processing flag inside the task
                if (!isProcessing || resultCallback == null) return;

                long startTime = System.nanoTime();
                Log.d(TAG, "Processing audio data: size=" + dataCopy.length);
                try {
                    // --- Existing Processing Pipeline ---
                    // Log the raw signal for the current sample
                    int sampleIndex = getSampleIndex();
                    SignalLogger.logRawSignal(dataCopy, sampleIndex);

                    // Step 1: Preprocess the signal (bandpass filtering)
                    Complex[] preprocessed = signalProcessor.preprocess(dataCopy);
                    SignalLogger.logComplexSignal(preprocessed, sampleIndex, "preprocessed");

                    // Step 2: Align echoes (compensate for Doppler effects)
                    Complex[] aligned = echoAligner.alignEchoes(preprocessed);
                    SignalLogger.logComplexSignal(aligned, sampleIndex, "aligned");

                    // Log 2D representation of aligned signal
                    float[][] alignedImage = convertComplexToFloat2D(aligned);
                    SignalLogger.log2DImage(alignedImage, sampleIndex, "echo_aligned");

                    double velocity = echoAligner.getEstimatedVelocity();
                    SignalLogger.logVelocityEstimation(velocity, velocity, 0.0, sampleIndex);

                    // Step 3: Down-convert to baseband
                    Complex[] baseband = downconverter.downconvert(aligned);
                    SignalLogger.logComplexSignal(baseband, sampleIndex, "baseband");

                    // Log 2D representation of baseband signal
                    float[][] basebandImage = convertComplexToFloat2D(baseband);
                    SignalLogger.log2DImage(basebandImage, sampleIndex, "downconverted");

                    // Step 4: Create frequency-time image
                    Complex[][] timeFreqImage = downconverter.createFrequencyTimeImage(baseband);

                    // Log frequency-time image
                    float[][] freqTimeFloatImage = convertComplex2DToFloat2D(timeFreqImage);
                    SignalLogger.log2DImage(freqTimeFloatImage, sampleIndex, "freq_time_image");

                    // Step 5: Remove background
                    Complex[][] foreground = signalProcessor.removeBackground(timeFreqImage);

                    // Log foreground image
                    float[][] foregroundFloatImage = convertComplex2DToFloat2D(foreground);
                    SignalLogger.log2DImage(foregroundFloatImage, sampleIndex, "foreground");

                    // Step 6: Compute range-Doppler image
                    float[][] rangeDopplerImage = downconverter.computeRangeDopplerImage(foreground);

                    // Log range-Doppler image
                    SignalLogger.log2DImage(rangeDopplerImage, sampleIndex, "range_doppler");

                    // Step 7: Apply phase compensation
                    float[][] compensated = signalProcessor.compensatePhase(rangeDopplerImage, velocity);

                    // Log final compensated image
                    SignalLogger.log2DImage(compensated, sampleIndex, "final_image");

                    // --- End of Pipeline ---

                    // Store results (Consider if access needs synchronization)
                    // If getLast... methods are called from other threads, synchronization is needed.
                    // For now, assuming they are only called after onResultReady on UI thread.
                    lastTimeFreqImage = foreground;
                    lastRangeDopplerImage = compensated;

                    // Create and pass result to the callback
                    SondarResult result = new SondarResult(compensated, velocity);
                    resultCallback.onResultReady(result); // This callback likely needs to run on UI thread

                    long duration = (System.nanoTime() - startTime) / 1_000_000; // ms
                    Log.d(TAG, String.format("Processing complete: velocity=%.2f m/s, duration=%d ms", velocity, duration));
                } catch (Exception e) {
                    // Catch specific exceptions if possible for better handling
                    Log.e(TAG, "Error during signal processing task: " + e.getMessage(), e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            Log.w(TAG, "Processing task rejected, executor likely shutting down.", e);
        }
    }

    /**
     * Helper method to generate a unique sample index.
     * This could be based on timestamp or a counter.
     */
    private int getSampleIndex() {
        // Simple implementation - could be enhanced for more reliable unique indexes
        return (int)(System.currentTimeMillis() % 10000);
    }

    /**
     * Convert Complex array to 2D float array for visualization
     */
    private float[][] convertComplexToFloat2D(Complex[] signal) {
        float[][] result = new float[1][signal.length];
        for (int i = 0; i < signal.length; i++) {
            result[0][i] = (float) signal[i].magnitude();
        }
        return result;
    }

    /**
     * Convert 2D Complex array to 2D float array for visualization
     */
    private float[][] convertComplex2DToFloat2D(Complex[][] image) {
        if (image == null || image.length == 0) {
            return new float[0][0];
        }

        float[][] result = new float[image.length][image[0].length];
        for (int i = 0; i < image.length; i++) {
            for (int j = 0; j < image[0].length; j++) {
                result[i][j] = (float) image[i][j].magnitude();
            }
        }
        return result;
    }

    /**
     * Gets the last processed time-frequency image.
     * Note: Consider thread-safety if accessed from threads other than where onResultReady is handled.
     */
    public Complex[][] getLastTimeFreqImage() {
        // If accessed concurrently, would need synchronization or volatile/AtomicReference
        return lastTimeFreqImage;
    }

    /**
     * Gets the last processed range-Doppler image.
     * Note: Consider thread-safety if accessed from threads other than where onResultReady is handled.
     */
    public float[][] getLastRangeDopplerImage() {
        // If accessed concurrently, would need synchronization or volatile/AtomicReference
        return lastRangeDopplerImage;
    }

    /**
     * Interface for receiving SONDAR processing results.
     */
    public interface SondarResultCallback {
        /**
         * Called when a new SONDAR result is ready.
         * IMPORTANT: Implementations should typically marshal this call
         * to the main UI thread before updating UI components.
         */
        void onResultReady(SondarResult result);
    }

    /**
     * Class representing SONDAR processing results.
     * Consider making fields final if they aren't modified after creation.
     */
    public static class SondarResult {
        private final float[][] rangeDopplerImage;
        private final double velocity;

        public SondarResult(float[][] rangeDopplerImage, double velocity) {
            // Defensive copy if rangeDopplerImage could be modified externally later
            this.rangeDopplerImage = rangeDopplerImage; // Assuming it won't be modified
            this.velocity = velocity;
        }

        public float[][] getRangeDopplerImage() {
            // Return a copy if the internal array should not be modifiable externally
            return rangeDopplerImage;
        }

        public double getVelocity() {
            return velocity;
        }
    }
}