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

package se.bitcraze.crazyflie.lib.crazyflie;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.bitcraze.crazyflie.lib.crazyradio.ConnectionData;
import se.bitcraze.crazyflie.lib.crazyradio.RadioDriver;
import se.bitcraze.crazyflie.lib.crtp.CommanderPacket;
import se.bitcraze.crazyflie.lib.crtp.CrtpDriver;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyflie.lib.crtp.CrtpPort;
import se.bitcraze.crazyflie.lib.log.Logg;
import se.bitcraze.crazyflie.lib.param.Param;
import se.bitcraze.crazyflie.lib.toc.TocCache;
import se.bitcraze.crazyflie.lib.toc.TocFetchFinishedListener;
import se.bitcraze.crazyfliecontrol2.MainActivity;
import se.bitcraze.crazyfliecontrol2.MainPresenter;

public class Crazyflie {

    private final Logger mLogger = LoggerFactory.getLogger("Crazyflie");

    private CrtpDriver mDriver;
    private Thread mIncomingPacketHandlerThread;

    private LinkedBlockingDeque<CrtpPacket> mResendQueue = new LinkedBlockingDeque<CrtpPacket>();
    private Thread mResendQueueHandlerThread;

    private Set<DataListener> mDataListeners = new CopyOnWriteArraySet<DataListener>();

    private State mState = State.DISCONNECTED;

    private ConnectionData mConnectionData;

    private Param mParam;
    private Logg mLogg;
    private TocCache mTocCache;

    private MainPresenter mainPresenter;

    /**
     * State of the connection procedure
     */
    public enum State {
        DISCONNECTED,
        INITIALIZED,
        CONNECTED,
        SETUP_FINISHED
    }

    /* used in examples */
    public State getState() {
        return mState;
    }

    /**
     * Crazyflie constructor
     *
     * @param driver driver to use (e.g. RadioDriver or BleLink)
     */
    public Crazyflie(CrtpDriver driver) {
        this(driver, null, null);
    }

    /**
     * Crazyflie constructor
     *
     * @param driver driver to use (e.g. RadioDriver or BleLink)
     * @param tocCacheDir TOC cache files dir
     */
    public Crazyflie(CrtpDriver driver, File tocCacheDir, MainPresenter mainPresenter) {
        this.mDriver = driver;
        this.mTocCache = new TocCache(tocCacheDir);
        this.mainPresenter = mainPresenter;
    }

    public void connect() {
        mLogger.debug("connect()");
        mState = State.INITIALIZED;

        /*
        // try to connect
        try {
            if (mDriver instanceof RadioDriver) {
                if (mConnectionData == null) {
                    throw new IllegalStateException("ConnectionData must be set for Crazyradio connections!");
                }
                ((RadioDriver) mDriver).setConnectionData(mConnectionData);
            }
            mDriver.connect(); ****important call
        }

        catch (IOException | IllegalArgumentException ioe) {
            mLogger.debug(ioe.getMessage());
            //notifyConnectionFailed("Connection failed: " + ioe.getMessage());
            disconnect();
        }*/


        //CAUSES BLOCK ON MAIN THRD
        //WifiDirect version: replicate only the driver.connect() call, ignoring radio connection data
        try {
            mDriver.connect(); //startSendReceiveThread() should have been called on the driver here
        }

        catch (IOException ioe) {
            ioe.printStackTrace();
            mLogger.debug(ioe.getMessage());
            //notifyConnectionFailed("Connection failed: " + ioe.getMessage());
            disconnect();
        }


        //instantiate and start IncomingPacketHandler on new Thread
        if (mIncomingPacketHandlerThread == null) {
            IncomingPacketHandler iph = new IncomingPacketHandler();
            mIncomingPacketHandlerThread = new Thread(iph);
            mIncomingPacketHandlerThread.start();
        }

        //instantiate and start ResendQueueHandler on new Thread
        if (mResendQueueHandlerThread == null) {
            ResendQueueHandler rqh = new ResendQueueHandler();
            mResendQueueHandlerThread = new Thread(rqh);
            mResendQueueHandlerThread.start();
        }
    }

    public void disconnect() {
        mLogger.debug("Crazyflie: disconnect()");


        if (mState != State.DISCONNECTED) {
            if (mDriver.isConnected()) {
                //Send commander packet with all values set to 0 before closing the connection
                Log.i("CRFLIE", "Sending shutdown commander packet to drone...");
                sendPacket(new CommanderPacket(0, 0, 0, (char) 0));
            }

            //call disconnect on WifiDirect
            mDriver.disconnect();

            if (mIncomingPacketHandlerThread != null) {
                mIncomingPacketHandlerThread.interrupt();
            }
            if (mResendQueueHandlerThread != null) {
                mResendQueueHandlerThread.interrupt();
            }

            //switch state
            mState = State.DISCONNECTED;
        }
    }

