/**
 *    ||          ____  _ __
 * +------+      / __ )(_) /_______________ _____  ___
 * | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *  ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Copyright (C) 2015 Bitcraze AB
 *
 * Crazyflie Nano Quadcopter Client
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *
 */

package se.bitcraze.crazyfliecontrol.bootloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.bitcraze.crazyflie.lib.bootloader.BootVersion;
import se.bitcraze.crazyflie.lib.bootloader.Bootloader;
import se.bitcraze.crazyflie.lib.bootloader.Bootloader.BootloaderListener;
import se.bitcraze.crazyflie.lib.bootloader.FirmwareRelease;
import se.bitcraze.crazyflie.lib.crazyradio.RadioDriver;
import se.bitcraze.crazyfliecontrol2.MainActivity;
import se.bitcraze.crazyfliecontrol2.R;
import se.bitcraze.crazyfliecontrol2.UsbLinkAndroid;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import javax.net.ssl.HttpsURLConnection;

public class BootloaderActivity extends Activity {

    private static final String LOG_TAG = "BootloaderActivity";
    public final static String BOOTLOADER_DIR = "bootloader";

    private Button mFlashFirmwareButton;
    private ImageButton mReleaseNotesButton;
    private Spinner mFirmwareSpinner;
    private CustomSpinnerAdapter mSpinnerAdapter;
    private ScrollView mScrollView;
    private TextView mConsoleTextView;
    private ProgressBar mProgressBar;

