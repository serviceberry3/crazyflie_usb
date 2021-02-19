package se.bitcraze.crazyfliecontrol2;

import se.bitcraze.crazyflie.lib.crazyflie.Crazyflie;
import se.bitcraze.crazyflie.lib.crtp.CrtpPacket;
import se.bitcraze.crazyfliecontrol.controller.WifiDirect;

/** Class to send high-level signals only from controller device->onboard phone,
 * indicating for the onboard phone to open a human tracking session, take other actions,
 * etc.
 * */
public class Flagger {
    private Crazyflie mCrazyflie;
    private WifiDirect wifiDirectDriver;


    public Flagger(Crazyflie mCrazyflie, WifiDirect wifiDirectDriver) {
        this.mCrazyflie = mCrazyflie;
        this.wifiDirectDriver = wifiDirectDriver;
    }

    public void sendStartFollowSignal() {
        wifiDirectDriver.sendPacket(new CrtpPacket(Signal.START_FOLLOW));
    }

    public void sendStopFollowSignal() {
        wifiDirectDriver.sendPacket(new CrtpPacket(Signal.STOP_FOLLOW));
    }
}
