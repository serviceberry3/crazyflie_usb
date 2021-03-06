package se.bitcraze.crazyfliecontrol.controller;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import se.bitcraze.crazyflie.lib.crazyradio.Crazyradio;
import se.bitcraze.crazyflie.lib.crazyradio.RadioAck;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyfliecontrol2.MainActivity;
import se.bitcraze.crazyfliecontrol2.MainPresenter;

public class WifiDirect extends CrtpDriver {

    private Thread mWifiDriverThread;

    private final Logger mLogger = LoggerFactory.getLogger("WifiDirectLogger");

    private static final String TAG = "WifiDirect";
    private MainActivity mContext;

    private WifiDriverThread mWifiDriverRunnable;

    private ListView listView;
    private TextView tv;
    private Button buttonDiscover;

    IntentFilter peerfilter;
    IntentFilter connectionfilter;
    IntentFilter p2pEnabled;

    private Handler handler = new Handler();
    public final String pixel = "Android_333a";
    public WifiP2pDevice pixelDev = null;

    //This class provides API for managing Wi-Fi peer-to-peer (Wifi Direct) connectivity. This lets app discover available peers,
    //setup connection to peers and query for list of peers. When a p2p connection is formed over wifi, the device continues
    //to maintain the uplink connection over mobile or any other available network for internet connectivity on the device.
    public WifiP2pManager wifiP2pManager;
    public WifiP2pManager.Channel wifiDirectChannel;

    private FileServerAsyncTask serverTask = null;

    private boolean mWriteWithAnswer;

    private final BlockingQueue<CrtpPacket> mOutQueue = new LinkedBlockingQueue<CrtpPacket>();
    //TODO: Limit size of out queue to avoid "ReadBack" effect?
    private final BlockingQueue<CrtpPacket> mInQueue = new LinkedBlockingQueue<CrtpPacket>();

    private final BlockingQueue<CrtpPacket> mSocketInQueue = new LinkedBlockingQueue<CrtpPacket>();
    private final BlockingQueue<CrtpPacket> mSocketOutQueue = new LinkedBlockingQueue<CrtpPacket>();

    private final Object mutex = new Object();

    public volatile boolean connected = false;

    private MainPresenter mainPresenter;

    public WifiDirect(MainActivity activity, MainPresenter mainPresenter) {
        this.mContext = activity;
        this.mainPresenter = mainPresenter;

        //this.mInQueue = new LinkedBlockingQueue<CrtpPacket>();

        //initialize the p2p channel
        init();
    }

