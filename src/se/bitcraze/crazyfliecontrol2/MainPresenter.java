package se.bitcraze.crazyfliecontrol2;

import android.nfc.Tag;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.io.IOException;
import java.sql.Time;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.LogRecord;

import se.bitcraze.crazyflie.lib.crazyflie.ConnectionAdapter;
import se.bitcraze.crazyflie.lib.crazyflie.Crazyflie;
import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crazyradio.RadioDriver;
import se.bitcraze.crazyflie.lib.crtp.CommanderPacket;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyflie.lib.crtp.HeightHoldPacket;
import se.bitcraze.crazyflie.lib.crtp.ZDistancePacket;
import se.bitcraze.crazyflie.lib.log.LogAdapter;
import se.bitcraze.crazyflie.lib.log.LogConfig;
import se.bitcraze.crazyflie.lib.log.Logg;
import se.bitcraze.crazyflie.lib.param.Param;
import se.bitcraze.crazyflie.lib.param.ParamListener;
import se.bitcraze.crazyflie.lib.toc.Toc;
import se.bitcraze.crazyflie.lib.toc.VariableType;
import se.bitcraze.crazyfliecontrol.ble.BleLink;
import se.bitcraze.crazyfliecontrol.console.ConsoleListener;
import se.bitcraze.crazyfliecontrol.controller.AbstractController;
import se.bitcraze.crazyfliecontrol.controller.GamepadController;
import se.bitcraze.crazyfliecontrol.controller.IController;
import se.bitcraze.crazyfliecontrol.controller.WifiDirect;

public class MainPresenter {

    private static final String LOG_TAG = "Crazyflie-MainPresenter";

    //the instance of MainActivity that this MainPresenter is linked to
    private MainActivity mainActivity;

    private Crazyflie mCrazyflie;
    private CrtpDriver mDriver;

    private Logg mLogg;
    private LogConfig mDefaultLogConfig = null;

    private Toc mLogToc;
    private Toc mParamToc;

    private boolean mHeadlightToggle = false;
    private boolean mSoundToggle = false;
    private int mRingEffect = 0;
    private int mNoRingEffect = 0;
    private int mCpuFlash = 0;
    private boolean isZrangerAvailable = false;
    private boolean heightHold = false;

    //is WifiDirect connection setup finished
    private boolean wdConnected = false;

    private WifiDirect wifiDirectDriver;

    private Thread mSendJoystickDataThread;
    private ConsoleListener mConsoleListener;

    private SendJoystickDataRunnable joystickRunnable;

    private volatile boolean kill = false;
    private Thread mLaunchingThread;
    private AtomicBoolean launching = new AtomicBoolean(false);

    //constructor
    public MainPresenter(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
        wifiDirectDriver = new WifiDirect(mainActivity);
    }

    public void onDestroy() {
        this.mainActivity = null;
    }

    public boolean isWdConnected() {
        return wdConnected;
    }


    private ConnectionAdapter crazyflieConnectionAdapter = new ConnectionAdapter() {

        @Override
        public void connectionRequested() {
            mainActivity.showToastie("Connecting ...");
        }

        @Override
        public void connected() {
            mainActivity.showToastie("Connected");

            //bluetooth stuff
            if (mCrazyflie != null && mCrazyflie.getDriver() instanceof BleLink) {
                mainActivity.setConnectionButtonConnectedBle();
                //FIXME: Hack to circumvent BLE reconnect problem
                mCrazyflie.startConnectionSetup_BLE();
            }

            //set button look
            else {
                mainActivity.setConnectionButtonConnected();
            }
        }

        @Override
        public void setupFinished() {
            Param param = mCrazyflie.getParam();
            if (param != null) {
                final Toc paramToc = param.getToc();
                if (paramToc != null) {
                    mParamToc = paramToc;
                    mainActivity.showToastie("Parameters TOC fetch finished: " + paramToc.getTocSize());
                    checkForBuzzerDeck();
                    checkForNoOfRingEffects();
                    checkForZRanger();
                }
            }


            mLogg = mCrazyflie.getLogg();
            if (mLogg != null) {
                final Toc logToc = mLogg.getToc();
                if (logToc != null) {
                    mLogToc = logToc;
                    mainActivity.showToastie("Log TOC fetch finished: " + logToc.getTocSize());
                    mDefaultLogConfig = createDefaultLogConfig();
                    startLogConfigs(mDefaultLogConfig);
                }
            }

            //start sending data from joystick
            Log.i(LOG_TAG, "Starting sendJoystickDataThread");
            startSendJoystickDataThread();
        }

        @Override
        public void connectionLost(final String msg) {
            //show message as Toast on UI thread
            mainActivity.showToastie(msg);

            //change the look of the connect button
            mainActivity.setConnectionButtonDisconnected();

            //disconnect
            disconnect();
        }

        @Override
        public void connectionFailed(final String msg) {
            mainActivity.showToastie(msg);
            disconnect();
        }

        @Override
        public void disconnected() {
            //just do some UI stuff
            mainActivity.showToastie("Disconnected");
            mainActivity.setConnectionButtonDisconnected();
            mainActivity.disableButtonsAndResetBatteryLevel();
            stopLogConfigs(mDefaultLogConfig);
        }

        @Override
        public void linkQualityUpdated(final int quality) {
            mainActivity.setLinkQualityText(quality + "%");
        }
    };