    //TODO: is this good enough?
    public boolean isConnected() {
        return mState == State.SETUP_FINISHED;
    }

    public void setConnectionData(ConnectionData connectionData) {
        this.mConnectionData = connectionData;
    }

    /**
     * Send a packet to drone through the driver interface
     *
     * @param packet packet to send to the Crazyflie
     */
    // def send_packet(self, pk, expected_reply=(), resend=False):
    public void sendPacket(CrtpPacket packet) {
        if (mDriver.isConnected()) {
            if (packet == null) {
                mLogger.warn("Packet is null.");
                return;
            }

            //Log.i("CRAZYFLIe", "sending packet...");
            //send the packet to the CrazyFlie
            mDriver.sendPacket(packet);

            //CHANGED: IGNORE RESENDS FOR NOW
            /*
            if (packet.getExpectedReply() != null && packet.getExpectedReply().length > 0) {
                //add packet to resend queue
                if (!mResendQueue.contains(packet)) {
                    mResendQueue.add(packet);
                }

                else {
                    mLogger.warn("Packet already exists in ResendQueue.");
                }
            }*/
        }
    }

    /**
     * Callback called for every packet received to check if we are
     * waiting for a packet like this. If so, then remove it from the queue.
     *
     * @param packet received packet
     */
    private void checkReceivedPackets(CrtpPacket packet) {
        // compare received packet with expectedReplies in resend queue
        for (CrtpPacket resendQueuePacket : mResendQueue) {
            if (isPacketMatchingExpectedReply(resendQueuePacket, packet)) {
                mResendQueue.remove(resendQueuePacket);
                //mLogger.debug("QUEUE REMOVE: " + resendQueuePacket);
                break;
            }
        }
    }

    private boolean isPacketMatchingExpectedReply(CrtpPacket resendQueuePacket, CrtpPacket packet) {
        //Only check equality for the amount of bytes in expected reply
        byte[] expectedReply = resendQueuePacket.getExpectedReply();
        for (int i = 0; i < expectedReply.length;i++) {
            if (expectedReply[i] != packet.getPayload()[i]) {
                return false;
            }
        }
        return true;
    }

    private class ResendQueueHandler implements Runnable {

        public void run() {
            mLogger.debug("ResendQueueHandlerThread was started.");

            //infinitely check to see if there's anything in the resend queue and resend it
            while (true) {
                if (!mResendQueue.isEmpty()) {
                    //remove a packet from the resend FIFO queue
                    CrtpPacket resendPacket = mResendQueue.poll();

                    //resend the removed packet
                    if (resendPacket != null) {
                        mLogger.debug("RESEND: {} ID: {}", resendPacket, resendPacket.getPayload()[0]);

                        //call sendPacket on the driver
                        sendPacket(resendPacket);
                    }
                }
                try {
                    Thread.sleep(500);
                }


                catch (InterruptedException e) {
                    mLogger.debug("ResendQueueHandlerThread was interrupted.");
                    break;
                }
            }
        }

    }


    /**
     * Called when first packet arrives from Crazyflie.
     * This is used to determine if we are connected to something that is answering.
     *
     *
     * @param packet initial packet
     */
    private void checkForInitialPacketCallback(CrtpPacket packet) {
        // mLogger.info("checkForInitialPacketCallback...");
        //TODO: should be made more reliable
        if (this.mState == State.INITIALIZED) {
            mLogger.info("Initial packet has been received! => State.CONNECTED");

            //set state to CONNECTED
            this.mState = State.CONNECTED;
            //self.link_established.call(self.link_uri)

            //FIXME: Crazyflie should not call mDriver.notifyConnected()
            this.mDriver.notifyConnected();

            //start the setup, which in turn starts the joystick data sending thread in MainPresenter
            startConnectionSetup();
        }
        //self.packet_received.remove_callback(self._check_for_initial_packet_cb)
        // => IncomingPacketHandler
    }

    /**
     * Hack to circumvent BLE reconnect issue
     */
    public void startConnectionSetup_BLE() {
        if (this.mState == State.INITIALIZED) {
            this.mState = State.CONNECTED;
            startConnectionSetup();
        }
    }

    public CrtpDriver getDriver() {
        return mDriver;
    }

    public MainPresenter getMainPresenter() {
        return mainPresenter;
    }


