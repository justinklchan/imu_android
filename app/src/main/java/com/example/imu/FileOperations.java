package com.example.imu;

import android.app.Activity;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

public class FileOperations {

    /**
     * Writes one file per sensor as {@code <fname>-<key>.txt}, each line
     * {@code t,x,y,z} where t is seconds since the capture started. The three
     * sensors deliver at independent rates, so the timestamp is what lets the
     * files be aligned afterwards.
     */
    public static void writetofile(Activity av, String fname, SensorChannel.Snapshot[] snapshots) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String dir = av.getExternalFilesDir(null).toString();
                File path = new File(dir);
                if (!path.exists()) {
                    path.mkdirs();
                }
                for (SensorChannel.Snapshot snapshot : snapshots) {
                    write(dir, fname, snapshot);
                }
            }
        }).start();
    }

    private static void write(String dir, String fname, SensorChannel.Snapshot s) {
        File file = new File(dir, fname + "-" + s.key + ".txt");
        BufferedWriter outfile = null;
        try {
            outfile = new BufferedWriter(new FileWriter(file, false));
            StringBuilder line = new StringBuilder(48);
            for (int i = 0; i < s.n; i++) {
                line.setLength(0);
                line.append(String.format(Locale.US, "%.6f", s.t[i])).append(',')
                        .append(s.x[i]).append(',')
                        .append(s.y[i]).append(',')
                        .append(s.z[i]);
                outfile.append(line);
                outfile.newLine();
            }
            outfile.flush();
        } catch (Exception e) {
            Log.e("ex", "writeRecToDisk " + s.key);
            Log.e("ex", String.valueOf(e.getMessage()));
        } finally {
            if (outfile != null) {
                try {
                    outfile.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