    public void init() {
        //initialize the peer-to-peer (Wifi Direct) connection manager
        wifiP2pManager = (WifiP2pManager) mContext.getSystemService(Context.WIFI_P2P_SERVICE);

        //WifiP2pManager's initialize() fxn returns channel instance that is necessary for performing any further p2p operations
        wifiDirectChannel = wifiP2pManager.initialize(mContext, mContext.getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    public void onChannelDisconnected() {
                        //re-initialize the WifiDirect channel upon disconnection
                        init();
                    }
                }
        );
    }

    //create WifiP2pManager ActionListener
    //Most application calls need ActionListener instance for receiving callbacks ActionListener.onSuccess() or ActionListener.onFailure(), which
    //indicate whether initiation of the action was a success or a failure. Reason of failure can be ERROR, P2P_UNSUPPORTED or BUSY
    private WifiP2pManager.ActionListener actionListener = new WifiP2pManager.ActionListener() {
        public void onFailure(int reason) {
            String errorMessage = "WiFi Direct failed with error: ";

            //error filter
            switch (reason) {
                case WifiP2pManager.BUSY:
                    errorMessage += "Framework busy.";
                    break;
                case WifiP2pManager.ERROR:
                    errorMessage += "Internal error.";
                    break;
                case WifiP2pManager.P2P_UNSUPPORTED:
                    errorMessage += "Unsupported.";
                    break;
                default:
                    errorMessage += "Unknown error.";
                    break;
            }

            //print out the final error message to the log
            Log.e(TAG, errorMessage);
        }

        public void onSuccess() {
            //Success!
            //Return values will be returned using a Broadcast Intent
        }
    };

    public void requestPeersList() {
        wifiP2pManager.requestPeers(wifiDirectChannel,
                new WifiP2pManager.PeerListListener() {
                    public void onPeersAvailable(WifiP2pDeviceList peers) {
                        //clear the old peers list
                        deviceList.clear();

                        //add all the found peers to the list
                        deviceList.addAll(peers.getDeviceList());

                        //find the Pixel we want to connect to and set that as pixelDev so we remember it
                        //if (pixelDev==null) {
                            for (WifiP2pDevice dev : deviceList) {
                                Log.i(TAG, dev.deviceName);


                                if (dev.deviceName.equals(pixel)) {
                                    Log.i(TAG, "Found onboard Pixel, setting dev...");
                                    Toast.makeText(mContext, "Onboard Pixel found", Toast.LENGTH_LONG).show();

                                    pixelDev = dev;

                                    break;
                                }
                            }
                        //}
                    }
                });
    }

    //discover Wifi Direct peers
    //An initiated discovery request from an app stays active until device starts connecting to a peer, forms a p2p group, or there's an explicit
    //stopPeerDiscovery(). Apps can listen to WIFI_P2P_DISCOVERY_CHANGED_ACTION to know if a peer-to-peer discovery is running or stopped.
    //WIFI_P2P_PEERS_CHANGED_ACTION indicates if peer list has changed
    public void discoverPeers() {
        //make sure we have permission
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "discoverPeers(): ACCESS_FINE_LOCATION not granted");
            return;
        }

        Log.i(TAG, "Running discoverPeers()...");
        mContext.showToastie("Discovering peers...");

        //run discoverPeers() method of WifiP2pManager
        wifiP2pManager.discoverPeers(wifiDirectChannel, actionListener);
    }


    //request connection to a wifi direct peer
    public void connectTo(WifiP2pDevice device) {
        Log.i(TAG, "connectTo called, initiating connection with onboard pixel...");

        //create new p2p configuration
        WifiP2pConfig config = new WifiP2pConfig();

        //get address of target device
        config.deviceAddress = device.deviceAddress;

        //make sure we have fine location perm
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "P2P connect error: fine location perm not granted");
            return;
        }

        //connect to the p2p device using the above address we got from the device
        wifiP2pManager.connect(wifiDirectChannel, config, actionListener);

        Log.i(TAG, "connectTo finished");
    }


    //something was connected or disconnected
    public void connectionChanged(Intent intent) {
        Log.i(TAG, "Connection CHANGED");


        //Extract the NetworkInfo
        String extraKey = WifiP2pManager.EXTRA_NETWORK_INFO;
        NetworkInfo networkInfo = (NetworkInfo) intent.getParcelableExtra(extraKey);

        //Check if we're connected
        assert networkInfo != null;
        if (networkInfo.isConnected()) {
            Log.i(TAG, "Network is connected to something...");

            wifiP2pManager.requestConnectionInfo(wifiDirectChannel,
                    new WifiP2pManager.ConnectionInfoListener() {
                        public void onConnectionInfoAvailable(WifiP2pInfo info) {
                            //If the connection is established
                            if (info.groupFormed) {
                                Log.i(TAG, "Connection has been established!");


                                //If we're the server
                                if (info.isGroupOwner) {
                                    Log.i(TAG, "We're the server, creating ServerSocket in background and waiting for client...");

                                        //create ServerSocket in background and wait for client to connect
                                        serverTask = new FileServerAsyncTask();
                                    try {
                                        //wait for socket setup task to finish
                                        serverTask.execute().get();
                                    } catch (ExecutionException e) {
                                        e.printStackTrace();
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }

                                    //WifiDirect cnxn finished; notify the presenter that we can start everything up
                                    mContext.getMainPresenter().onConnectToPixelFinished();
                                }

                                //If we're the client
                                else if (info.groupFormed) {
                                    Log.i(TAG, "We're the client");

                                    //create Socket in background and request connection to server
                                    initiateClientSocket(info.groupOwnerAddress.getHostAddress());
                                }
                                connected = true;
                            }
                        }
                    });
        }

        else {
            Log.d(TAG, "Wi-Fi Direct Disconnected");
        }
    }



    //MAND METHODS FROM CRTPDRIVER-----------------------------------------------------------------------------------------------------------------------------------------
    @Override
    public void connect() throws IOException {
        //all this does is show connecting toast
        notifyConnectionRequested();

        //Launch the WifiDirect comm thread between this phone and onboard phone
        startSendReceiveThread(); //CAUSING MAIN THD TO BLOCK
    }

    /*
    public void notifySocketConnectionFinished() throws IOException {
        //Show connecting toast
        notifyConnectionRequested();


        //Launch the comm thread
        startSendReceiveThread(); //CAUSING MAIN THD TO BLOCK
    }*/

    @Override
    public void disconnect() {
        mLogger.debug("WifiDirect: disconnect()");

        //Stop the comm thread, if it's up
        stopSendReceiveThread();

        //Avoid NPE because packets are still processed. Wait .1 sec for the sendreceive thread to stop
        try {
            Thread.sleep(100);
        }
        catch (InterruptedException e) {
            mLogger.error("Interrupted during disconnect: " + e.getMessage());
        }

        Log.i(TAG, "WifiDirect driver disconnect done.");
        //just do some UI and log stuff for the disconnect
        notifyDisconnected();
    }

    //check whether we're maintaining a connection between drone phone and controller phone
    @Override
    public boolean isConnected() {
        return connected;
    }

    int ctr = 0;

    @Override
    public void sendPacket(CrtpPacket packet) {
        //TODO: does it make sense to be able to queue packets even though the connection is not established yet?
        //this.mOutQueue.addLast(packet);

        //add the passed packet to the out FIFO queue
        try {
            Log.i(TAG, "WifiDirect putting control packet on the queue, size of queue before is " + mOutQueue.size());
            mOutQueue.put(packet);
            Log.i(TAG, "WifiDirect finished queue packet put, size of queue now " + mOutQueue.size());

            if (mOutQueue.size() > 10) {
                mContext.showToastie("There was a problem with the connection. Please close app and try again.");
                disconnect();

                //halt joystick stream indefinitely
                mainPresenter.pauseJoystickRunnable();
            }
        }

        catch (InterruptedException e) {
            mLogger.error("InterruptedException: " + e.getMessage());
        }
    }

    @Override
    public CrtpPacket receivePacket(int time) {
        //retrieves and removes head of FIFO queue, or returns NULL if time runs out b4 an item is avail,
        //waiting up to the specified wait time if necessary for an element to become available
        //so here we're returning the first-in ack packet that's in the queue (that was received back from the drone)
        try {
            //Log.i(TAG, "WifiDirect receivePacket(): polling mInQueue.");
            return mInQueue.poll((long) time, TimeUnit.SECONDS);
        }

        catch (InterruptedException e) {
            mLogger.error("InterruptedException: " + e.getMessage());
            return null;
        }
    }
    //END MAND METHODS FROM CRTPDRIVER..........................................................................................................................................


    //instantiate and start a new Thread that receives and sends packets
    private void startSendReceiveThread() {
        if (mWifiDriverThread == null) {
            mWifiDriverRunnable = new WifiDriverThread(serverTask);

            //run on separate (non-UI) thread to avoid blocking UI
            mWifiDriverThread = new Thread(mWifiDriverRunnable);
            mWifiDriverThread.start();
        }
    }

    //stop the sender/receiver Thread by calling interrupt() to end the loop
    private void stopSendReceiveThread() {
        if (this.mWifiDriverThread != null) {
            this.mWifiDriverThread.interrupt();

            //set the Thread to null
            this.mWifiDriverThread = null;
        }
    }

    public void pauseWifiDriverThread() {
        mWifiDriverRunnable.onPause();
    }

    public void resumeWifiDriverThread() {
        mWifiDriverRunnable.onResume();
    }

    /**
     * Wifi link receiver thread is used to send and receive data from the onboard Pixel, interacting with the LinkedBlockingQueues.
     */
    private class WifiDriverThread implements Runnable {

        final Logger mLogger = LoggerFactory.getLogger(this.getClass().getSimpleName());

        //how many times to retry sending a packet and get back .isAck() as false before we disconnect
        private final static int RETRYCOUNT_BEFORE_DISCONNECT = 10;
        private int mRetryBeforeDisconnect;
        private final OutputStream outputStream;
        private final InputStream inputStream;
        private final Object mPauseLock;
        private boolean mPaused;
        private boolean mFinished;

        /**
         * Create the object
         */
        public WifiDriverThread(FileServerAsyncTask serverTask) {
            this.outputStream = serverTask.getOutStream();
            this.inputStream = serverTask.getInStream();
            this.mRetryBeforeDisconnect = RETRYCOUNT_BEFORE_DISCONNECT;

            mPauseLock = new Object();
            mPaused = false;
            mFinished = false;
        }

        /**
         * Run the receiver thread
         *
         * (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        public void run() {
            //set dataOut byte array to null packet to start, which is just 0xff
            byte[] dataOut = Crazyradio.NULL_PACKET; //equal to one 0xff byte

            //wait up to 100 ms for an mOutQueue item to become available, since we often sleep while running automated flight scripts
            double waitTime = 100;
            int emptyCtr = 0;

            //as long as the Thread isn't interrupted. (Basically runs infinitely)
            while (!Thread.currentThread().isInterrupted()) {
                //Log.i(TAG, "Running sendReceiveThread");
                try {

                    /*
                    try:
                        ackStatus = self.cradio.send_packet(dataOut)
                    except Exception as e:
                        import traceback
                        self.link_error_callback("Error communicating with crazy radio"
                                                 " ,it has probably been unplugged!\n"
                                                 "Exception:%s\n\n%s" % (e,
                                                 traceback.format_exc()))
                    */

                    Log.i(TAG, "WifiDirect: WifiDriverThread calling sendPacketHelper()");

                    //***********************************************************************************************************************************
                    //send next packet to the drone, blocking until get response
                    RadioAck ackStatus = sendPacketHelper(dataOut, outputStream, inputStream);      //TODO: implement new packet sender for WIFI DIRECT SOCKET

                    /*CHANGED BY NOAH: SKIP ACK ANALYSIS FOR NOW

                    //Analyze the data packet returned by the onboard Pixel (client) after the send. If there was no acknowledgment, something's wrong with comms
                    if (ackStatus == null) {
                        //BYPASS SHUTDOWN FOR NOW

                        //No acknowledgement returned. Log stuff and disconnect everything. This will end up interrupt()ing this thread when disconnect() is called above
                        //notifyConnectionLost("Dongle communication error (ackStatus == null)"); //calls disconnect() on MainPresenter instance




                        mLogger.warn("Dongle communication error (ackStatus == null)");
                        Log.e(TAG, "WifiDirect driver: ackStatus came up NULL in sendreceive thread. Shutting everything down.");
                        //continue;
                    }

                    //notifyLinkQualityUpdated((10 - ackStatus.getRetry()) * 10);

                    //If no copter, retry
                    //TODO: how is this actually possible?

                    //try sending again
                    if (!ackStatus.isAck()) {
                        this.mRetryBeforeDisconnect--;

                        //BYPASS SHUTDOWN FOR NOW
                        if (this.mRetryBeforeDisconnect == 0) {

                            //we've retried the send 10 times, now we'll disconnect everything and interrupt this thread in disconnect() above.
                            //notifyConnectionLost("Too many packets lost");
                            Log.e(TAG, "WifiDirect driver: too many packets were lost in sendreceive thread. Shutting everything down.");


                        }
                        //continue;
                    }
                    //if we made it here, ackStatus.isAck() was true, so copter in range

                    this.mRetryBeforeDisconnect = RETRYCOUNT_BEFORE_DISCONNECT;

                    //get the payload
                    byte[] data = ackStatus.getData();


                    //If there is a copter in range (ack had a payload), the incoming ack payload is analyzed and the next packet to send is prepared
                    if (data != null && data.length > 0) {
                        //create a packet from the ackStatus data payload
                        CrtpPacket inPacket = new CrtpPacket(data);

                        Log.i(TAG, "WifiDirect driverThread: Got non-null ack from cf, putting on mInQueue...");

                        //put the packet on the IN blocking queue. It's now available to be picked up by receivePacket() above
                        mInQueue.put(inPacket);

                        waitTime = 0;
                        emptyCtr = 0;
                    }

                    //otherwise we got an empty payload in the ack
                    else {
                        //increment empty packet counter
                        emptyCtr += 1;

                        if (emptyCtr > 10) {
                            emptyCtr = 10;
                            //Relaxation time if the last 10 packets were empty
                            waitTime = 0.01;
                        }

                        //otherwise no waiting for the queue polling, we can't relax it
                        else {
                            waitTime = 0;
                        }
                    }*/

                    //SKIP: create fake incoming packet

                    //create incoming packet to store ack
                    CrtpPacket inPacket = new CrtpPacket(new byte[1]);
                    mInQueue.put(inPacket);

                    //get the next packet to send after relaxation (wait 10ms)
                    CrtpPacket outPacket = mOutQueue.poll((long) waitTime, TimeUnit.MILLISECONDS); //retrieves and removes head of FIFO queue, or returns NULL if time runs out b4 an item is avail
                    //waiting up to the specified wait time if necessary for an element to become available

                    //if we got something from the queue to send
                    if (outPacket != null) {
                        //Log.i(TAG, "sendReceiveThread: got joystick control pkt from queue, converting to bytearray for sending");

                        //convert the CRTP packet to array of bytes for proper transmission. Should be a 15-byte array for CommanderPkt, 17-byte array for HeightHoldPkt
                        dataOut = outPacket.toByteArray();
                    }

                    //otherwise set out to 0xff again
                    else {
                        Log.i(TAG, "WifiDirect: setting dataOut to NULL_PACKET");
                        //prepare a NULL packet to send to the copter
                        dataOut = Crazyradio.NULL_PACKET;
                    }

                    //check to see if a pause was requested from the MainPresenter

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


                //if we called to stop the Thread
                catch (InterruptedException e) {
                    mLogger.debug("WifiDriverThread was interrupted.");
                    notifyLinkQualityUpdated(0);
                    break;
                }
            }
        }

        //pause the thread to stop streaming packets from queue (in this case, probably NULL packets) to onboard phone so that we can run a navigation sequence, etc.
        public void onPause() {
            synchronized (mPauseLock) {
                Log.i(TAG, "Pausing WifiDriverThread runnable!");
                mContext.showToastie("Joystick stream stopped");
                mPaused = true;
            }
        }

        //resume the thread
        public void onResume() {
            //get lock on the pauser object, set paused to false, and notify mPauseLock object
            synchronized (mPauseLock) {
                Log.i(TAG, "Resuming WifiDriverThread runnable!");
                mPaused = false;
                //wake up all threads that are waiting on this object's monitor
                mPauseLock.notifyAll();
            }
        }
    }

    /**
     * Send a packet and receive the ACKNOWLEDGEMENT signal from the onboard phone (back from drone).
     * The ack contains information about the packet transmission
     * and a data payload if the ack packet contained any. Right now I'm just using a dummy ack, 0x09.
     *
     * @param dataOut bytes to send
     */
    public RadioAck sendPacketHelper(final byte[] dataOut, OutputStream outStream, InputStream inStream) { //was sendPacket()
        RadioAck ackIn = null;

        //create array of bytes to hold incoming data
        byte[] dataIn = new byte[25]; // 33?


        Log.i(TAG, "dataOut length is " + dataOut.length);
        Log.i(TAG, "mInQueue num elements is " + mInQueue.size());
        Log.i(TAG, "mOutQueue num elements is " + mOutQueue.size());

        //print out pkts
        if (dataOut != null && dataOut.length == 15) {

            //if (counter == 3) {
                //Log.i(TAG, String.format("Sending packet of length %d to drone", dataOut.length));
                Log.i(TAG, String.format("Controller sending packet 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                                "0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X ",
                        //"0x%02X 0x%02X 0x%02X 0x%02X " +
                        //"0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                        //"0x%02X 0x%02X 0x%02X 0x%02X to drone",
                        dataOut[0], dataOut[1], dataOut[2], dataOut[3], dataOut[4],
                        dataOut[5], dataOut[6], dataOut[7], dataOut[8], dataOut[9], dataOut[10], dataOut[11], dataOut[12],
                        dataOut[13], dataOut[14]/*, dataOut[15], dataOut[16]*/));


                    /*
                    dataOut[15], dataOut[16], dataOut[17], dataOut[18], dataOut[19], dataOut[20],
                    dataOut[21], dataOut[22], dataOut[23], dataOut[24], dataOut[25], dataOut[26], dataOut[27], dataOut[28],
                    dataOut[29], dataOut[30], dataOut[31], dataOut[32]));*/
        }
        else if (dataOut != null && dataOut.length == 17) {

            //if (counter == 3) {
            //Log.i(TAG, String.format("Sending packet of length %d to drone", dataOut.length));
            Log.i(TAG, String.format("Controller sending packet 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                            "0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X",
                    //"0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X 0x%02X " +
                    //"0x%02X 0x%02X 0x%02X 0x%02X to drone",
                    dataOut[0], dataOut[1], dataOut[2], dataOut[3], dataOut[4],
                    dataOut[5], dataOut[6], dataOut[7], dataOut[8], dataOut[9], dataOut[10], dataOut[11], dataOut[12],
                    dataOut[13], dataOut[14], dataOut[15], dataOut[16]));


                    /*
                    dataOut[15], dataOut[16], dataOut[17], dataOut[18], dataOut[19], dataOut[20],
                    dataOut[21], dataOut[22], dataOut[23], dataOut[24], dataOut[25], dataOut[26], dataOut[27], dataOut[28],
                    dataOut[29], dataOut[30], dataOut[31], dataOut[32]));*/
        }



        /*pseudo: send packet via the server/client socket
        We're the server, so we need to write a packet. Then we need to block waiting for the returned response
        */
        asServerSendDataAndWaitForResponse(dataOut, dataIn, outStream, inStream);


        return null;
        /*SKIP ACK FOR NOW

        ackIn = new RadioAck();

        //if there was a data payload in the ack
        if (dataIn[0] != 0) {
            Log.i(TAG, String.format("Setting Ack for ackIn to %b...", (dataIn[0] & 0x01) != 0));

            //make sure this is an Ack (the first byte in the received packet is not 0x00), set the isAck boolean appropriately
            ackIn.setAck((dataIn[0] & 0x01) != 0);


            ackIn.setPowerDet((dataIn[0] & 0x02) != 0);


            ackIn.setRetry(dataIn[0] >> 4);

            //set the payload
            ackIn.setData(Arrays.copyOfRange(dataIn, 1, dataIn.length));
        }
        else {
            Log.i(TAG, "Ack: dataIn[0] is 0x00");
        }

        return ackIn;*/
    }


    //send a packet to onboard Pixel (on the drone) via WifiDirect and wait for ACK
    public void asServerSendDataAndWaitForResponse(byte[] out, byte[] in, OutputStream outStream, InputStream inStream) {
        try {
            if (outStream == null) {
                mContext.showToastie("A problem occurred with the Wifi socket. Please retry the connection process.");
                return;
            }
            outStream.write(out);
        }

        catch (IOException e) {
            e.printStackTrace();
        }


        Log.i(TAG,"SenddataandWait waiting for read in from drone...");
        //block waiting for a packet
        try {
            if (inStream == null) {
                mContext.showToastie("A problem occurred with the Wifi socket. Please retry the connection process.");
                return;
            }
            int dataIn = inStream.read(in);
            Log.i(TAG, String.format("Senddatandwait Got back %x from drone, returning...", in[0]));
        }

        catch (IOException e) {
            e.printStackTrace();
        }
    }



























