Crazyflie client app for Android, modified to send control commands over Wifi Direct to a second onboard phone.

On the onboard phone (which is plugged into drone via USB), open the wifidirect_ubuntu2pixel app and hit "Discover Peers."

Open this client app on the controller phone and hit the upper "connect" button (icon of two arrows) to start peer discovery.

Wait until you see the toast "Ready to hit lower button" and you see the name of your Android device in the list on the onboard phone. (You need to modify line 81 of WifiDirect.java in the client to match name of your device.)

Simultaneously tap the lower connect button on the client and the name of the discovered device on the onboard phone.

Sometimes the app crashes when trying to connect; if so, try again. Otherwise you should see the toast "Connection established successfully!" on the onboard phone's screen.