    private FirmwareRelease mSelectedFirmwareRelease = null;
    private FirmwareDownloader mFirmwareDownloader;
    private Bootloader mBootloader;
    private FlashFirmwareTask mFlashFirmwareTask;
    private boolean mDoubleBackToExitPressedOnce = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bootloader);
        mFlashFirmwareButton = (Button) findViewById(R.id.bootloader_flashFirmware);
        mReleaseNotesButton = (ImageButton) findViewById(R.id.bootloader_releaseNotes);
        mFirmwareSpinner = (Spinner) findViewById(R.id.bootloader_firmwareSpinner);
        mScrollView = (ScrollView) findViewById(R.id.bootloader_scrollView);
        mConsoleTextView = (TextView) findViewById(R.id.bootloader_statusLine);
        mProgressBar = (ProgressBar) findViewById(R.id.bootloader_progressBar);

        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        initializeFirmwareSpinner();

        mFirmwareDownloader = new FirmwareDownloader(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkForFirmwareUpdate(getCurrentFocus());
    }

    @Override
    protected void onPause() {
        //TODO: improve
        //TODO: why does resetToFirmware not work?
        if (mFlashFirmwareTask != null && mFlashFirmwareTask.getStatus().equals(Status.RUNNING)) {
            Log.d(LOG_TAG, "OnPause: stop bootloader.");
            mFlashFirmwareTask.cancel(true);
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (mFlashFirmwareTask != null && mFlashFirmwareTask.getStatus().equals(Status.RUNNING)) {
            if (mDoubleBackToExitPressedOnce) {
                super.onBackPressed();
                return;
            }
            this.mDoubleBackToExitPressedOnce = true;
            Toast.makeText(this, "Please click BACK again to cancel flashing and exit", Toast.LENGTH_SHORT).show();
            new Handler().postDelayed(new Runnable() {

                @Override
                public void run() {
                    mDoubleBackToExitPressedOnce = false;

                }
            }, 2000);
        } else {
            super.onBackPressed();
        }
    }

    public void showReleaseNotes(View view) {
        if (mSelectedFirmwareRelease != null && mSelectedFirmwareRelease.getReleaseNotes() != null) {
            final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
            alertDialog.setTitle("Release notes:");
            alertDialog.setMessage(mSelectedFirmwareRelease.getReleaseNotes());
            alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK", new DialogInterface.OnClickListener() {

                @Override
                public void onClick(DialogInterface dialog, int which) {
                    alertDialog.dismiss();
                }
            });
            alertDialog.show();
        }
    }

    public void checkForFirmwareUpdate(View view) {
        mFirmwareDownloader.checkForFirmwareUpdate();
    }

    private void initializeFirmwareSpinner() {
        mSpinnerAdapter = new CustomSpinnerAdapter(BootloaderActivity.this, R.layout.spinner_rows, new ArrayList<FirmwareRelease>());
        mSpinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mFirmwareSpinner.setAdapter(mSpinnerAdapter);
        mFirmwareSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FirmwareRelease firmwareRelease = (FirmwareRelease) mFirmwareSpinner.getSelectedItem();
                if (firmwareRelease != null) {
                    mSelectedFirmwareRelease = firmwareRelease;
                    mFlashFirmwareButton.setEnabled(true);
                    mReleaseNotesButton.setEnabled(true);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                mFlashFirmwareButton.setEnabled(false);
                mReleaseNotesButton.setEnabled(false);
            }

        });
    }

    public void updateFirmwareSpinner(List<FirmwareRelease> firmwareReleases) {
        mSpinnerAdapter.clear();
        Collections.sort(firmwareReleases);
        Collections.reverse(firmwareReleases);
        mSpinnerAdapter.addAll(firmwareReleases);
    }

    public void appendConsole(String status) {
        Log.d(LOG_TAG, status);
        this.mConsoleTextView.append("\n" + status);
        mScrollView.post(new Runnable() {
            @Override
            public void run() {
                mScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    public void appendConsoleError(String status) {
        Log.e(LOG_TAG, status);
        int start = this.mConsoleTextView.getText().length();
        this.mConsoleTextView.append("\n" + status);
        int end = this.mConsoleTextView.getText().length();
        Spannable spannableText = (Spannable) this.mConsoleTextView.getText();
        spannableText.setSpan(new ForegroundColorSpan(Color.RED), start, end, 0);
        mScrollView.post(new Runnable() {
            @Override
            public void run() {
                mScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }

    public void startFlashProcess(final View view) {
        // disable button and spinner
        mFlashFirmwareButton.setEnabled(false);
        mReleaseNotesButton.setEnabled(false);
        mFirmwareSpinner.setEnabled(false);

        //clear console
        mConsoleTextView.setText("");

        // download firmware file
        appendConsole("Downloading firmware...");

        DownloadTask mDownloadTask = new DownloadTask();
        mDownloadTask.execute(this.mSelectedFirmwareRelease);
    }

    private class DownloadTask extends AsyncTask<FirmwareRelease, Integer, String> {

        private PowerManager.WakeLock mWakeLock;
        private boolean mAlreadyDownloaded = false;

        private String downloadFile (String urlString, String fileName, String tagName) {
            InputStream input = null;
            OutputStream output = null;
            HttpsURLConnection connection = null;

            // Retrofitting support for TLSv1.2, because GitHub only supports TLSv1.2
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(new TLSSocketFactory());
            } catch (KeyManagementException | NoSuchAlgorithmException e) {
                e.printStackTrace();
            }

            try {
                URL url = new URL(urlString);
                connection = (HttpsURLConnection) url.openConnection();
                connection.connect();

                // expect HTTP 200 OK, so we don't mistakenly save error report instead of the file
                if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                    return "Server returned HTTP " + connection.getResponseCode() + " " + connection.getResponseMessage();
                }

                // this will be useful to display download percentage. it might be -1: server did not report the length
                int fileLength = connection.getContentLength();

                // download the file
                File outputFile = new File(BootloaderActivity.this.getExternalFilesDir(null) + "/" + BootloaderActivity.BOOTLOADER_DIR + "/" + tagName + "/", fileName);
                outputFile.getParentFile().mkdirs();
                input = connection.getInputStream();
                output = new FileOutputStream(outputFile);

                byte data[] = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    // allow canceling
                    if (isCancelled()) {
                        input.close();
                        return null;
                    }
                    total += count;
                    // publishing the progress....
                    if (fileLength > 0) { // only if total length is known
                        publishProgress((int) (total * 100 / fileLength));
                    }
                    output.write(data, 0, count);
                }
            } catch (Exception e) {
                return e.toString();
            } finally {
                try {
                    if (output != null) {
                        output.close();
                    }
                    if (input != null) {
                        input.close();
                    }
                } catch (IOException ignored) {

                }
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return null;
        }

        @Override
        protected String doInBackground(FirmwareRelease... sFirmwareRelease) {
            mSelectedFirmwareRelease = sFirmwareRelease[0];

            if (mSelectedFirmwareRelease != null) {
                if (mFirmwareDownloader.isFileAlreadyDownloaded(mSelectedFirmwareRelease.getTagName() + "/" + mSelectedFirmwareRelease.getAssetName())) {
                    mAlreadyDownloaded = true;
                    return null;
                }
                String browserDownloadUrl = mSelectedFirmwareRelease.getBrowserDownloadUrl();
                if (mFirmwareDownloader.isNetworkAvailable()) {
                    return downloadFile(browserDownloadUrl, mSelectedFirmwareRelease.getAssetName(), mSelectedFirmwareRelease.getTagName());
                } else {
                    Log.d(LOG_TAG, "Network connection not available.");
                    return "No network connection available.\nPlease check your connectivity.";
                }
            } else {
                return "Selected firmware does not have assets.";
            }
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // take CPU lock to prevent CPU from going off if the user presses the power button during download
            PowerManager pm = (PowerManager) BootloaderActivity.this.getSystemService(Context.POWER_SERVICE);
            mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
            mWakeLock.acquire();
            mProgressBar.setProgress(0);
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            super.onProgressUpdate(progress);
            // if we get here, length is known, now set indeterminate to false
            mProgressBar.setIndeterminate(false);
            mProgressBar.setMax(100);
            mProgressBar.setProgress(progress[0]);
        }

        @Override
        protected void onPostExecute(String result) {
            mWakeLock.release();
            if (result != null) {
                //flash firmware once firmware is downloaded
                appendConsole("Firmware download failed: " + result);
                stopFlashProcess(false);
            } else {
                //flash firmware once firmware is downloaded
                if (mAlreadyDownloaded) {
                    appendConsole("Firmware file already downloaded.");
                } else {
                    appendConsole("Firmware downloaded.");
                }
                startBootloader();
            }
        }
    }

    private void startBootloader() {

        if (!mFirmwareDownloader.isFileAlreadyDownloaded(mSelectedFirmwareRelease.getTagName() + "/" + mSelectedFirmwareRelease.getAssetName())) {
            appendConsoleError("Firmware file can not be found.");
            stopFlashProcess(false);
            return;
        }

        try {
            //fail quickly, when Crazyradio is not connected
            //TODO: fix when BLE is used as well
            //TODO: extract this to RadioDriver class?
            if (!MainActivity.isCrazyradioAvailable(this)) {
                appendConsoleError("Please make sure that a Crazyradio (PA) is connected.");
                stopFlashProcess(false);
                return;
            }
            mBootloader = new Bootloader(new RadioDriver(new UsbLinkAndroid(BootloaderActivity.this)));
        } catch (IOException ioe) {
            appendConsoleError(ioe.getMessage());
            stopFlashProcess(false);
            return;
        } catch (IllegalArgumentException iae) {
            appendConsoleError(iae.getMessage());
            stopFlashProcess(false);
            return;
        }

        new AsyncTask<Void, Void, Boolean>() {

            private ProgressDialog mProgress;

            @Override
            protected void onPreExecute() {
                mProgress = ProgressDialog.show(BootloaderActivity.this, "Searching Crazyflie in bootloader mode...", "Restart the Crazyflie you want to bootload in the next 10 seconds ...", true, false);
            }

            @Override
            protected Boolean doInBackground(Void... arg0) {
                return mBootloader.startBootloader(false);
            }

            @Override
            protected void onPostExecute(Boolean result) {
                mProgress.dismiss();
                if (!result) {
                    appendConsoleError("No Crazyflie found in bootloader mode.");
                    stopFlashProcess(false);
                    return;
                }
                flashFirmware();
            }
        }.execute();
    }

    //TODO: simplify
    public void flashFirmware() {
        //TODO: externalize
        //Check if firmware is compatible with Crazyflie
        int protocolVersion = mBootloader.getProtocolVersion();
        boolean cfType2 = !(protocolVersion == BootVersion.CF1_PROTO_VER_0 ||
                            protocolVersion == BootVersion.CF1_PROTO_VER_1);

        String cfversion = "Found Crazyflie " + (cfType2 ? "2.0" : "1.0") + ".";
        appendConsole(cfversion);

        // check if firmware and CF are compatible
        if (("CF2".equalsIgnoreCase(mSelectedFirmwareRelease.getType()) && !cfType2) ||
            ("CF1".equalsIgnoreCase(mSelectedFirmwareRelease.getType()) && cfType2)) {
            appendConsoleError("Incompatible firmware version.");
            stopFlashProcess(false);
            return;
        }

        //keep the screen on during flashing
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mFlashFirmwareTask = new FlashFirmwareTask();
        mFlashFirmwareTask.execute();
        //TODO: wait for finished task
    }

    private class FlashFirmwareTask extends AsyncTask<String, String, String> {

        boolean flashSuccessful;

        @Override
        protected void onPreExecute() {
            Toast.makeText(BootloaderActivity.this, "Flashing firmware ...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected String doInBackground(String... params) {

            BootloaderListener bootloaderListener = new BootloaderListener() {

                @Override
                public void updateStatus(String status) {
                    publishProgress(status, null, null, null);
                }

                @Override
                public void updateProgress(int progress, int max) {
                    publishProgress(null, "" + progress, "" + max, null);
                    if (isCancelled()) {
                        mBootloader.cancel();
                    }
                }

                @Override
                public void updateError(String error) {
                    publishProgress(null, null, null, error);
                }
            };
            mBootloader.addBootloaderListener(bootloaderListener);

            File bootloaderDir = new File(getApplicationContext().getExternalFilesDir(null), BOOTLOADER_DIR);
            File firmwareFile = new File(bootloaderDir, mSelectedFirmwareRelease.getTagName() + "/" + mSelectedFirmwareRelease.getAssetName());

            long startTime = System.currentTimeMillis();
            try {
                flashSuccessful = mBootloader.flash(firmwareFile);
            } catch (IOException ioe) {
                Log.e(LOG_TAG, ioe.getMessage());
                flashSuccessful = false;
            }
            mBootloader.removeBootloaderListener(bootloaderListener);
            String flashTime = "Flashing took " + (System.currentTimeMillis() - startTime)/1000 + " seconds.";
            Log.d(LOG_TAG, flashTime);
            return flashSuccessful ? ("Flashing successful. " + flashTime) : "Flashing not successful.";
        }

        @Override
        protected void onProgressUpdate(String... progress) {
            if (progress[0] != null) {
                appendConsole(progress[0]);
            } else if (progress[1] != null && progress[2] != null) {
                mProgressBar.setProgress(Integer.parseInt(progress[1]));
                // TODO: progress bar max is reset when activity is resumed
                mProgressBar.setMax(Integer.parseInt(progress[2]));
            } else if (progress[3] != null) {
                appendConsole(progress[3]);
            }
        }

        @Override
        protected void onPostExecute(String result) {
            if (flashSuccessful) {
                appendConsole(result);
            } else {
                appendConsoleError(result);
            }
            stopFlashProcess(true);
        }

        @Override
        protected void onCancelled(String result) {
            stopFlashProcess(false);
        }

    }

    private void stopFlashProcess(boolean reset) {
        if (reset) {
            String resetMsg = "Resetting Crazyflie to firmware mode...";
            appendConsole(resetMsg);
            Toast.makeText(BootloaderActivity.this, resetMsg, Toast.LENGTH_SHORT).show();
            mBootloader.resetToFirmware();
        }
        if (mBootloader != null) {
            mBootloader.close();
        }
        //re-enable widgets
        mFlashFirmwareButton.setEnabled(true);
        mReleaseNotesButton.setEnabled(true);
        mFirmwareSpinner.setEnabled(true);

        mProgressBar.setProgress(0);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

}
