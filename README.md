Crazyflie client app for Android, modified to send control commands over Wifi Direct to a second onboard phone which relays them via USB to the drone.

# UPDATES #
02/19/21: Launch and Land buttons, as well as all Kill functionality, completed.


# Connection instructions #
On the onboard phone (which is plugged into drone via USB), open the [crazyflie_receiver](https://github.com/serviceberry3/crazyflie_receiver) app and hit "Discover Peers."

Open the cf-usb app on the controller phone and hit the upper "Discover Wifi peers" button to start peer discovery.

Wait until you see the toast "Onboard Pixel found" and you see the name of your Android device (the one running cf-usb) in the list on the onboard phone. (You need to modify line 81 of WifiDirect.java in the client to match name of your device.)

Simultaneously tap the "Rqst conn to onboard phone" button on the client and the name of the discovered device on the onboard phone.

After a moment, you should see the toast "Connection established successfully!" on the onboard phone's screen. If it doesn't work, try restarting both apps and repeating the process outlined above.


# Other instructions #
To run a script that launches the Crazyflie up to a hovering position, press the "Launch" button. The Crazyflie will hover indefinitely at TARG_HEIGHT.

To run a script that safely lands the Crazyflie from the height attained via "Launch," press the "Land" button.

