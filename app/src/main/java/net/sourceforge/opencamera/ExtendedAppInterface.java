package net.sourceforge.opencamera;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.TextView;

import com.googleresearch.capturesync.SoftwareSyncController;
import com.googleresearch.capturesync.softwaresync.SoftwareSyncLeader;

import net.sourceforge.opencamera.cameracontroller.CameraController;
import net.sourceforge.opencamera.cameracontroller.YuvImageUtils;
import net.sourceforge.opencamera.preview.Preview;
import net.sourceforge.opencamera.sensorlogging.FlashController;
import net.sourceforge.opencamera.sensorlogging.RawSensorInfo;
import net.sourceforge.opencamera.sensorlogging.VideoFrameInfo;
import net.sourceforge.opencamera.sensorlogging.VideoPhaseInfo;
import net.sourceforge.opencamera.ui.MainUI;
import net.sourceforge.opencamera.ui.ManualSeekbars;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Extended implementation of ApplicationInterface, adds raw sensor recording layer and RecSync
 * controls to the interface.
 */
public class ExtendedAppInterface extends MyApplicationInterface {
    private static final String TAG = "ExtendedAppInterface";
    private static final int SENSOR_FREQ_DEFAULT_PREF = 0;

    private final RawSensorInfo mRawSensorInfo;
    private final FlashController mFlashController;
    private final SharedPreferences mSharedPreferences;
    private final MainActivity mMainActivity;
    private final YuvImageUtils mYuvUtils;
    private final TextView mSyncStatusText;
    private final Handler sendSettingsHandler = new Handler();

    private SoftwareSyncController softwareSyncController;
    private ApplySettingsTask applySettingsTask = null;
    private BroadcastReceiver connectionStatusChecker = null;

