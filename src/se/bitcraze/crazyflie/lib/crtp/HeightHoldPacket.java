package se.bitcraze.crazyflie.lib.crtp;

import java.nio.ByteBuffer;

public class HeightHoldPacket extends CrtpPacket {
    private final float mRoll;
    private final float mPitch;
    private final float mYawrate;
    private final float mHeight;

    /**
     * Create a new Guojun height hold packet.
     *
     * @param roll (Deg.)
     * @param pitch (Deg.)
     * @param yaw (Deg./s)
     * @param height (m)
     */
    public HeightHoldPacket(float roll, float pitch, float yaw, float height) {
        //heighthold is Port 9, channel 1
        super(1, CrtpPort.COMMANDER_POSHOLD);

        this.mRoll = roll; // * (float)(Math.PI / 180.0);
        this.mPitch = pitch; // * (float)(Math.PI / 180.0);
        this.mYawrate = yaw; // * (float)(Math.PI / 180.0);
        this.mHeight = height;
    }

    @Override
    protected void serializeData(ByteBuffer buffer) {
        //type: also 9
        buffer.put((byte) 0x09);

        buffer.putFloat(mRoll);
        buffer.putFloat(-mPitch); //invert axis
        buffer.putFloat(mYawrate);
        buffer.putFloat(mHeight);
    }

    @Override
    protected int getDataByteCount() {
        return 1 + 4 * 4; // 1 byte (type) + 4 floats with size 4 = 17 vs. 14 for CommanderPacket
    }

    @Override
    public String toString() {
        return "zDistancePacket: roll: " + this.mRoll + " pitch: " + this.mPitch + " yawrate: " +
                this.mYawrate + " zDistance: " + this.mHeight;
    }
}
