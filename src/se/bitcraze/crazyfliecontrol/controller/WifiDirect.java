package se.bitcraze.crazyfliecontrol.controller;

import android.Manifest;
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

import se.bitcraze.crazyfliecontrol2.MainActivity;
import se.bitcraze.crazyfliecontrol2.R;

public class WifiDirect {

    private static final String TAG = "WifiDirect";
    private MainActivity mainActivity;

    private ListView listView;
    private ArrayAdapter aa;
    private TextView tv;
    private Button buttonDiscover;

    IntentFilter peerfilter;
    IntentFilter connectionfilter;
    IntentFilter p2pEnabled;

    private Handler handler = new Handler();

    //This class provides API for managing Wi-Fi peer-to-peer (Wifi Direct) connectivity. This lets app discover available peers,
    //setup connection to peers and query for list of peers. When a p2p connection is formed over wifi, the device continues
    //to maintain the uplink connection over mobile or any other available network for internet connectivity on the device.
    public WifiP2pManager wifiP2pManager;
    public WifiP2pManager.Channel wifiDirectChannel;

    public WifiDirect(MainActivity activity) {
        this.mainActivity = activity;
    }

    public void init() {
        //initialize the peer-to-peer (Wifi Direct) connection manager
        wifiP2pManager = (WifiP2pManager) mainActivity.getSystemService(Context.WIFI_P2P_SERVICE);

        //WifiP2pManager's initialize() fxn returns channel instance that is necessary for performing any further p2p operations
        wifiDirectChannel = wifiP2pManager.initialize(mainActivity, mainActivity.getMainLooper(),
                new WifiP2pManager.ChannelListener() {
                    public void onChannelDisconnected() {
                        //re-initialize the WifiDirect upon disconnection
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
            String errorMessage = "WiFi Direct Failed: ";


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
            Log.d(TAG, errorMessage);
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
                        deviceList.clear();
                        deviceList.addAll(peers.getDeviceList());
                        aa.notifyDataSetChanged();
                    }
                });
    }

    //discover Wifi Direct peers
    //An initiated discovery request from an app stays active until device starts connecting to a peer, forms a p2p group, or there's an explicit
    //stopPeerDiscovery(). Apps can listen to WIFI_P2P_DISCOVERY_CHANGED_ACTION to know if a peer-to-peer discovery is running or stopped.
    //WIFI_P2P_PEERS_CHANGED_ACTION indicates if peer list has changed
    private void discoverPeers() {
        //make sure we have permission
        if (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "discoverPeers(): ACCESS_FINE_LOCATION not granted");
            return;
        }

        Log.i(TAG, "Running discoverPeers()...");
        //run discoverPeers() method of WifiP2pManager
        wifiP2pManager.discoverPeers(wifiDirectChannel, actionListener);
    }




    //request connection to a wifi direct peer
    private void connectTo(WifiP2pDevice device) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;

        if (ActivityCompat.checkSelfPermission(mainActivity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        wifiP2pManager.connect(wifiDirectChannel, config, actionListener);
    }

    public void connectionChanged(Intent intent) {
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
                                    //initiateServerSocket();


                                    //create ServerSocket in background and wait for client to connect
                                    FileServerAsyncTask asyncServerSockInit = new FileServerAsyncTask();
                                    asyncServerSockInit.execute();
                                }


                                //If we're the client
                                else if (info.groupFormed) {
                                    Log.i(TAG, "We're the client");

                                    initiateClientSocket(info.groupOwnerAddress.getHostAddress());
                                }
                            }
                        }
                    });
        }

        else {
            Log.d(TAG, "Wi-Fi Direct Disconnected");
        }
    }


    private void initiateServerSocket() {
        ServerSocket serverSocket;
        try {
            //instantiate a ServerSocket
            serverSocket = new ServerSocket(8988);
            Socket serverClient = serverSocket.accept();
        }

        catch (IOException e) {
            Log.e(TAG, "I/O Exception", e);
        }
    }


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

                    success = 1;
                    Log.i(TAG, "Client-server connection successful!!");

                    //get resources to output stuff to the client's input stream
                    //out = new PrintWriter(socket.getOutputStream(), true);

                    //get the client's input stream (incoming data to client)
                    //in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                    /*
                    outStream = socket.getOutputStream();
                    inStream = socket.getInputStream();

                    long start = System.currentTimeMillis();

                    //Log.i(TAG, "Client: sending 48 to server...");

                    //ping the server
                    //out.print(48);
                    outStream.write(48);

                    //Log.i(TAG, "Client: data sent to server complete, now reading...");

                    //int got = in.read();

                    int got = inStream.read();

                    //Log.i(TAG, "Client: readback complete");

                    long end = System.currentTimeMillis();

                    Log.i(TAG, String.format("Got %d back from server after %d ms", got, (end - start) ));

                    /*
                    //Create a byte stream from a JPEG file and pipe it to the output stream
                    //of the socket. This data is retrieved by the server device.
                    OutputStream outputStream = socket.getOutputStream();
                    ContentResolver cr = MainActivity.this.getApplicationContext().getContentResolver();

                    //write a 4 into the stream
                    outputStream.write(bytes);

                    //close the stream
                    outputStream.close();
                     */
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


    private List<WifiP2pDevice> deviceList = new ArrayList<>();



    //Server socket that initializes in background and accepts connection and reads data from client (use of AsyncTask here is probly stupid)
    public static class FileServerAsyncTask extends AsyncTask<Void, Void, Void> { //params passed, progress update returned, final returned
        private Context context;
        private TextView statusText;
        private BufferedReader in;
        private PrintWriter out;

        private OutputStream outStream;
        private InputStream inStream;

        @Override
        protected Void doInBackground(Void... params) {
            try {
                //Create a server socket and wait for client connections. This
                //call blocks until a connection is accepted from a client
                ServerSocket serverSocket = new ServerSocket(8988);

                Log.d(TAG, "Server: Socket opened port 8988, waiting for client");

                Log.i(TAG, "Address: " + serverSocket.getLocalSocketAddress());

                //String hostname =

                //serverSocket.bind();

                //block until connection from client comes through
                Socket client = serverSocket.accept();

                Log.d(TAG, "Server: connection done");

                //get stream to output stuff to the client's input stream
                //out = new PrintWriter(client.getOutputStream(), true);

                //get stream to read stuff in from the client's output stream
                //in = new BufferedReader(new InputStreamReader(client.getInputStream()));

                /*
                outStream = client.getOutputStream();
                inStream = client.getInputStream();

                Log.d(TAG, "Server: reading in data...");

                //wait for data
                //int greeting = in.read();
                int greeting = inStream.read();

                Log.d(TAG, "Server: reading in data complete");

                if (greeting == 48) {
                    //out.print(50);
                    outStream.write(50);
                }


                else {
                    Log.i(TAG, "Server: no data found");
                    //out.println("Unrecognized greeting");
                }


                /*
                byte[] in = new byte[10];

                //now a client has initialized and transferred/output data via stream
                //now we want to save the input stream from the client
                InputStream inputStream = client.getInputStream();

                int charsRead = inputStream.read(in);

                Log.d(TAG, String.format("Got message from client: " + in[0]));

                 */

                //serverSocket.close();
            }

            catch (IOException e) {
                e.printStackTrace();
            }

            return null;
        }


        @Override
        protected void onPostExecute (Void result) {
        }


        @Override
        protected void onPreExecute() {
        }
    }



}