//BEGIN CLIENT/SERVER WIFI DIRECT SETUP CODE-----------------------------------------------------------------------------------------------------------------------------------------


    public static class becomeServerForPC extends Thread {
        @Override
        public void run() {
            Log.d(TAG, "Start becomeServerForPC thread...");

            ServerSocket serverSocket = null;

            try {
                //create server socket and wait for client connections.
                serverSocket = new ServerSocket(8988);

                Log.d(TAG, "Socket waiting " + serverSocket.getLocalSocketAddress().toString() );

                Socket client = serverSocket.accept();

                InputStream inputStream = client.getInputStream();

                Log.d(TAG, "InputStream is available: " + String.valueOf(inputStream.available()));

                //shut down server
                serverSocket.close();
            }

            catch (IOException e) {
                Log.e(TAG, "becomeServerForPC received exception " + e.getMessage());

                e.printStackTrace();

                //make sure server is closed
                if (serverSocket != null) {
                    try {
                        serverSocket.close();
                    }
                    catch (IOException ex) {
                        Log.e(TAG, "Failed to close socket exception " + ex.getMessage());
                    }
                }
            }

        }
    }

    private BufferedReader in;
    private PrintWriter out;
    private InputStream inStream;
    private OutputStream outStream;

//-----------------------------------------------------------------CLIENT CODE-------------------------------------------------------------------------------

    //create a client socket on a background thread
    private void initiateClientSocket(final String hostAddress) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Log.i(TAG, "Address :" + hostAddress);

                int timeout = 10000;
                int port = 8988;
                int success = 0;

                //create packet of host and port information
                InetSocketAddress socketAddress = new InetSocketAddress(hostAddress, port);

                byte[] bytes = new byte[1];

                bytes[0] = 0x30;

                //create a client socket and connect it to the server
                Socket socket = new Socket();

                try {
                    Log.i(TAG, "initiateClientSocket(): calling bind");

                    socket.bind(null);

                    socket.connect(socketAddress, timeout);

                    Log.i(TAG, "Client-server connection successful!!");

                    //get input and output streams for the socket
                    outStream = socket.getOutputStream();
                    inStream = socket.getInputStream();

                    Log.i(TAG, "Client: sending 48 to server...");


                    //ping the server test
                    outStream.write(48);

                    Log.i(TAG, "Client: data sent to server complete, now reading...");
                }

                catch (IOException e) {
                    Log.e(TAG, "IO Exception from trying to bind socket:", e);
                }


                //Clean up any open sockets when done transferring or if an exception occurred.
                //executed no matter what, even if other exceptions occur
                finally {
                    if (socket.isConnected()) {
                        try {
                            socket.close();
                        }

                        catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }).start();
    }