    //TODO: Replace with specific test for buzzer deck
    private void checkForBuzzerDeck() {
        //activate buzzer sound button when a CF2 is recognized (a buzzer can not yet be detected separately)
        mCrazyflie.getParam().addParamListener(new ParamListener("cpu", "flash") {
            @Override
            public void updated(String name, Number value) {
                mCpuFlash = mCrazyflie.getParam().getValue("cpu.flash").intValue();
                //enable buzzer action button when a CF2 is found (cpu.flash == 1024)
                if (mCpuFlash == 1024) {
                    mainActivity.setBuzzerSoundButtonEnablement(true);
                }
                Log.d(LOG_TAG, "CPU flash: " + mCpuFlash);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("cpu.flash");
    }

    private void checkForZRanger() {
        //this should return true when either a zRanger or a flow deck is connected
        mCrazyflie.getParam().addParamListener(new ParamListener("deck", "bcZRanger") {
            @Override
            public void updated(String name, Number value) {
                isZrangerAvailable = mCrazyflie.getParam().getValue("deck.bcZRanger").intValue() == 1;
                //TODO: indicate in the UI that the zRanger sensor is installed
                if (isZrangerAvailable) {
                    mainActivity.showToastie("Found zRanger sensor.");
                }
                Log.d(LOG_TAG, "is zRanger installed: " + isZrangerAvailable);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("deck.bcZRanger");
    }

    private void checkForNoOfRingEffects() {
        //set number of LED ring effects
        mCrazyflie.getParam().addParamListener(new ParamListener("ring", "neffect") {
            @Override
            public void updated(String name, Number value) {
                mNoRingEffect = mCrazyflie.getParam().getValue("ring.neffect").intValue();
                //enable LED ring action buttons only when ring.neffect parameter is set correctly (=> hence it's a CF2 with a LED ring)
                if (mNoRingEffect > 0) {
                    mainActivity.setRingEffectButtonEnablement(true);
                    mainActivity.setHeadlightButtonEnablement(true);
                }
                Log.d(LOG_TAG, "No of ring effects: " + mNoRingEffect);
            }
        });
        mCrazyflie.getParam().requestParamUpdate("ring.neffect");
    }

    private void sendPacket(CrtpPacket packet) {
        if (mCrazyflie != null) {
            mCrazyflie.sendPacket(packet);
        }
        else {
            Log.e(LOG_TAG, "CRAZYFLIE CAME UP NULL");
        }
    }


    //Runnable that sends data from joysticks to the drone containing user input
    class SendJoystickDataRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        public SendJoystickDataRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }

        public void run() {
            while (!mFinished) {
                // Do stuff.
                int counter = 0;
                while (mainActivity != null && mCrazyflie != null) {
                    //get the controller we're using
                    IController controller = mainActivity.getController();


                    if (controller == null) {
                        Log.d(LOG_TAG, "SendJoystickDataThread: controller is null.");
                        break;
                    }

                    //get the position control data
                    float roll = controller.getRoll();
                    float pitch = controller.getPitch();
                    float yaw = controller.getYaw();
                    float thrustAbsolute = controller.getThrustAbsolute();

                    //probly not
                    boolean xmode = mainActivity.getControls().isXmode();


                    if (counter == 0) {
                        //check if we're trying to hold drone at a specific height
                        if (heightHold) {
                            //right now always returns 0.3
                            float targetHeight = controller.getTargetHeight();
                            sendPacket(new ZDistancePacket(roll, pitch, yaw, targetHeight));
                        }


                        //****************************************************************************************************
                        //DEFAULT. Send a CommanderPacket to the drone with the requested movement data
                        else {
                            Log.i(LOG_TAG, String.format("sending CommanderPacket to drone with roll %f, pitch %f, yaw %f, thrustAbs %f", roll, pitch, yaw, thrustAbsolute));
                            sendPacket(new CommanderPacket(roll, pitch, yaw, (char) thrustAbsolute, xmode));
                        }
                        //****************************************************************************************************
                        counter =- 1;
                    }

                    //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                    CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                    /*
                    /////////
                    try {
                        Thread.sleep(20); //CHANGED: WAS 20
                    }

                    catch (InterruptedException e) {
                        Log.d(LOG_TAG, "SendJoystickDataThread was interrupted.");
                        break;
                    }
                    ///////////*/

                    counter++;

                    //Log.i(LOG_TAG, "Count");

                    //get the lock on the pauser object
                    synchronized (mPauseLock) {
                        //Log.i(LOG_TAG, "Testing joystick stopper");

                        //check to see if a pause was indeed requested. If so, wait until notify from onResume()
                        while (mPaused) {
                            try {
                                //causes current thread to wait until another thread invokes notify() or notifyAll() for this obj
                                mPauseLock.wait();
                            }
                            catch (InterruptedException e) {
                            }
                        }
                    }
                }
            }
        }

        //pause the thread to stop streaming joystick data to onboard phone so that we can run a navigation sequence, etc.
        public void onPause() {
            synchronized (mPauseLock) {
                mainActivity.showToastie("Joystick stream stopped");
                mPaused = true;
            }
        }

        //resume the thread
        public void onResume() {
            //get lock on the pauser object, set paused to false, and notify mPauseLock object
            synchronized (mPauseLock) {
                mPaused = false;
                //wake up all threads that are waiting on this object's monitor
                mPauseLock.notifyAll();
            }
        }
    }


    /**
     * Start thread to periodically send commands containing the user input
     */
    private void startSendJoystickDataThread() {
        Log.i(LOG_TAG, "Joystick thread started");
        mainActivity.showToastie("Joystick stream started");

        joystickRunnable = new SendJoystickDataRunnable();

        //instantiate the joystick data sending thread
        mSendJoystickDataThread = new Thread(joystickRunnable);

        //start the thread
        mSendJoystickDataThread.start();
    }

    public void startHeightHoldThread() {

    }

    //Runnable that sends data from joysticks to the drone containing user input
    class LaunchRunnable implements Runnable {
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        public LaunchRunnable() {
            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }

        public void run() {
            //pause the joystick sending thread so that we can safely run the launch sequence
            joystickRunnable.onPause();

            //TODO: launch sequence
            Log.i(LOG_TAG, "Launching...");

            final int[] cnt = {0};
            int thrust_mult = 1;
            int thrust_step = 100;
            int thrust_dstep = 10;
            int thrust = 3000;
            int pitch = 0;
            int roll = 0;
            int yawrate = 0;
            final float start_height = 0.05f;

            //the distance is not accurate, 1.2 => 1.5m
            final float target_height = 0.3f;

            Runnable runnable;

            /*
            //get Python interpreter
            Python python = Python.getInstance();
            PyObject pythonFile = python.getModule("cf-python-lib/examples/flytest.py");
            pythonFile.call();*/

            //Unlock startup thrust protection
            sendPacket(new CommanderPacket(0, 0, 0, (char)0));

            //create new Handler to post delayed work to the main thread
            final android.os.Handler handler = new Handler(Looper.getMainLooper());

            /*
            //DEFINE prop test
            runnable = new Runnable() {
                public void run() {
                    //send low thrust packet to indicate packet transfer success
                    sendPacket(new CommanderPacket(0, 0, 0, (char) 10001));

                    //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                    CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                    if (cnt[0]++ < 20) {
                        handler.post(this);
                    }
                    //otherwise runnable is complete
                }
            };

            //RUN prop test
            handler.post(runnable);*/


            /*
            while (cnt[0] < 20) {
                //send low thrust packet to indicate packet transfer success
                sendPacket(new CommanderPacket(0, 0, 0, (char) 10001));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                cnt[0]++;
            }*/


            //reset counter to 0
            cnt[0] = 0;

            //DEFINE up sequence
            Log.i(LOG_TAG, "Lifting");

            /*
            while (cnt[0] < 10) {
                sendPacket(new HeightHoldPacket(0, 0, 0, start_height));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                cnt[0]++;
            }*/

            runnable = new Runnable() {
                @Override
                public void run() {
                    //split deltah into 50 and lift one part per packet
                    sendPacket(new HeightHoldPacket(0, 0, 0, (float)start_height + (target_height - start_height) * (cnt[0] / 50.0f)));

                    //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                    CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                    if (killCheck())
                        return;

                    if (cnt[0]++ < 50) {
                        handler.postDelayed(this, 50);
                    }
                }
            };

            //RUN up sequence
            handler.post(runnable);

            /*
            while (cnt[0] < 50) {
                sendPacket(new HeightHoldPacket(0, 0, 0, (float)start_height + (target_height - start_height) * (cnt[0] / 50.0f)));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                if (killCheck()) {
                    return;
                }

                cnt[0]++;
            }*/

            //reset counter to 0
            cnt[0] = 0;

            //DEFINE hover sequence
            runnable = new Runnable() {
                //hover for 30
                @Override
                public void run() {
                    sendPacket(new HeightHoldPacket(0, 0, 0, target_height));

                    //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                    CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                    if (killCheck())
                        return;

                    if (cnt[0]++ < 50) {
                        handler.postDelayed(this, 100);
                    }
                }
            };

            //RUN hover sequence
            handler.post(runnable);

            /*
            while (cnt[0] < 30) {
                sendPacket(new HeightHoldPacket(0, 0, 0, target_height));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                if (killCheck()) {
                    return;
                }

                cnt[0]++;
            }*/

            //reset counter to 0
            cnt[0] = 0;

            //DEFINE down sequence


            //down
            Log.i(LOG_TAG, "Landing");
            runnable = new Runnable() {
                @Override
                public void run() {
                    //split deltah into 50 and lower one part per packet
                    sendPacket(new HeightHoldPacket(0, 0, 0, (-target_height + start_height) * (cnt[0] / 50.0f) + target_height));

                    //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                    CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                    if (killCheck())
                        return;

                    //for 50 cycles, send another ZDistancePacket, incrementing cnt[0] each time
                    if (cnt[0]++ < 50) {
                        handler.postDelayed(this, 50);
                    }
                }
            };

            //RUN down sequence
            handler.post(runnable);

            /*
            while (cnt[0] < 50) {
                sendPacket(new HeightHoldPacket(0, 0, 0, (-target_height + start_height) * (cnt[0] / 50.0f) + target_height));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);

                if (killCheck()) {
                    return;
                }

                cnt[0]++;
            }*/

            //stop
            sendPacket(new CommanderPacket(0, 0, 0, (char)0));
            //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
            CrtpPacket testing = wifiDirectDriver.receivePacket(1);


            //what we want to do now is hover indefinitely. That means we need to keep streaming height hold pkts with target_height, while
            //also running a joystick thread but disabling the thrust for the joystick and subbing in the correct thrust...
            //or rather, we could run a new thread that streams height hold packets and accepts right joystick pad for pitch and roll adjustments
            startHeightHoldThread();

            //resume streaming packets from joystick
            joystickRunnable.onResume();

            //set launching to false
            launching.set(false);
        }

        //pause the thread to stop streaming joystick data to onboard phone so that we can run a navigation sequence, etc.
        public void onPause() {
            synchronized (mPauseLock) {
                mainActivity.showToastie("Joystick stream stopped");
                mPaused = true;
            }
        }

        //resume the thread
        public void onResume() {
            //get lock on the pauser object, set paused to false, and notify mPauseLock object
            synchronized (mPauseLock) {
                mPaused = false;
                //wake up all threads that are waiting on this object's monitor
                mPauseLock.notifyAll();
            }
        }
    }

    public void launch() {
        launching.set(true);
        mLaunchingThread = new Thread(new LaunchRunnable());
        mLaunchingThread.start();
    }

    public void land() {
        //pause the joystick sending thread so that we can safely run the landing sequence
        joystickRunnable.onPause();

        //TODO: landing sequence

        joystickRunnable.onResume();
    }

    //request a kill. Called on pressing "kill" button
    public void kill() {
        Log.i(LOG_TAG, "Kill requested");
        kill = true;

        //if launching, interrupt the launching thread
        if (launching.get())
            mLaunchingThread.interrupt();
    }

    //check if user has requested kill. If so, kill the drone
    public boolean killCheck() {
        if (kill) {
            //pause the joystick sending thread so that we can safely run the landing sequence (if it's not already paused
            joystickRunnable.onPause();

            //send 10 STOP packets
            for (int i = 0; i < 10; i++) {
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));

                //BLOCK UNTIL RECEIVE CONFIRMATION FROM DRONE BACK THRU PIPELINE
                CrtpPacket testing = wifiDirectDriver.receivePacket(1);
            }

            joystickRunnable.onResume();

            kill = false;

            return true;
        }

        return false;
    }

    public void connectWifiDirect() {
        wifiDirectDriver.discoverPeers();
    }

    public void connectToPixel(File mCacheDir) {
        //make sure wifiDirectDriver and its pixelDev are non-null
        if (wifiDirectDriver==null || wifiDirectDriver.pixelDev == null) {
            mainActivity.showToastie("Please run peer discovery first and wait until you see \"Onboard Pixel found\" toast");
            return;
        }

        //add listener for connection status
        wifiDirectDriver.addConnectionListener(crazyflieConnectionAdapter);

        mCrazyflie = new Crazyflie(wifiDirectDriver, mCacheDir, this);

        //Pixel has now been found. Request p2p connection with it
        wifiDirectDriver.connectTo(wifiDirectDriver.pixelDev);

        //we need to wait here
    }

    //callback for when WifiDirect connection has finished
    public void onConnectToPixelFinished() {
        //call connect on the driver, which just shows toast and starts the WifiDirect communication thread
        mCrazyflie.connect();

        //add console listener
        if (mCrazyflie != null) {
            mConsoleListener = new ConsoleListener();
            mConsoleListener.setMainActivity(mainActivity);

            //add data listener to our Crazyflie instance
            mCrazyflie.addDataListener(mConsoleListener);
        }

        //at this point all Wifi Direct comms are set up, let's set wdConnected to true
        wdConnected = true;
    }

    public WifiDirect getWifiDirect() {
        return wifiDirectDriver;
    }

    public void connectCrazyradio(int radioChannel, int radioDatarate, File mCacheDir) {
        Log.d(LOG_TAG, "connectCrazyradio()");
        //ensure previous link is disconnected and driver is blank
        disconnect();
        mDriver = null;

        try {
            mDriver = new RadioDriver(new UsbLinkAndroid(mainActivity));
        }

        catch (IllegalArgumentException e) {
            Log.d(LOG_TAG, e.getMessage());
            mainActivity.showToastie(e.getMessage());
        }

        catch (IOException e) {
            Log.e(LOG_TAG, e.getMessage());
            mainActivity.showToastie(e.getMessage());
        }

        connect(mCacheDir, new ConnectionData(radioChannel, radioDatarate));
    }

    public void connectBle(boolean writeWithResponse, File mCacheDir) {
        Log.d(LOG_TAG, "connectBle()");
        // ensure previous link is disconnected
        disconnect();


        mDriver = null;
        mDriver = new BleLink(mainActivity, writeWithResponse);


        connect(mCacheDir, null);
    }

    private void connect(File mCacheDir, ConnectionData connectionData) {

        //make sure the driver exists
        if (mDriver != null) {
            //add listener for connection status
            mDriver.addConnectionListener(crazyflieConnectionAdapter);

            mCrazyflie = new Crazyflie(mDriver, mCacheDir, this);

            if (mDriver instanceof RadioDriver) {
                mCrazyflie.setConnectionData(connectionData);
            }


            //connect
            mCrazyflie.connect();

            //add console listener
            if (mCrazyflie != null) {
                mConsoleListener = new ConsoleListener();
                mConsoleListener.setMainActivity(mainActivity);
                mCrazyflie.addDataListener(mConsoleListener);
            }
        }

        else {
            mainActivity.showToastie("Cannot connect: Crazyradio not attached and Bluetooth LE not available");
        }
    }

    public void disconnect() {
        Log.d(LOG_TAG, "MainPresenter: disconnect()");


        //kill sendJoystickDataThread first to avoid NPE
        if (mSendJoystickDataThread != null) {
            mSendJoystickDataThread.interrupt();
            mSendJoystickDataThread = null;
        }

        //remove the ConsoleListener from our instance of Crazyflie
        if (mCrazyflie != null) {
            mCrazyflie.removeDataListener(mConsoleListener);
            mCrazyflie.disconnect();
            mCrazyflie = null;
        }

        //remove connectionListener from the driver
        if (mDriver != null) {
            //mDriver.removeConnectionListener(crazyflieConnectionAdapter);
            wifiDirectDriver.removeConnectionListener(crazyflieConnectionAdapter);
        }

        //link quality is not available when there is no active connection
        mainActivity.setLinkQualityText("N/A");
    }

    public void enableAltHoldMode(boolean hover) {
        // For safety reasons, altHold mode is only supported when the Crazyradio and a game pad are used
        if (mCrazyflie != null && mCrazyflie.getDriver() instanceof RadioDriver && mainActivity.getController() instanceof GamepadController) {
            if (isZrangerAvailable) {
                heightHold = hover;
                //reset target height, when hover is deactivated
                if (!hover) {
                    ((GamepadController) mainActivity.getController()).setTargetHeight(AbstractController.INITIAL_TARGET_HEIGHT);
                }
            }

            else {
                //Log.i(LOG_TAG, "flightmode.althold: getThrust(): " + mController.getThrustAbsolute());
                mCrazyflie.setParamValue("flightmode.althold", hover ? 1 : 0);
            }
        }
    }

    //TODO: make runAltAction more universal
    public void runAltAction(String action) {
        Log.i(LOG_TAG, "runAltAction: " + action);
        if (mCrazyflie != null) {
            if ("ring.headlightEnable".equalsIgnoreCase(action)) {
                // Toggle LED ring headlight
                mHeadlightToggle = !mHeadlightToggle;
                mCrazyflie.setParamValue(action, mHeadlightToggle ? 1 : 0);
                mainActivity.toggleHeadlightButtonColor(mHeadlightToggle);
            } else if ("ring.effect".equalsIgnoreCase(action)) {
                // Cycle through LED ring effects
                Log.i(LOG_TAG, "Ring effect: " + mRingEffect);
                mCrazyflie.setParamValue(action, mRingEffect);
                mRingEffect++;
                mRingEffect = (mRingEffect > mNoRingEffect) ? 0 : mRingEffect;
            } else if (action.startsWith("sound.effect")) {
                // Toggle buzzer deck sound effect
                String[] split = action.split(":");
                Log.i(LOG_TAG, "Sound effect: " + split[1]);
                mCrazyflie.setParamValue(split[0], mSoundToggle ? Integer.parseInt(split[1]) : 0);
                mSoundToggle = !mSoundToggle;
            }
        } else {
            Log.d(LOG_TAG, "runAltAction - crazyflie is null");
        }
    }

    public Crazyflie getCrazyflie(){
        return mCrazyflie;
    }

    private LogAdapter standardLogAdapter = new LogAdapter() {

        public void logDataReceived(LogConfig logConfig, Map<String, Number> data, int timestamp) {
            super.logDataReceived(logConfig, data, timestamp);

            if ("Standard".equals(logConfig.getName())) {
                final float battery = (float) data.get("pm.vbat");
                mainActivity.setBatteryLevel(battery);
            }
            for (Map.Entry<String, Number> entry : data.entrySet()) {
                Log.d(LOG_TAG, "\t Name: " + entry.getKey() + ", data: " + entry.getValue());
            }
        }

    };

    private LogConfig createDefaultLogConfig() {
        LogConfig logConfigStandard = new LogConfig("Standard", 1000);
        logConfigStandard.addVariable("pm.vbat", VariableType.FLOAT);
        return logConfigStandard;
    }

    /**
     * Start logging config
     */
    private void startLogConfigs(LogConfig logConfig) {
        if (mLogg == null) {
            Log.e(LOG_TAG, "startLogConfigs: mLogg was null!!");
            return;
        }
        if (logConfig == null) {
            Log.e(LOG_TAG, "startLogConfigs: Logg was null!!");
            return;
        }
        mLogg.addLogListener(standardLogAdapter);
        mLogg.addConfig(logConfig);
        mLogg.start(logConfig);
    }

    /**
     * Stop logging config
     */
    private void stopLogConfigs(LogConfig logConfig) {
        if (mLogg == null) {
            Log.e(LOG_TAG, "stopLogConfigs: mLogg was null!!");
            return;
        }
        if (logConfig == null) {
            Log.e(LOG_TAG, "stopLogConfigs: Logg was null!!");
            return;
        }
        mLogg.stop(logConfig);
        mLogg.delete(logConfig);
        mLogg.removeLogListener(standardLogAdapter);
    }
}
