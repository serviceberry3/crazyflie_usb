package se.bitcraze.crazyfliecontrol.controller;

import android.Manifest;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import se.bitcraze.crazyflie.lib.Utilities;
import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crazyradio.Crazyradio;
import se.bitcraze.crazyflie.lib.crazyradio.RadioAck;
import se.bitcraze.crazyflie.lib.crazyradio.RadioDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyflie.lib.usb.CrazyUsbInterface;
import se.bitcraze.crazyfliecontrol.ble.BleLink;
import se.bitcraze.crazyfliecontrol2.MainActivity;
import se.bitcraze.crazyfliecontrol2.R;

public class WifiDirect extends CrtpDriver {

    private Thread mRadioDriverThread;

    private final Logger mLogger = LoggerFactory.getLogger("WifiDirectLogger");

    private static final String TAG = "WifiDirect";
    private MainActivity mContext;

    private ListView listView;
    private TextView tv;
    private Button buttonDiscover;

    IntentFilter peerfilter;
    IntentFilter connectionfilter;
    IntentFilter p2pEnabled;

    private Handler handler = new Handler();
    public final String pixel = "Android_ea5c";
    public WifiP2pDevice pixelDev = null;

    //This class provides API for managing Wi-Fi peer-to-peer (Wifi Direct) connectivity. This lets app discover available peers,
    //setup connection to peers and query for list of peers. When a p2p connection is formed over wifi, the device continues
    //to maintain the uplink connection over mobile or any other available network for internet connectivity on the device.
    public WifiP2pManager wifiP2pManager;
    public WifiP2pManager.Channel wifiDirectChannel;

    private boolean mWriteWithAnswer;

    private final BlockingQueue<CrtpPacket> mOutQueue = new LinkedBlockingQueue<CrtpPacket>();
    //TODO: Limit size of out queue to avoid "ReadBack" effect?
    private final BlockingQueue<CrtpPacket> mInQueue = new LinkedBlockingQueue<CrtpPacket>();


    public volatile boolean connected = false;