    /**
     * Start the connection setup by refreshing the TOCs
     */
    private void startConnectionSetup() {
        String connection = "";
        if (mConnectionData != null) {
            connection = mConnectionData.toString();
        }

        else {
            connection = "BLE";
        }
        mLogger.info("We are connected [{}], requesting connection setup...", connection);

        mParam = new Param(this);
        //must be defined first to be usable in Log TocFetchFinishedListener
        final TocFetchFinishedListener paramTocFetchFinishedListener = new TocFetchFinishedListener(CrtpPort.PARAMETERS) {
            public void tocFetchFinished() {
                //_param_toc_updated_cb(self):
                mLogger.info("Param TOC finished updating.");
                //mParam.requestUpdateOfAllParams();
                //TODO: should be set only after log, param, mems are all updated
                mState = State.SETUP_FINISHED;
                //TODO: fix hacky-di-hack


                Log.i("CRAZYFLIE", "tocFetchFinished calling setup finished");
                //setup finished callback of the driver's plugged ConnectionAdapter. starts the joystick data sending thread
                mDriver.notifySetupFinished();
            }
        };

        mLogg = new Logg(this);
        TocFetchFinishedListener loggTocFetchFinishedListener = new TocFetchFinishedListener(CrtpPort.LOGGING) {
            public void tocFetchFinished() {
                mLogger.info("Logg TOC finished updating.");
                //after log toc has been fetched, fetch param toc
                mParam.refreshToc(paramTocFetchFinishedListener, mTocCache);
            }
        };
        //mLog.refreshToc(self._log_toc_updated_cb, self._toc_cache);
        if (mDriver instanceof RadioDriver) {
            mLogg.refreshToc(loggTocFetchFinishedListener, mTocCache);
        }

        else {
            //TODO: shortcut for BLELink
            mState = State.SETUP_FINISHED; //important, otherwise BLE keeps trying to reconnect
            mDriver.notifySetupFinished();
        }

        //TODO: self.mem.refresh(self._mems_updated_cb)
    }

    public Param getParam() {
        return mParam;
    }

    public Logg getLogg() {
        return mLogg;
    }

    public void clearTocCache() {
        mTocCache.clear();
    }

    public void setParamValue(String completeName, Number value) {
        if (mParam != null) {
            mParam.setValue(completeName, value);
        }
    }

    /** DATA LISTENER **/

    /**
     * Add a data listener for data that comes on a specific port
     *
     * @param dataListener datalistener that should be added
     */
    public void addDataListener(DataListener dataListener) {
        if (dataListener != null) {
            mLogger.debug("Adding data listener for port [" + dataListener.getPort() + "]");
            this.mDataListeners.add(dataListener);
        }
    }

    /**
     * Remove a data listener for data that comes on a specific port
     *
     * @param dataListener datalistener that should be removed
     */
    public void removeDataListener(DataListener dataListener) {
        if (dataListener != null) {
            mLogger.debug("Removing data listener for port [" + dataListener.getPort() + "]");
            this.mDataListeners.remove(dataListener);
        }
    }

    //public void removeDataListener(CrtpPort); ?

    /**
     * Notify data listeners that a packet was received
     *
     * @param packet received packet
     */
    private void notifyDataReceived(CrtpPacket packet) {
        boolean found = false;

        //got through all of the datalisteners attached to this Crazyflie object
        for (DataListener dataListener : mDataListeners) {
            //if this packet goes to this DataListener, call dataReceived on that Listener
            if (dataListener.getPort() == packet.getHeader().getPort()) {
                dataListener.dataReceived(packet);
                found = true;
            }
        }
        if (!found) {
            //mLogger.warn("Got packet on port [" + packet.getHeader().getPort() + "] but found no data listener to handle it.");
        }
    }

    /**
     * Handles incoming packets and sends the data to the correct listeners
     */
    private class IncomingPacketHandler implements Runnable {

        final Logger mLogger = LoggerFactory.getLogger("IncomingPacketHandler");


        public void run() {
            //mLogger.debug("IncomingPacketHandlerThread was started.");

            //force start joystick data thread
            checkForInitialPacketCallback(null);

            /*
            //as long as the Thread isn't interrupted
            while (!Thread.currentThread().isInterrupted()) {
                //receive an ack packet from the driver (the oldest packet that was received from the drone)
                CrtpPacket packet = getDriver().receivePacket(1);

                //make sure packet is non-null
                if (packet != null) {
                    //All-packet callbacks
                    //self.cf.packet_received.call(pk)

                    //let's see if this is the first packet arriving from the drone
                    //checkForInitialPacketCallback(packet);

                    //check if we were waiting for a packet like this
                    checkReceivedPackets(packet);

                    //dispatch the packet to listener
                    notifyDataReceived(packet);
                }
                else {
                    Log.e("CFLIE", "PACKET NULL TIMEOUT");
                }
            }*/
            //mLogger.debug("IncomingPacketHandlerThread was interrupted.");
        }

    }

}
