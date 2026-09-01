package com.example.imu;

import android.hardware.Sensor;

import java.util.Arrays;

/**
 * One sensor stream: its identity, units, and the buffer of samples captured
 * during the current recording.
 *
 * <p>Samples are held in parallel primitive arrays grown geometrically, so a
 * long capture at {@code SENSOR_DELAY_FASTEST} does not allocate per sample.
 */
public class SensorChannel {

    /** An immutable view of a finished capture, safe to hand to a writer thread. */
    public static final class Snapshot {
        public final String key;
        public final double[] t;
        public final float[] x, y, z;
        public final int n;

        Snapshot(String key, double[] t, float[] x, float[] y, float[] z, int n) {
            this.key = key;
            this.t = t;
            this.x = x;
            this.y = y;
            this.z = z;
            this.n = n;
        }
    }

    private static final int INITIAL_CAPACITY = 4096;

    /** {@code Sensor.TYPE_*} constant this channel listens to. */
    public final int type;
    /** Short name used as the output file suffix, e.g. {@code accel} -> {@code <ts>-accel.txt}. */
    public final String key;
    /** Full name shown on the panel, e.g. "Accelerometer". */
    public final String label;
    /** Unit the recorded values are in, e.g. "g". */
    public final String unit;
    /** Raw sensor values are divided by this to reach {@link #unit}. */
    public final float scale;

    /** Null when the device does not have this sensor. */
    public Sensor sensor;
    /** Whether this channel's panel is currently shown. Recording ignores this. */
    public boolean visible = true;

    private double[] t = new double[INITIAL_CAPACITY];
    private float[] x = new float[INITIAL_CAPACITY];
    private float[] y = new float[INITIAL_CAPACITY];
    private float[] z = new float[INITIAL_CAPACITY];
    private int n = 0;

    public SensorChannel(int type, String key, String label, String unit, float scale) {
        this.type = type;
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.scale = scale;
    }

    public boolean isPresent() {
        return sensor != null;
    }

    public int count() {
        return n;
    }

    /** Discards any previously buffered samples. Called when a capture starts. */
    public void clear() {
        n = 0;
    }

    public void add(double seconds, float vx, float vy, float vz) {
        if (n == t.length) {
            grow();
        }
        t[n] = seconds;
        x[n] = vx;
        y[n] = vy;
        z[n] = vz;
        n++;
    }

    /**
     * Hands off the current buffer and installs fresh arrays, so a background
     * writer can never race a capture that starts straight afterwards.
     */
    public Snapshot takeSnapshot() {
        Snapshot snapshot = new Snapshot(key, t, x, y, z, n);
        t = new double[INITIAL_CAPACITY];
        x = new float[INITIAL_CAPACITY];
        y = new float[INITIAL_CAPACITY];
        z = new float[INITIAL_CAPACITY];
        n = 0;
        return snapshot;
    }

    private void grow() {
        int capacity = t.length * 2;
        t = Arrays.copyOf(t, capacity);
        x = Arrays.copyOf(x, capacity);
        y = Arrays.copyOf(y, capacity);
        z = Arrays.copyOf(z, capacity);
    }
}
