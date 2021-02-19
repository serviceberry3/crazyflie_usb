package se.bitcraze.crazyfliecontrol2;

public enum Signal {
    START_FOLLOW(1),
    STOP_FOLLOW(2);

    private byte mNumber;

    private Signal(int number) {
        this.mNumber = (byte) number;
    }

    /**
     * Get the number associated with this port.
     *
     * @return the number of the port
     */
    public byte getNumber() {
        return mNumber;
    }
}