    ExtendedAppInterface(MainActivity mainActivity, Bundle savedInstanceState) {
        super(mainActivity, savedInstanceState);
        mRawSensorInfo = mainActivity.getRawSensorInfoManager();
        mMainActivity = mainActivity;
        mFlashController = new FlashController(mainActivity);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity);
        // We create it only once here (not during the video) as it is a costly operation
        // (instantiates RenderScript object)
        mYuvUtils = new YuvImageUtils(mainActivity);
        mSyncStatusText = new TextView(mMainActivity);
    }

    public VideoFrameInfo setupFrameInfo() throws IOException {
        return new VideoFrameInfo(
                getLastVideoDate(), mMainActivity, getSaveFramesPref(), getVideoPhaseInfoReporter()
        );
    }

    public FlashController getFlashController() {
        return mFlashController;
    }

    public SoftwareSyncController getSoftwareSyncController() {
        return softwareSyncController;
    }

    public YuvImageUtils getYuvUtils() {
        return mYuvUtils;
    }

    public BlockingQueue<VideoPhaseInfo> getVideoPhaseInfoReporter() {
        return mMainActivity.getPreview().getVideoPhaseInfoReporter();
    }

    /**
     * Provides the current leader-client status of RecSync.
     *
     * @return a {@link TextView} describing the current status.
     */
    public TextView getSyncStatusText() {
        return mSyncStatusText;
    }

    public boolean getIMURecordingPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.IMURecordingPreferenceKey, false);
    }

    public boolean getRemoteRecControlPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.RemoteRecControlPreferenceKey, false);
    }

    public boolean getSaveFramesPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.saveFramesPreferenceKey, false);
    }

    public boolean getEnableRecSyncPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.EnableRecSyncPreferenceKey, false);
    }

    /**
     * Retrieves gyroscope and accelerometer sample rate preference and converts it to number.
     */
    public int getSensorSampleRatePref(String prefKey) {
        String sensorSampleRateString = mSharedPreferences.getString(
                prefKey,
                String.valueOf(SENSOR_FREQ_DEFAULT_PREF)
        );
        int sensorSampleRate = SENSOR_FREQ_DEFAULT_PREF;
        try {
            if (sensorSampleRateString != null)
                sensorSampleRate = Integer.parseInt(sensorSampleRateString);
        } catch (NumberFormatException exception) {
            if (MyDebug.LOG)
                Log.e(TAG, "Sample rate invalid format: " + sensorSampleRateString);
        }
        return sensorSampleRate;
    }

    private boolean getAccelPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.AccelPreferenceKey, true);
    }

    private boolean getGyroPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.GyroPreferenceKey, true);
    }

    private boolean getMagneticPref() {
        return mSharedPreferences.getBoolean(PreferenceKeys.MagnetometerPrefKey, true);
    }

    @Override
    void onDestroy() {
        mYuvUtils.close();
        if (applySettingsTask != null) {
            applySettingsTask.cancel(true);
        }
        stopSoftwareSync();
        super.onDestroy();
    }

    @Override
    public void startingVideo() {
        if (MyDebug.LOG) {
            Log.d(TAG, "starting video");
        }
        if (getIMURecordingPref() && useCamera2() && (getGyroPref() || getAccelPref() || getMagneticPref())) {
            // Extracting sample rates from shared preferences
            try {
                mMainActivity.getPreview().showToast("Starting video with IMU recording...", true);
                startImu(getAccelPref(), getGyroPref(), getMagneticPref(), mLastVideoDate);
                // TODO: add message to strings.xml
            } catch (NumberFormatException e) {
                if (MyDebug.LOG) {
                    Log.e(TAG, "Failed to retrieve the sample rate preference value");
                    e.printStackTrace();
                }
            }
        } else if (getIMURecordingPref() && !useCamera2()) {
            mMainActivity.getPreview().showToast(null, "Not using Camera2API! Can't record in sync with IMU");
            mMainActivity.getPreview().stopVideo(false);
        } else if (getIMURecordingPref() && !(getGyroPref() || getMagneticPref() || getAccelPref())) {
            mMainActivity.getPreview().showToast(null, "Requested IMU recording but no sensors were enabled");
            mMainActivity.getPreview().stopVideo(false);
        }

        if (getVideoFlashPref()) {
            try {
                mFlashController.startRecording(mLastVideoDate);
            } catch (IOException e) {
                Log.e(TAG, "Failed to start flash controller");
                e.printStackTrace();
            }
        }

        super.startingVideo();
    }

    @Override
    public void stoppingVideo() {
        if (MyDebug.LOG) {
            Log.d(TAG, "stopping video");
        }
        if (mRawSensorInfo.isRecording()) {
            mRawSensorInfo.stopRecording();
            mRawSensorInfo.disableSensors();

            // TODO: add message to strings.xml
            mMainActivity.getPreview().showToast("Stopping video with IMU recording...", true);
        }

        if (mFlashController.isRecording()) {
            mFlashController.stopRecording();
        }

        super.stoppingVideo();
    }

    public void onFrameInfoRecordingFailed() {
        mMainActivity.getPreview().showToast(null, "Couldn't write frame timestamps");
    }

    public void startImu(boolean wantAccel, boolean wantGyro, boolean wantMagnetic, Date currentDate) {
        if (wantAccel) {
            int accelSampleRate = getSensorSampleRatePref(PreferenceKeys.AccelSampleRatePreferenceKey);
            if (!mRawSensorInfo.enableSensor(Sensor.TYPE_ACCELEROMETER, accelSampleRate)) {
                mMainActivity.getPreview().showToast(null, "Accelerometer unavailable");
            }
        }
        if (wantGyro) {
            int gyroSampleRate = getSensorSampleRatePref(PreferenceKeys.GyroSampleRatePreferenceKey);
            if (!mRawSensorInfo.enableSensor(Sensor.TYPE_GYROSCOPE, gyroSampleRate)) {
                mMainActivity.getPreview().showToast(null, "Gyroscope unavailable");
            }
        }
        if (wantMagnetic) {
            int magneticSampleRate = getSensorSampleRatePref(PreferenceKeys.MagneticSampleRatePreferenceKey);
            if (!mRawSensorInfo.enableSensor(Sensor.TYPE_MAGNETIC_FIELD, magneticSampleRate)) {
                mMainActivity.getPreview().showToast(null, "Magnetometer unavailable");
            }
        }

        //mRawSensorInfo.startRecording(mMainActivity, mLastVideoDate, get Pref(), getAccelPref())
        Map<Integer, Boolean> wantSensorRecordingMap = new HashMap<>();
        wantSensorRecordingMap.put(Sensor.TYPE_ACCELEROMETER, getAccelPref());
        wantSensorRecordingMap.put(Sensor.TYPE_GYROSCOPE, getGyroPref());
        wantSensorRecordingMap.put(Sensor.TYPE_MAGNETIC_FIELD, getMagneticPref());
        mRawSensorInfo.startRecording(mMainActivity, currentDate, wantSensorRecordingMap);
    }

    /**
     * Starts RecSync by instantiating a {@link SoftwareSyncController}. If one is already running
     * then it is closed and a new one is initialized.
     * <p>
     * Starts pick Wi-Fi activity if neither Wi-Fi nor hotspot is enabled.
     */
    public void startSoftwareSync() {
        // Start softwaresync, close it first if it's already running.
        if (isSoftwareSyncRunning()) {
            stopSoftwareSync();
        }

        try {
            softwareSyncController =
                    new SoftwareSyncController(mMainActivity, null, mSyncStatusText);
        } catch (IllegalStateException e) {
            // Wi-Fi and hotspot are disabled.
            Log.e(TAG, "Couldn't start SoftwareSync: Wi-Fi and hotspot are disabled.");
            disableRecSyncSetting();
            showSimpleAlert("Cannot start RecSync", "Enable either Wi-Fi or hotspot for RecSync to be able to start.");
            return;
        }

        // Listen for wifi or hotpot status changes.
        IntentFilter intentFilter = new IntentFilter();
        if (softwareSyncController.isLeader()) {
            // Need to get WIFI_AP_STATE_CHANGED_ACTION hidden in WiFiManager.
            String action;
            WifiManager wifiManager = (WifiManager) mMainActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            try {
                Field field = wifiManager.getClass().getDeclaredField("WIFI_AP_STATE_CHANGED_ACTION");
                action = (String) field.get(wifiManager);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException("Cannot get WIFI_AP_STATE_CHANGED_ACTION value from WifiManager.", e);
            }
            intentFilter.addAction(action);
            connectionStatusChecker = new HotspotStatusChecker();
        } else {
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            connectionStatusChecker = new WifiStatusChecker();
        }
        mMainActivity.registerReceiver(connectionStatusChecker, intentFilter);
    }

    private class HotspotStatusChecker extends BroadcastReceiver {
        private static final String TAG = "HotspotStatusChecker";

        private final int WIFI_AP_STATE_ENABLED;

        HotspotStatusChecker() {
            // Need to get WIFI_AP_STATE_ENABLED hidden in WifiManager
            WifiManager wifiManager = (WifiManager) mMainActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            try {
                Field field = wifiManager.getClass().getDeclaredField("WIFI_AP_STATE_ENABLED");
                WIFI_AP_STATE_ENABLED = (int) field.get(wifiManager);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException("Cannot get WIFI_AP_STATE_ENABLED value from WifiManager.", e);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 0);
            if (state != WIFI_AP_STATE_ENABLED) {
                Log.e(TAG, "Hotspot has been stopped, disabling RecSync.");
                disableRecSyncSetting();
                showSimpleAlert("Hotspot was stopped", "Stopping RecSync. Enable either Wi-Fi or hotspot and re-enable RecSync in the settings.");
            }
        }
    }

    private class WifiStatusChecker extends BroadcastReceiver {
        private static final String TAG = "WifiStatusChecker";

        @Override
        public void onReceive(Context context, Intent intent) {
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo == null || networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
                Log.e(TAG, "Wi-Fi connection has been closed, disabling RecSync.");
                disableRecSyncSetting();
                showSimpleAlert("Wi-Fi was stopped", "Stopping RecSync. Enable either Wi-Fi or hotspot and re-enable RecSync in the settings.");
            }
        }
    }

    private void disableRecSyncSetting() {
        if (!mMainActivity.isCameraInBackground()) {
            mMainActivity.openSettings();
        }
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putBoolean(PreferenceKeys.EnableRecSyncPreferenceKey, false);
        editor.apply();
        stopSoftwareSync(); // Preference wasn't clicked so this won't be triggered
        getDrawPreview().updateSettings(); // Because we cache the enable RecSync setting
    }

    private void showSimpleAlert(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mMainActivity);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(android.R.string.ok, null);

        AlertDialog alert = builder.create();
        mMainActivity.showAlert(alert);
    }

    /**
     * Closes {@link SoftwareSyncController} if one is running.
     */
    public void stopSoftwareSync() {
        if (isSoftwareSyncRunning()) {
            mMainActivity.unregisterReceiver(connectionStatusChecker);
            connectionStatusChecker = null;
            softwareSyncController.close();
            softwareSyncController = null;
        }
    }

    /**
     * Whether SoftwareSync is currently running (i.e. {@link SoftwareSyncController} is
     * initialized).
     *
     * @return true if SoftwareSync is currently running, false if it is not.
     */
    public boolean isSoftwareSyncRunning() {
        return softwareSyncController != null;
    }

    /**
     * Schedules a broadcast of the current settings to clients.
     *
     * @param settings describes the settings to be broadcast.
     * @throws IllegalStateException if {@link SoftwareSyncController} is not initialized after the
     *                               delay.
     */
    public void scheduleBroadcastSettings(SyncSettingsContainer settings) {
        sendSettingsHandler.removeCallbacks(null);
        sendSettingsHandler.postDelayed(
                () -> {
                    Log.d(TAG, "Broadcasting current settings.");

                    // Send settings to all devices
                    if (softwareSyncController == null) {
                        throw new IllegalStateException("Cannot broadcast settings when RecSync is not running");
                    }
                    ((SoftwareSyncLeader) softwareSyncController.getSoftwareSync())
                            .broadcastRpc(SoftwareSyncController.METHOD_SET_SETTINGS, settings.asString());
                },
                500);
    }

    /**
     * Creates an {@link AsyncTask} to apply the values of the settings received from a leader and
     * lock them. Closes the previous task if it exists and is still running.
     *
     * @param settings describes the settings to be changed and the values to be applied.
     */
    public void applyAndLockSettings(SyncSettingsContainer settings) {
        Log.d(TAG, "Applying and locking settings.");

        // Cancel previous task if it is still running
        if (applySettingsTask != null && applySettingsTask.getStatus() != AsyncTask.Status.FINISHED) {
            applySettingsTask.cancel(true);
        }

        // Create a new task to wait until camera is opened
        applySettingsTask = new ApplySettingsTask(this);
        applySettingsTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, settings);
    }

    private static class ApplySettingsTask extends AsyncTask<SyncSettingsContainer, Void, Void> {
        private static final String TAG = "ApplySettingsTask";

        final private WeakReference<ExtendedAppInterface> extendedAppInterfaceRef;
        final private WeakReference<MainActivity> mainActivityRef;
        final private WeakReference<Preview> previewRef;
        final private WeakReference<MainUI> mainUIRef;

        final private ManualSeekbars manualSeekbars;

        private CameraController cameraController;

        ApplySettingsTask(ExtendedAppInterface extendedAppInterface) {
            // Use weak references so that the task allows MainActivity to be garbage collected
            extendedAppInterfaceRef = new WeakReference<>(extendedAppInterface);
            mainActivityRef = new WeakReference<>(extendedAppInterface.mMainActivity);
            previewRef = new WeakReference<>(extendedAppInterface.mMainActivity.getPreview());
            mainUIRef = new WeakReference<>(extendedAppInterface.mMainActivity.getMainUI());

            manualSeekbars = extendedAppInterface.mMainActivity.getManualSeekbars();
        }

        @Override
        protected Void doInBackground(SyncSettingsContainer... settingsContainers) {
            Log.d(TAG, "doInBackground");

            if (settingsContainers.length != 1) {
                throw new IllegalArgumentException(
                        String.format("One SyncSettingsContainer must be passed but was %d", settingsContainers.length)
                );
            }
            final SyncSettingsContainer settings = settingsContainers[0];

            final ExtendedAppInterface extendedAppInterface = extendedAppInterfaceRef.get();
            final MainActivity mainActivity = mainActivityRef.get();
            final Preview preview = previewRef.get();
            final MainUI mainUI = mainUIRef.get();
            if (extendedAppInterface == null || mainActivity == null || preview == null || mainUI == null) {
                return null;
            }

            waitForCameraToOpen(); // If camera is not opened wait until it is

            // Close some UI elements for the changes to be reflected in them
            mainActivity.runOnUiThread(() -> {
                mainUI.closeExposureUI();
                mainUI.closePopup();
                mainUI.destroyPopup();
            });

            syncCaptureFormat(settings);

            cameraController = preview.getCameraController();

            syncExposure(settings);
            if (settings.syncISO) syncISO(settings);
            if (settings.syncWb) syncWb(settings);
            if (settings.syncFlash) syncFlash(settings);
            if (settings.syncFormat) syncFormat(settings);

            extendedAppInterface.getDrawPreview().updateSettings(); // Ensure that the changes get cached
            return null;
        }

        private void waitForCameraToOpen() {
            Preview preview = previewRef.get();
            if (preview == null) return;

            Log.d(TAG, "Waiting for camera to open...");

            while (!preview.openCameraAttempted()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void syncCaptureFormat(SyncSettingsContainer settings) {
            MainActivity mainActivity = mainActivityRef.get();
            Preview preview = previewRef.get();
            if (mainActivity == null || preview == null) return;

            Log.d(TAG, "Syncing settings: capture format");

            if (preview.isVideo() != settings.isVideo) {
                mainActivity.runOnUiThread(() -> mainActivity.clickedSwitchVideo(null));

                waitForCameraToOpen(); // Need to wait until camera gets reopened

                // Need to wait for capture mode to change
                while (settings.isVideo != preview.isVideo()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void syncExposure(SyncSettingsContainer settings) {
            MainActivity mainActivity = mainActivityRef.get();
            Preview preview = previewRef.get();
            if (mainActivity == null || preview == null) return;

            Log.d(TAG, "Syncing settings: exposure");

            if (!preview.isExposureLocked()) {
                mainActivity.runOnUiThread(() -> mainActivity.clickedExposureLock(null));
            }
            preview.setExposureTime(settings.exposure);
            // Reflect the change in the UI
            manualSeekbars.setProgressSeekbarShutterSpeed(
                    mainActivity.findViewById(R.id.exposure_time_seekbar),
                    preview.getMinimumExposureTime(),
                    preview.getMaximumExposureTime(),
                    cameraController.getExposureTime()
            ); // We get exposure from the controller in case the selected value is unsupported
        }

        private void syncISO(SyncSettingsContainer settings) {
            ExtendedAppInterface extendedAppInterface = extendedAppInterfaceRef.get();
            MainActivity mainActivity = mainActivityRef.get();
            Preview preview = previewRef.get();
            MainUI mainUI = mainUIRef.get();
            if (extendedAppInterface == null || mainActivity == null || preview == null || mainUI == null) {
                return;
            }

            Log.d(TAG, "Syncing settings: ISO");

            cameraController.setManualISO(true, settings.iso); // Set ISO to manual (lock it)
            // Reflect the change in the UI
            preview.setISO(settings.iso);
            extendedAppInterface.setISOPref("" + cameraController.getISO()); // So it is not set to auto
            mainActivity.runOnUiThread(() -> {
                mainUI.setupExposureUI();
                mainUI.closeExposureUI();
            });
            manualSeekbars.setProgressSeekbarISO(
                    mainActivity.findViewById(R.id.iso_seekbar),
                    preview.getMinimumISO(),
                    preview.getMaximumISO(),
                    cameraController.getISO()
            ); // We get ISO from the controller in case the selected value is unsupported
        }

        private void syncWb(SyncSettingsContainer settings) {
            ExtendedAppInterface extendedAppInterface = extendedAppInterfaceRef.get();
            MainActivity mainActivity = mainActivityRef.get();
            Preview preview = previewRef.get();
            if (extendedAppInterface == null || mainActivity == null || preview == null) return;

            Log.d(TAG, "Syncing settings: white balance");

            // Lock wb only if the selected mode is not auto
            if (!settings.wbMode.equals(CameraController.WHITE_BALANCE_DEFAULT) && !preview.isWhiteBalanceLocked()) {
                mainActivity.runOnUiThread(() -> mainActivity.clickedWhiteBalanceLock(null));
            }
            // If the selected mode is supported set it, otherwise try to set manual mode
            List<String> supportedValues = preview.getSupportedWhiteBalances();
            if (supportedValues.contains(settings.wbMode)) {
                cameraController.setWhiteBalance(settings.wbMode);
            } else if (supportedValues.contains("manual")) {
                cameraController.setWhiteBalance("manual");
            }
            String resultingWbMode = cameraController.getWhiteBalance();
            extendedAppInterface.setWhiteBalancePref(resultingWbMode); // Reflect the resulting mode in the UI
            // If the resulting mode is manual apply the selected temperature
            if (resultingWbMode.equals("manual")) {
                preview.setWhiteBalanceTemperature(settings.wbTemperature);
                // Reflect the change in the UI
                manualSeekbars.setProgressSeekbarWhiteBalance(
                        mainActivity.findViewById(R.id.white_balance_seekbar),
                        preview.getMinimumWhiteBalanceTemperature(),
                        preview.getMaximumWhiteBalanceTemperature(),
                        cameraController.getWhiteBalanceTemperature()
                ); // We get the temperature from the controller in case the selected value is unsupported
            }
        }

        private void syncFlash(SyncSettingsContainer settings) {
            MainActivity mainActivity = mainActivityRef.get();
            Preview preview = previewRef.get();
            MainUI mainUI = mainUIRef.get();
            if (mainActivity == null || preview == null || mainUI == null) return;

            Log.d(TAG, "Syncing settings: flash mode");

            mainActivity.runOnUiThread(() -> {
                preview.updateFlash(settings.flash);
                mainUI.setPopupIcon(); // Reflect the change in the UI
            });
        }

        private void syncFormat(SyncSettingsContainer settings) {
            MainActivity mainActivity = mainActivityRef.get();
            if (mainActivity == null) return;

            Log.d(TAG, "Syncing settings: file format");

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mainActivity);
            SharedPreferences.Editor editor = sharedPreferences.edit();
            if (settings.isVideo) {
                // Check that the selected format is supported (HEVC and WebM may be not)
                List<String> supportedFormats = getSupportedVideoFormats(mainActivity);
                if (supportedFormats.contains(settings.format)) {
                    editor.putString(PreferenceKeys.VideoFormatPreferenceKey, settings.format);
                }
            } else {
                editor.putString(PreferenceKeys.ImageFormatPreferenceKey, settings.format);
            }
            editor.apply();
        }

        private List<String> getSupportedVideoFormats(MainActivity mainActivity) {
            // Construct the list of the supported formats the same way MyPreferenceFragment does
            List<String> supportedFormats = new ArrayList<>(Arrays.asList(mainActivity.getResources().getStringArray(R.array.preference_video_output_format_values)));
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                supportedFormats.remove("preference_video_output_format_mpeg4_hevc");
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                supportedFormats.remove("preference_video_output_format_webm");
            }
            return supportedFormats;
        }
    }
}