//-----------------------------------------------------------------END CLIENT CODE-------------------------------------------------------------------------------


    private List<WifiP2pDevice> deviceList = new ArrayList<>();



    //---------------------------------------------------------------------SERVER CODE---------------------------------------------------------------------------

    //Server socket that initializes in background and accepts connection and reads data from client (use of AsyncTask here is probly stupid)
    public static class FileServerAsyncTask extends AsyncTask<WifiDirect, Void, Void> { //params passed, progress update returned, final returned
        private Context context;
        private TextView statusText;

        private OutputStream outStream;
        private InputStream inStream;

        private ServerSocket serverSocket;

        public boolean done = false;


        //getters for public use so that we can send and receive
        public OutputStream getOutStream() {
            return outStream;
        }

        public InputStream getInStream() {
            return inStream;
        }

        @Override
        protected Void doInBackground(WifiDirect... params) {
            try {
                //Create a server socket and wait for client connections. This
                //call blocks until a connection is accepted from a client
                serverSocket = new ServerSocket(8988);

                Log.d(TAG, "Server: Socket opened port 8988, waiting for client");
                Log.i(TAG, "Address: " + serverSocket.getLocalSocketAddress());

                //block until connection from client comes through
                Socket client = serverSocket.accept();

                Log.d(TAG, "Server: connection from client came through");

                //get input and output streams for the client
                outStream = client.getOutputStream();
                inStream = client.getInputStream();


                //SOCKET COMMS TEST
                /*
                Log.d(TAG, "Server: reading in data...");

                byte[] in = new byte[10];

                //this call BLOCKS until data detected and read in
                int dataIn = inStream.read(in);

                Log.d(TAG, "Server: reading in data complete");
                Log.d(TAG, String.format("Got message from client: " + in[0]));*/
            }

            catch (IOException e) {
                e.printStackTrace();
            }

            //CLOSING THE SOCKET
            /*
            finally {
                try {
                    //close up the socket
                    serverSocket.close();
                }

                catch (IOException e) {
                    e.printStackTrace();
                }
            }*/

            return null;
        }




        //non-static version
        private void fileServerTask(final String hostAddress, WifiDirect wifiDirect) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        //Create a server socket and wait for client connections. This
                        //call blocks until a connection is accepted from a client
                        serverSocket = new ServerSocket(8988);

                        Log.d(TAG, "Server: Socket opened port 8988, waiting for client");
                        Log.i(TAG, "Address: " + serverSocket.getLocalSocketAddress());

                        //block until connection from client comes through
                        Socket client = serverSocket.accept();

                        Log.d(TAG, "Server: connection from client came through");

                        //get input and output streams for the client
                        outStream = client.getOutputStream();
                        inStream = client.getInputStream();


                        //SOCKET COMMS TEST
                        /*
                        Log.d(TAG, "Server: reading in data...");

                        byte[] in = new byte[10];

                        //this call BLOCKS until data detected and read in
                        int dataIn = inStream.read(in);

                        Log.d(TAG, "Server: reading in data complete");
                        Log.d(TAG, String.format("Got message from client: " + in[0]));*/
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    //CLOSING THE SOCKET
                    /*
                    finally {
                        try {
                            //close up the socket
                            serverSocket.close();
                        }

                        catch (IOException e) {
                            e.printStackTrace();
                        }
                    }*/
                }
            }).start();
        }


//--------------------------------------------------------------------END SERVER CODE------------------------------------------------------------------------


        //unused AsyncTask methods

        @Override
        protected void onPostExecute (Void result) {
            done = true;
        }



        @Override
        protected void onPreExecute() {
        }
    }

}