    public WifiDirect(MainActivity activity) {
        this.mContext = activity;

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


                                if (dev.deviceName.equals("Android_ea5c")) {
                                    Log.i(TAG, "Found Android_ea5c, setting dev...");
                                    Toast.makeText(mContext, "Ready to hit lower button.", Toast.LENGTH_LONG).show();

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

        //run discoverPeers() method of WifiP2pManager
        wifiP2pManager.discoverPeers(wifiDirectChannel, actionListener);
    }




    //request connection to a wifi direct peer
    public void connectTo(WifiP2pDevice device) {
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
                                    FileServerAsyncTask asyncServerSockInit = new FileServerAsyncTask();
                                    asyncServerSockInit.execute();
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
        //Show connecting toast
        notifyConnectionRequested();

        //Launch the comm thread
        startSendReceiveThread(); //CAUSING MAIN THD TO BLOCK
    }

    @Override
    public void disconnect() {
        mLogger.debug("WifiDirect: disconnect()");

        //Stop the comm thread, if it's up
        stopSendReceiveThread();

        //Avoid NPE because packets are still processed
        try {
            Thread.sleep(100);
        }

        catch (InterruptedException e) {
            mLogger.error("Interrupted during disconnect: " + e.getMessage());
        }

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

        /*
        try:
            self.out_queue.put(pk, True, 2)
        except Queue.Full:
            if self.link_error_callback:
                self.link_error_callback("RadioDriver: Could not send packet to copter")
        */

        //this.mOutQueue.addLast(packet);

        //add the passed packet to the out FIFO queue
        try {
            this.mOutQueue.put(packet);
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
            return mInQueue.poll((long) time, TimeUnit.SECONDS);
        }

        catch (InterruptedException e) {
            mLogger.error("InterruptedException: " + e.getMessage());
            return null;
        }
    }
    //END MAND METHODS FROM CRTPDRIVER..........................................................................................................................................


    /*
    public List<ConnectionData> scanInterface() {

        return scanInterface(mCradio, mUsbInterface);
    }

    //scan interface for crazyflies
    private static List<ConnectionData> scanInterface(Crazyradio crazyRadio, CrazyUsbInterface crazyUsbInterface) {
        List<ConnectionData> connectionDataList = new ArrayList<ConnectionData>();

        if(crazyRadio == null) {
            crazyRadio = new Crazyradio(crazyUsbInterface);
        } else {
            mLogger.error("Cannot scan for links while the link is open!");
            //TODO: throw exception?
        }

        mLogger.info("Found Crazyradio with version " + crazyRadio.getVersion() + " and serial number " + crazyRadio.getSerialNumber());

        crazyRadio.setArc(1);

//        crazyRadio.setDataRate(CrazyradioLink.DR_250KPS);
//        List<Integer> scanRadioChannels250k = crazyRadio.scanChannels();
//        for(Integer channel : scanRadioChannels250k) {
//            connectionDataList.add(new ConnectionData(channel, CrazyradioLink.DR_250KPS));
//        }
//        crazyRadio.setDataRate(CrazyradioLink.DR_1MPS);
//        List<Integer> scanRadioChannels1m = crazyRadio.scanChannels();
//        for(Integer channel : scanRadioChannels1m) {
//            connectionDataList.add(new ConnectionData(channel, CrazyradioLink.DR_1MPS));
//        }
//        crazyRadio.setDataRate(CrazyradioLink.DR_2MPS);
//        List<Integer> scanRadioChannels2m = crazyRadio.scanChannels();
//        for(Integer channel : scanRadioChannels2m) {
//            connectionDataList.add(new ConnectionData(channel, CrazyradioLink.DR_2MPS));
//        }

        try {
            connectionDataList = Arrays.asList(crazyRadio.scanChannels());
        } catch (IOException e) {
            mLogger.error(e.getMessage());
        }

//        crazyRadio.close();
//        crazyRadio = null;

        return connectionDataList;
    }

    public boolean scanSelected(ConnectionData connectionData, byte[] packet) {
        if (mCradio == null) {
            mCradio = new Crazyradio(mUsbInterface);
        }
        return mCradio.scanSelected(connectionData.getChannel(), connectionData.getDataRate(), packet);
    }*/


    //instantiate and start a new Thread that receives and sends packets
    private void startSendReceiveThread() {
        if (mRadioDriverThread == null) {
            //self._thread = _RadioDriverThread(self.cradio, self.in_queue, self.out_queue, link_quality_callback, link_error_callback)
            WifiDirect.RadioDriverThread rDT = new WifiDirect.RadioDriverThread();

            //run on separate (non-UI) thread to avoid blocking UI
            mRadioDriverThread = new Thread(rDT);
            mRadioDriverThread.start();
        }
    }

    //stop the sender/receiver Thread by calling interrupt() to end the loop
    private void stopSendReceiveThread() {
        if (this.mRadioDriverThread != null) {
            this.mRadioDriverThread.interrupt();

            //set the Thread to null
            this.mRadioDriverThread = null;
        }
    }


    /**
     * Radio link receiver thread is used to read data from the Crazyradio USB driver.
     */
    private class RadioDriverThread implements Runnable {

        final Logger mLogger = LoggerFactory.getLogger(this.getClass().getSimpleName());

        //how many times to retry sending a packet and get back .isAck() as false before we disconnect
        private final static int RETRYCOUNT_BEFORE_DISCONNECT = 10;
        private int mRetryBeforeDisconnect;

        /**
         * Create the object
         */
        public RadioDriverThread() {
            this.mRetryBeforeDisconnect = RETRYCOUNT_BEFORE_DISCONNECT;
        }



        /**
         * Run the receiver thread
         *
         * (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        public void run() {
            //set dataOut byte array to null packet to start, which is just 0xff
            byte[] dataOut = Crazyradio.NULL_PACKET;

            double waitTime = 0;
            int emptyCtr = 0;


            //as long as the Thread isn't interrupted. (Basically runs infinitely)
            while (!Thread.currentThread().isInterrupted()) {
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


                    RadioAck ackStatus = sendPacketHelper(dataOut);                       //TODO: implement new packet sender for WIFI DIRECT SOCKET

                    //Analyze the data packet returned by the onboard Pixel (client) after the send. If there was no acknowledgment, something's wrong with comms
                    if (ackStatus == null) {
                        //No acknowledgement returned. Log stuff and disconnect everything
                        notifyConnectionLost("Dongle communication error (ackStatus == null)"); //calls disconnect() on MainPresenter instance
                        mLogger.warn("Dongle communication error (ackStatus == null)");
                        continue;
                    }

                    notifyLinkQualityUpdated((10 - ackStatus.getRetry()) * 10);

                    //If no copter, retry
                    //TODO: how is this actually possible?

                    //try sending again
                    if (!ackStatus.isAck()) {
                        this.mRetryBeforeDisconnect--;
                        if (this.mRetryBeforeDisconnect == 0) {
                            notifyConnectionLost("Too many packets lost");
                            mLogger.warn("Too many packets lost");
                        }

                        continue;
                    }
                    //if we made it here, ackStatus.isAck() was true, so copter in range

                    this.mRetryBeforeDisconnect = RETRYCOUNT_BEFORE_DISCONNECT;

                    //get the payload
                    byte[] data = ackStatus.getData();


                    //If there is a copter in range (ack had a payload), the incoming ack payload is analyzed and the next packet to send is prepared
                    if (data != null && data.length > 0) {
                        //create a packet from the ackStatus data payload
                        CrtpPacket inPacket = new CrtpPacket(data);

                        //put the packet on the IN blocking queue
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
                    }

                    //get the next packet to send after relaxation (wait 10ms)
                    CrtpPacket outPacket = mOutQueue.poll((long) waitTime, TimeUnit.SECONDS); //retrieves and removes head of FIFO queue, or returns NULL if time runs out b4 an item is avail
                    //waiting up to the specified wait time if necessary for an element to become available

                    //if we got something from the queue
                    if (outPacket != null) {
                        //convert the CRTP packet to array of bytes
                        dataOut = outPacket.toByteArray();
                    }

                    //otherwise set out to 0xff again
                    else {
                        dataOut = Crazyradio.NULL_PACKET;
                    }
                    //Thread.sleep(10);
                }


                //if we called to stop the Thread
                catch (InterruptedException e) {
                    mLogger.debug("RadioDriverThread was interrupted.");
                    notifyLinkQualityUpdated(0);
                    break;
                }
            }

        }
    }



    /**
     * Send a packet and receive the ACKNOWLEDGEMENT signal from the radio dongle (back from the drone).
     * The ack contains information about the packet transmission
     * and a data payload if the ack packet contained any
     *
     * @param dataOut bytes to send
     */
    public RadioAck sendPacketHelper(byte[] dataOut) { //was sendPacket
        RadioAck ackIn = null;
        byte[] data = new byte[33]; // 33?

       // Log.i(TAG, String.format("Sending packet of length %d", dataOut.length));


        //mUsbInterface.sendBulkTransfer(dataOut, data); //TODO: change to send the transfer over WIFI DIRECT SOCKET

        //if data is not None:
        ackIn = new RadioAck();

        //if there was a data payload in the ack
        if (data[0] != 0) {
            //make sure this is an Ack, set the isAck boolean appropriately
            ackIn.setAck((data[0] & 0x01) != 0);


            ackIn.setPowerDet((data[0] & 0x02) != 0);


            ackIn.setRetry(data[0] >> 4);

            //set the payload
            ackIn.setData(Arrays.copyOfRange(data, 1, data.length));
        }

        return ackIn;
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
    public static class FileServerAsyncTask extends AsyncTask<Void, Void, Void> { //params passed, progress update returned, final returned
        private Context context;
        private TextView statusText;

        private OutputStream outStream;
        private InputStream inStream;

        private ServerSocket serverSocket;

        @Override
        protected Void doInBackground(Void... params) {
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

                Log.d(TAG, "Server: reading in data...");

                byte[] in = new byte[10];

                //this call BLOCKS until data detected and read in
                int dataIn = inStream.read(in);

                Log.d(TAG, "Server: reading in data complete");
                Log.d(TAG, String.format("Got message from client: " + in[0]));
            }

            catch (IOException e) {
                e.printStackTrace();
            }

            finally {
                try {
                    //close up the socket
                    serverSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            return null;
        }

//--------------------------------------------------------------------END SERVER CODE------------------------------------------------------------------------


        //unused AsyncTask methods

        @Override
        protected void onPostExecute (Void result) {
        }


        @Override
        protected void onPreExecute() {
        }
    }



}
