package se.bitcraze.crazyfliecontrol2;

import se.bitcraze.crazyflie.lib.crazyflie.Crazyflie;
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
}
