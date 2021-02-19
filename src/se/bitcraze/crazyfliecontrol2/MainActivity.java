/**
 *    ||          ____  _ __
 * +------+      / __ )(_) /_______________ _____  ___
 * | 0xBC |     / __  / / __/ ___/ ___/ __ `/_  / / _ \
 * +------+    / /_/ / / /_/ /__/ /  / /_/ / / /_/  __/
 *  ||  ||    /_____/_/\__/\___/_/   \__,_/ /___/\___/
 *
 * Copyright (C) 2013 Bitcraze AB
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

package se.bitcraze.crazyfliecontrol2;

import java.io.File;
import java.util.List;
import java.util.Locale;

import se.bitcraze.crazyflie.lib.crazyradio.Crazyradio;
import se.bitcraze.crazyfliecontrol.controller.Controls;
import se.bitcraze.crazyfliecontrol.controller.GamepadController;
import se.bitcraze.crazyfliecontrol.controller.GyroscopeController;
import se.bitcraze.crazyfliecontrol.controller.IController;
import se.bitcraze.crazyfliecontrol.controller.TouchController;
import se.bitcraze.crazyfliecontrol.controller.WifiDirect;
import se.bitcraze.crazyfliecontrol.prefs.PreferencesActivity;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.SoundPool.OnLoadCompleteListener;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.ContextMenu;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.MobileAnarchy.Android.Widgets.Joystick.JoystickView;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public class MainActivity extends Activity {

    private static final String LOG_TAG = "CrazyflieControl";
    private static final int MY_PERMISSIONS_REQUEST_LOCATION = 42;

    private JoystickView mJoystickViewLeft;
    private JoystickView mJoystickViewRight;
    private FlightDataView mFlightDataView;

    private ScrollView mConsoleScrollView;
    private TextView mConsoleTextView;

    private SharedPreferences mPreferences;

    private IController mController;
    private GamepadController mGamepadController;

    private String mRadioChannelDefaultValue;
    private String mRadioDatarateDefaultValue;

    private boolean mDoubleBackToExitPressedOnce = false;
    private Controls mControls;
    private SoundPool mSoundPool;
    private boolean mLoaded;
    private int mSoundConnect;
    private int mSoundDisconnect;

    private Button mDiscPeersButton;
    private Button mRequestConnectButton;
    private Button mLaunchButton;
    private Button mLandButton;
    private Button mKillButton;
    private Button mFollowButton;
    private ImageButton mRingEffectButton;
    private ImageButton mHeadlightButton;
    private ImageButton mBuzzerSoundButton;
    private File mCacheDir;

    private WifiDirect wifiDirect;

    private TextView mTextView_battery;
    private TextView mTextView_linkQuality;
    private MainPresenter mPresenter;

    private boolean following = false;


    IntentFilter peerfilter;
    IntentFilter connectionfilter;
    IntentFilter p2pEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.i(LOG_TAG, "Good morning from the Main commander");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //start Python3 interpreter
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        //instantiate a MainPresenter
        mPresenter = new MainPresenter(this);

        //create the p2p action intent filters for the callback fxns
        peerfilter = new IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);

        connectionfilter = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        p2pEnabled = new IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);


        setDefaultPreferenceValues();

        //set some textviews for top left corner
        mTextView_battery = (TextView) findViewById(R.id.battery_text);
        mTextView_linkQuality = (TextView) findViewById(R.id.linkQuality_text);

        //initialize the texts to nothing
        setBatteryLevel(-1.0f);
        setLinkQualityText("N/A");

        //instantiate a Controls
        mControls = new Controls(this, mPreferences);
        mControls.setDefaultPreferenceValues(getResources());

        //Default controller
        mJoystickViewLeft = (JoystickView) findViewById(R.id.joystick_left);
        mJoystickViewRight = (JoystickView) findViewById(R.id.joystick_right);
        mJoystickViewRight.setLeft(false);

        //instantiate a TouchController
        mController = new TouchController(mControls, this, mJoystickViewLeft, mJoystickViewRight);

        //initialize gamepad controller
        mGamepadController = new GamepadController(mControls, this, mPreferences);
        mGamepadController.setDefaultPreferenceValues(getResources());

        //initialize buttons
        mDiscPeersButton = (Button) findViewById(R.id.disc_peers_button);
        mRequestConnectButton = (Button) findViewById(R.id.rqst_conn_button);
        mLaunchButton = (Button) findViewById(R.id.launcher_button);
        mLandButton = (Button) findViewById(R.id.lander_button);
        mKillButton = (Button) findViewById(R.id.kill_button);
        mFollowButton = (Button) findViewById(R.id.follow_button);
        initializeMenuButtons();

        //view to display flight data
        mFlightDataView = (FlightDataView) findViewById(R.id.flightdataview);

        mConsoleScrollView = (ScrollView) findViewById(R.id.console_scrollView);
        mConsoleTextView = (TextView) findViewById(R.id.console_textView);
        registerForContextMenu(mConsoleTextView);

        //action buttons
        mRingEffectButton = (ImageButton) findViewById(R.id.button_ledRing);
        mHeadlightButton = (ImageButton) findViewById(R.id.button_headLight);
        mBuzzerSoundButton = (ImageButton) findViewById(R.id.button_buzzerSound);

        //new intent filter for USB
        IntentFilter filter = new IntentFilter();
        filter.addAction(this.getPackageName() + ".USB_PERMISSION");
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        //register the USB receiver
        registerReceiver(mUsbReceiver, filter);

        //some useless sounds I guess
        initializeSounds();

        //storage
        setCacheDir();
    }

    public MainPresenter getMainPresenter() {
        return mPresenter;
    }

    private void initializeSounds() {
        this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
        // Load sounds
        mSoundPool = new SoundPool(10, AudioManager.STREAM_MUSIC, 0);
        mSoundPool.setOnLoadCompleteListener(new OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                mLoaded = true;
            }
        });
        mSoundConnect = mSoundPool.load(this, R.raw.proxima, 1);
        mSoundDisconnect = mSoundPool.load(this, R.raw.tejat, 1);
    }

    private void setCacheDir() {
        if (isExternalStorageWriteable()) {
            Log.d(LOG_TAG, "External storage is writeable.");
            if (mCacheDir == null) {
                File appDir = getApplicationContext().getExternalFilesDir(null);
                mCacheDir = new File(appDir, "TOC_cache");
                mCacheDir.mkdirs();
            }
        } else {
            Log.d(LOG_TAG, "External storage is not writeable.");
            mCacheDir = null;
        }
    }

    private boolean isExternalStorageWriteable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    private void setDefaultPreferenceValues() {
        // Set default preference values
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        // Initialize preferences
        mPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        mRadioChannelDefaultValue = getString(R.string.preferences_radio_channel_defaultValue);
        mRadioDatarateDefaultValue = getString(R.string.preferences_radio_datarate_defaultValue);
    }

    private void checkScreenLock() {
        boolean isScreenLock = mPreferences.getBoolean(PreferencesActivity.KEY_PREF_SCREEN_ROTATION_LOCK_BOOL, false);
        if (isScreenLock) {
            this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private void checkConsole() {
        boolean showConsole = mPreferences.getBoolean(PreferencesActivity.KEY_PREF_SHOW_CONSOLE_BOOL, false);

        if (showConsole) {
            mConsoleScrollView.setVisibility(View.VISIBLE);
        }
        else {
            mConsoleScrollView.setVisibility(View.INVISIBLE);
        }
    }

    private void initializeMenuButtons() {
        mRequestConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestConnToPixel();
            }
        });


        //set discover peers button click listener
        mDiscPeersButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                try {
                    //if we're already connected
                    if (mPresenter != null && mPresenter.getCrazyflie() != null && mPresenter.getCrazyflie().isConnected()) {
                        mPresenter.disconnect();
                    }

                    //run the connection: either Crazyradio or Bluetooth
                    else {
                        /*
                        if (isCrazyradioAvailable(MainActivity.this)) {
                            connectCrazyradio();
                        }

                        else {
                            connectBlePreChecks();
                        }*/

                        //connect to the Pixel for USB packet relay
                        connectToOnboardPhoneViaWifiDirect();
                    }
                }

                catch (IllegalStateException e) {
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        mLaunchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mPresenter.isWdConnected()) {
                    showToastie("Launching...");
                    mPresenter.launch();
                }
                else {
                    showToastie("Please complete your Wifi Direct connection first");
                }
            }
        });

        mLandButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mPresenter.isWdConnected()) {
                    showToastie("Landing...");
                    mPresenter.land();
                }
                else {
                    showToastie("Please complete your Wifi Direct connection first");
                }
            }
        });

        mKillButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mPresenter.isWdConnected()) {
                    showToastie("Killing...");
                    mPresenter.kill();
                }
                else {
                    showToastie("Please complete your Wifi Direct connection first");
                }
            }
        });

        mFollowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (following) {
                    //if currently following, tell the drone to stop following
                    mPresenter.stopFollowing();
                    mFollowButton.setText(R.string.start_follow);
                }
                else {
                    mPresenter.startFollowing();
                    mFollowButton.setText(R.string.stop_follow);
                }
                following = !following;
            }
        });

        //set click listener for settings button
        ImageButton settingsButton = (ImageButton) findViewById(R.id.imageButton_settings);
        settingsButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PreferencesActivity.class);
                startActivity(intent);
            }
        });
    }

    private void connectCrazyradio() {
        int radioChannel = Integer.parseInt(mPreferences.getString(PreferencesActivity.KEY_PREF_RADIO_CHANNEL, mRadioChannelDefaultValue));
        int radioDatarate = Integer.parseInt(mPreferences.getString(PreferencesActivity.KEY_PREF_RADIO_DATARATE, mRadioDatarateDefaultValue));
        mPresenter.connectCrazyradio(radioChannel, radioDatarate, mCacheDir);
    }

    private void connectBlePreChecks() {
        //Check if Bluetooth LE is supported by the Android version
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            Log.e(LOG_TAG, Build.VERSION.SDK_INT + "does not support Bluetooth LE.");
            Toast.makeText(this, Build.VERSION.SDK_INT + "does not support Bluetooth LE. Please use a Crazyradio to connect to the Crazyflie instead.", Toast.LENGTH_LONG).show();
            return;
        }
        //Check if Bluetooth LE is supported by the hardware
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(LOG_TAG, "Device does not support Bluetooth LE.");
            Toast.makeText(this, "Device does not support Bluetooth LE. Please use a Crazyradio to connect to the Crazyflie instead.", Toast.LENGTH_LONG).show();
            return;
        }


        //Since Android version 6, ACCESS_COARSE_LOCATION is required for Bluetooth scanning
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.e(LOG_TAG, "Android version >= 6 requires ACCESS_COARSE_LOCATION permissions for Bluetooth scanning.");

            //request location permission for bluetooth scanning
            requestPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, MY_PERMISSIONS_REQUEST_LOCATION);
        }

        else {
            //run the bluetooth connection
            connectBle();
        }
    }

    private void connectToOnboardPhoneViaWifiDirect() {
        //showToastie("Connecting Wifi Direct...");
        Log.i(LOG_TAG, "Connecting to onboard phone...");
        mPresenter.connectWifiDirect();
    }

    private void requestConnToPixel() {
        Log.i(LOG_TAG, "Attempting to request conn to Pixel...");
        mPresenter.connectToPixel(mCacheDir);
    }

    private void connectBle() {
        boolean writeWithResponse = mPreferences.getBoolean(PreferencesActivity.KEY_PREF_BLATENCY_BOOL, false);
        Log.d(LOG_TAG, "Using bluetooth write with response - " + writeWithResponse);
        mPresenter.connectBle(writeWithResponse, mCacheDir);
    }

    private void checkLocationSettings() {
        LocationManager service = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean isEnabled = service.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!isEnabled) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Location Access");
            builder.setMessage("The app needs location access for Bluetooth scanning. Please enable it in the settings menu.");
            builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(intent);
                }
            });
            builder.setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    Log.d(LOG_TAG, "Location access request has been denied.");
                }
            });
            builder.show();
        } else {
            connectBle();
        }

    }

    private void requestPermissions(String permission, int request) {
        if (ContextCompat.checkSelfPermission(MainActivity.this, permission) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted. Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(MainActivity.this, permission)) {
                // Show an explanation to the user *asynchronously* -- don't block this thread waiting for the user's response!
                // After the user sees the explanation, try again to request the permission.
                Log.d(LOG_TAG, "ACCESS_COARSE_LOCATION permission request has been denied.");
                //Toast.makeText(this,  "Android version >= 6 requires ACCESS_COARSE_LOCATION permissions for Bluetooth scanning.", Toast.LENGTH_LONG).show();
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, request);
            }

            else {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, request);
            }
        } else {
            // Permission has already been granted
            checkLocationSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            //handle a location request permission result
            case MY_PERMISSIONS_REQUEST_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, yay! Do the contacts-related task you need to do.
                    checkLocationSettings();
                }

                else {
                    // permission denied, boo! Disable the functionality that depends on this permission.
                    Log.d(LOG_TAG, "ACCESS_COARSE_LOCATION permission request has been denied.");
                    Toast.makeText(this, "Android version >= 6 requires ACCESS_COARSE_LOCATION permissions for Bluetooth scanning.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    public void onResume() {
        //a bulk of work is done here it seems like

        super.onResume();


        PreferencesActivity.setDefaultJoystickSize(this);
        mJoystickViewLeft.setPreferences(mPreferences);
        mJoystickViewRight.setPreferences(mPreferences);


        mControls.setControlConfig();
        mGamepadController.setControlConfig();


        resetInputMethod();

        checkScreenLock();
        checkConsole();


        //disable action buttons
        mRingEffectButton.setEnabled(false);
        mHeadlightButton.setEnabled(false);
        mBuzzerSoundButton.setEnabled(false);


        //register the p2p broadcast receivers to listen
        registerReceiver(peerDiscoveryReceiver, peerfilter);
        registerReceiver(connectionChangedReceiver, connectionfilter);
        registerReceiver(p2pStatusReceiver, p2pEnabled);


        if (mPreferences.getBoolean(PreferencesActivity.KEY_PREF_IMMERSIVE_MODE_BOOL, false)) {
            setHideyBar();
        }
        //mJoystickViewLeft.requestLayout();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(LOG_TAG, "onPause()");
        if (mControls != null) {
            mControls.resetAxisValues();
        }
        if (mController != null) {
            mController.disable();
        }

        //unregister p2p receivers
        unregisterReceiver(peerDiscoveryReceiver);
        unregisterReceiver(connectionChangedReceiver);
        unregisterReceiver(p2pStatusReceiver);


        updateFlightData();
        mPresenter.disconnect();
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG, "onDestroy()");
        unregisterReceiver(mUsbReceiver);
        mSoundPool.release();
        mSoundPool = null;
        mPresenter.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mDoubleBackToExitPressedOnce) {
            super.onBackPressed();
            return;
        }
        this.mDoubleBackToExitPressedOnce = true;
        Toast.makeText(this, "Please click BACK again to exit", Toast.LENGTH_SHORT).show();
        new Handler().postDelayed(new Runnable() {

            @Override
            public void run() {
                mDoubleBackToExitPressedOnce = false;

            }
        }, 2000);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mPreferences.getBoolean(PreferencesActivity.KEY_PREF_IMMERSIVE_MODE_BOOL, false) && hasFocus) {
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    setHideyBar();
                }
            }, 2000);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        if (v.getId() == R.id.console_textView) {
            AdapterView.AdapterContextMenuInfo info = (AdapterView.AdapterContextMenuInfo) menuInfo;
            menu.add(Menu.NONE, 0, 0, "Copy to clipboard");
            menu.add(Menu.NONE, 1, 1, "Clear console");
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                ClipboardManager cm = (ClipboardManager) getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = ClipData.newPlainText("console text", mConsoleTextView.getText());
                cm.setPrimaryClip(clipData);
                showToastie("Copied to clipboard");
                break;
            case 1:
                mConsoleTextView.setText("");
                showToastie("Console cleared");
                break;
            default:
                break;
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void setHideyBar() {
        Log.i(LOG_TAG, "Activating immersive mode");
        int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
        int newUiOptions = uiOptions;

        if (Build.VERSION.SDK_INT >= 14) {
            newUiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }
        if (Build.VERSION.SDK_INT >= 16) {
            newUiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }
        if (Build.VERSION.SDK_INT >= 18) {
            newUiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        getWindow().getDecorView().setSystemUiVisibility(newUiOptions);
    }


    //TODO: fix indirection
    //Update the text for the flight data info
    public void updateFlightData() {
        mFlightDataView.updateFlightData(mController.getPitch(), mController.getRoll(), mController.getThrust(), mController.getYaw());
    }

    public void appendToConsole(String text) {
        final String ftext = text;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConsoleTextView.append("\n" + ftext);
                mConsoleScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        mConsoleScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    //You can override this to intercept all generic motion events before
    // they are dispatched to the window
    //events regarding moving the joysticks
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // Check that the event came from a joystick since a generic motion event could be almost anything.
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) != 0 && event.getAction() == MotionEvent.ACTION_MOVE && mController instanceof GamepadController) {
            Log.i(LOG_TAG, "motion event handling");
            mGamepadController.dealWithMotionEvent(event);
            updateFlightData();
            return true;
        }

        else {
            return super.dispatchGenericMotionEvent(event);
        }
    }


    /*
    Called to process key events. You can override this to intercept all key events before
    they are dispatched to the window.
    Be sure to call this implementation for key events that should be handled normally.
     */
    //key pressing events (the joystick buttons)
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // do not call super if key event comes from a gamepad, otherwise the buttons can quit the app
        if (isJoystickButton(event.getKeyCode()) && mController instanceof GamepadController) {
            Log.i(LOG_TAG, "key event handling");

            mGamepadController.dealWithKeyEvent(event);
            // exception for OUYA controllers
            if (!Build.MODEL.toUpperCase(Locale.getDefault()).contains("OUYA")) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    //this workaround is necessary because DPad buttons are not considered to be "Gamepad buttons"
    private static boolean isJoystickButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            default:
                return KeyEvent.isGamepadButton(keyCode);
        }
    }


    //****INPUT METHOD INIT****
    private void resetInputMethod() {
        //temp disable the touch controller
        mController.disable();

        //update flight data text
        updateFlightData();


        switch (mControls.getControllerType()) {
            case 0:
                // Use GyroscopeController if activated in the preferences
                if (mControls.isUseGyro()) {
                    mController = new GyroscopeController(mControls, this, mJoystickViewLeft, mJoystickViewRight);
                }


                //otherwise let's create a new touchcontroller
                else {
                    // TODO: reuse existing touch controller? -- good point, we already have one
                    mController = new TouchController(mControls, this, mJoystickViewLeft, mJoystickViewRight);
                }
                break;
            case 1:
                // TODO: show warning if no game pad is found?
                mController = mGamepadController;
                break;
            default:
                break;

        }
        mController.enable();
        Toast.makeText(this, "Using " + mController.getControllerName(), Toast.LENGTH_SHORT).show();
    }

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(LOG_TAG, "mUsbReceiver action: " + action);
            if ((MainActivity.this.getPackageName() + ".USB_PERMISSION").equals(action)) {
                //reached only when USB permission on physical connect was canceled and "Connect" or "Radio Scan" is clicked
                synchronized (this) {
                    UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Toast.makeText(MainActivity.this, "Crazyradio attached", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Log.d(LOG_TAG, "permission denied for device " + device);
                    }
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && UsbLinkAndroid.isUsbDevice(device, Crazyradio.CRADIO_VID, Crazyradio.CRADIO_PID)) {
                    Log.d(LOG_TAG, "Crazyradio detached");
                    Toast.makeText(MainActivity.this, "Crazyradio detached", Toast.LENGTH_SHORT).show();


                    if (mPresenter != null && mPresenter.getCrazyflie() != null) {
                        Log.d(LOG_TAG, "linkDisconnect()");
                        mPresenter.disconnect();
                    }
                }
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && UsbLinkAndroid.isUsbDevice(device, Crazyradio.CRADIO_VID, Crazyradio.CRADIO_PID)) {
                    Log.d(LOG_TAG, "Crazyradio attached");
                    Toast.makeText(MainActivity.this, "Crazyradio attached", Toast.LENGTH_SHORT).show();

                }
            }
        }
    };

    //callback for when WifiP2PManager.discoverPeers() is called
    BroadcastReceiver peerDiscoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            //make sure fine location permission granted
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "peerDiscoveryReceiver.onReceive(): ACCESS_FINE_LOCATION not granted");
                return;
            }

            Log.i(LOG_TAG, "peerDiscoveryReceiver: Peers have updated");

            mPresenter.getWifiDirect().requestPeersList();
        }
    };

    //receive a Wifi Direct status change
    BroadcastReceiver p2pStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);


            if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                mDiscPeersButton.setEnabled(true);
                mRequestConnectButton.setEnabled(true);
            }

            else {
                Log.e(LOG_TAG, "FALSE");
                mDiscPeersButton.setEnabled(false);
                mRequestConnectButton.setEnabled(false);
            }

            //TODO: do anything with the state? Not now
        }
    };


    //wifi direct peer connection callback
    BroadcastReceiver connectionChangedReceiver = new BroadcastReceiver() {
        //executes when the Wifi Direct connection status changes
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(LOG_TAG, "Wifi Direct connection status change detected");


            mPresenter.getWifiDirect().connectionChanged(intent);

        }

    };


    private void playSound(int sound) {
        if (mLoaded) {
            float volume = 1.0f;
            mSoundPool.play(sound, volume, volume, 1, 0, 1f);
        }
    }

    // extra method for onClick attribute in XML
    public void switchLedRingEffect(View view) {
        if (mPresenter != null) {
            mPresenter.runAltAction("ring.effect");
        }
    }

    // extra method for onClick attribute in XML
    public void toggleHeadlight(View view) {
        if (mPresenter != null) {
            mPresenter.runAltAction("ring.headlightEnable");
        }
    }

    // extra method for onClick attribute in XML
    public void playBuzzerSound(View view) {
        if (mPresenter != null) {
            mPresenter.runAltAction("sound.effect:10");
        }
    }

    //some getters
    public MainPresenter getPresenter() {
        return mPresenter;
    }

    public IController getController() {
        return mController;
    }

    public Controls getControls() {
        return mControls;
    }

    public static boolean isCrazyradioAvailable(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            throw new IllegalArgumentException("UsbManager == null!");
        }
        List<UsbDevice> usbDeviceList = UsbLinkAndroid.findUsbDevices(usbManager, (short) Crazyradio.CRADIO_VID, (short) Crazyradio.CRADIO_PID);
        return !usbDeviceList.isEmpty();
    }

    public void setBatteryLevel(float battery) {
        float normalizedBattery = battery - 3.0f;
        int batteryPercentage = (int) (normalizedBattery * 100);
        if (battery == -1f) {
            batteryPercentage = 0;
        } else if (normalizedBattery < 0f && normalizedBattery > -1f) {
            batteryPercentage = 0;
        } else if (normalizedBattery > 1f) {
            batteryPercentage = 100;
        }
        //TODO: FIXME
        final int fBatteryPercentage = batteryPercentage;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView_battery.setText(format(R.string.battery_text, fBatteryPercentage));
            }
        });
    }

    public void setLinkQualityText(final String quality) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView_linkQuality.setText(format(R.string.linkQuality_text, quality));
            }
        });
    }

    private String format(int identifier, Object o) {
        return String.format(getResources().getString(identifier), o);
    }

    public void showToastie(final String message) { //this dude is funny
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void setConnectionButtonConnected() {
        setConnectionButtonBackground(R.drawable.custom_button_connected);
    }

    public void setConnectionButtonConnectedBle() {
        setConnectionButtonBackground(R.drawable.custom_button_connected_ble);
    }

    public void setConnectionButtonDisconnected() {
        setConnectionButtonBackground(R.drawable.custom_button);
    }

    public void setConnectionButtonBackground(final int drawable) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                //turn the connection rqst button to green, indicating the the connection hs been established
                mRequestConnectButton.setBackgroundColor(Color.GREEN);
            }
        });
    }

    public void setBuzzerSoundButtonEnablement(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mBuzzerSoundButton.setEnabled(enabled);
            }
        });
    }

    public void setRingEffectButtonEnablement(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mRingEffectButton.setEnabled(enabled);
            }
        });
    }

    public void setHeadlightButtonEnablement(final boolean enabled) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mHeadlightButton.setEnabled(enabled);
            }
        });
    }

    public void toggleHeadlightButtonColor(final boolean toggle) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mHeadlightButton.setColorFilter(toggle ? Color.parseColor("#00FF00") : Color.BLACK);
            }
        });
    }

    public void disableButtonsAndResetBatteryLevel() {
        setRingEffectButtonEnablement(false);
        setHeadlightButtonEnablement(false);
        setBuzzerSoundButtonEnablement(false);
        setBatteryLevel(-1.0f);
    }
}

