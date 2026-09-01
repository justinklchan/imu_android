package com.example.imu;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;

import com.google.android.material.button.MaterialButton;

/**
 * Records the accelerometer, gyroscope and magnetometer simultaneously, and
 * shows a live trace for whichever of them the display chips have switched on.
 */
public class MainActivity extends AppCompatActivity implements SensorEventListener {

    /**
     * Chart x values are seconds, not sample indices: the three sensors run at
     * different rates, so a fixed sample count would span a different duration
     * on every panel and scroll at a different speed.
     */
    private static final float[] WINDOW_CHOICES = {5f, 10f, 20f, 30f, 60f};
    private static final int DEFAULT_WINDOW_INDEX = 1;
    /** Seconds of trace kept behind the live window, reachable by panning back. */
    private static final float HISTORY_SECONDS = 60f;
    /**
     * Plotting every sample of a 400 Hz sensor is wasted work: the chart is
     * ~1000 px wide, so anything past this rate cannot be told apart on screen.
     * Recording is unaffected and stays at the sensor's full rate.
     */
    private static final double DISPLAY_HZ = 60d;

    /** Chart redraws and readout updates are throttled to stay legible and smooth. */
    private static final long CHART_FRAME_MS = 40;
    private static final long READOUT_FRAME_MS = 100;

    /** Accelerometer values are reported in m/s^2; divide to get g. */
    private static final float GRAVITY = 9.81f;

    private static final long NO_BASE = Long.MIN_VALUE;

    private SensorManager sensorManager;
    private SensorChannel[] channels;
    private Panel[] panels;

    private MaterialButton startButton, stopButton;
    private TextView statusText, sampleCount, windowPill;
    private View statusDot;
    private ObjectAnimator dotPulse;

    private boolean recording = false;
    private int windowIndex = DEFAULT_WINDOW_INDEX;
    /**
     * Timestamp of the first sample, taken from the sensor's own clock so the
     * file times and the chart's x axis share one origin. Reset on Record.
     */
    private long baseNanos = NO_BASE;
    private long recordStartedAt = 0;
    private long lastFooterDraw = 0;

    /** The views and chart state backing one {@link SensorChannel}. */
    private final class Panel {
        final SensorChannel channel;
        final View root;
        final TextView valueX, valueY, valueZ, reset;
        final LineChart chart;
        final TextView chip;

        LineDataSet setX, setY, setZ;
        LineData lineData;
        /** Seconds of the newest plotted sample, and of the last one plotted. */
        float lastX = 0f;
        double lastPlotted = Double.NEGATIVE_INFINITY;
        long lastChartDraw = 0;
        long lastReadoutDraw = 0;
        /** True once the trace has been zoomed or panned, which stops live follow. */
        boolean viewAdjusted = false;

        Panel(SensorChannel channel, int rootId, int chipId, int titleRes) {
            this.channel = channel;
            this.root = findViewById(rootId);
            this.chip = findViewById(chipId);
            this.reset = root.findViewById(R.id.panelReset);
            this.valueX = root.findViewById(R.id.panelValueX);
            this.valueY = root.findViewById(R.id.panelValueY);
            this.valueZ = root.findViewById(R.id.panelValueZ);
            this.chart = root.findViewById(R.id.panelChart);
            ((TextView) root.findViewById(R.id.panelTitle)).setText(titleRes);
            ((TextView) root.findViewById(R.id.panelUnit)).setText(channel.unit);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startButton = findViewById(R.id.button);
        stopButton = findViewById(R.id.button2);
        statusText = findViewById(R.id.statusText);
        statusDot = findViewById(R.id.statusDot);
        sampleCount = findViewById(R.id.sampleCount);
        windowPill = findViewById(R.id.windowPill);

        channels = new SensorChannel[]{
                new SensorChannel(Sensor.TYPE_ACCELEROMETER, "accel", "Accelerometer", "g", GRAVITY),
                new SensorChannel(Sensor.TYPE_GYROSCOPE, "gyro", "Gyroscope", "rad/s", 1f),
                new SensorChannel(Sensor.TYPE_MAGNETIC_FIELD, "mag", "Magnetometer", "µT", 1f),
        };

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        for (SensorChannel channel : channels) {
            channel.sensor = sensorManager.getDefaultSensor(channel.type);
        }

        panels = new Panel[]{
                new Panel(channels[0], R.id.panelAccel, R.id.chipAccel, R.string.sensor_accel),
                new Panel(channels[1], R.id.panelGyro, R.id.chipGyro, R.string.sensor_gyro),
                new Panel(channels[2], R.id.panelMag, R.id.chipMag, R.string.sensor_mag),
        };

        for (final Panel panel : panels) {
            styleChart(panel.chart);
            enableChartGestures(panel);
            resetTrace(panel);
            // A sensor the device does not have cannot be shown or recorded.
            if (!panel.channel.isPresent()) {
                panel.channel.visible = false;
                panel.chip.setEnabled(false);
            }
            panel.chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setPanelVisible(panel, !panel.channel.visible);
                }
            });
            applyPanelVisibility(panel);
        }

        windowPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                windowIndex = (windowIndex + 1) % WINDOW_CHOICES.length;
                applyWindow();
            }
        });
        applyWindow();

        startButton.setEnabled(hasAnySensor());
        stopButton.setEnabled(false);
        if (!hasAnySensor()) {
            showNoSensor();
        }

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startRecording();
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopRecording();
            }
        });
    }

    private float windowSeconds() {
        return WINDOW_CHOICES[windowIndex];
    }

    /** Applies the chosen span to every panel and puts them all back on live follow. */
    private void applyWindow() {
        windowPill.setText(getString(R.string.window_format, formatSeconds(windowSeconds())));
        for (Panel panel : panels) {
            setViewAdjusted(panel, false);
            panel.chart.fitScreen();
            followLive(panel);
            panel.chart.invalidate();
        }
    }

    private static String formatSeconds(float seconds) {
        if (seconds == Math.rint(seconds)) {
            return String.format(Locale.US, "%d s", (int) seconds);
        }
        return String.format(Locale.US, "%.1f s", seconds);
    }

    private boolean hasAnySensor() {
        for (SensorChannel channel : channels) {
            if (channel.isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void startRecording() {
        for (SensorChannel channel : channels) {
            channel.clear();
        }
        // A fresh origin, so chart seconds and the saved timestamps both start at 0.
        baseNanos = NO_BASE;
        for (Panel panel : panels) {
            setViewAdjusted(panel, false);
            resetTrace(panel);
        }
        recordStartedAt = SystemClock.elapsedRealtime();
        recording = true;

        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRecordingUi(true);
        updateFooter(true);
    }

    private void stopRecording() {
        recording = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRecordingUi(false);

        String fname = System.currentTimeMillis() + "";
        ArrayList<SensorChannel.Snapshot> taken = new ArrayList<>();
        StringBuilder counts = new StringBuilder();
        for (SensorChannel channel : channels) {
            if (!channel.isPresent()) {
                continue;
            }
            if (counts.length() > 0) {
                counts.append("  ");
            }
            counts.append(channel.key.substring(0, 1).toUpperCase(Locale.US))
                    .append(' ').append(String.format(Locale.US, "%,d", channel.count()));
            taken.add(channel.takeSnapshot());
        }
        sampleCount.setText(String.format(Locale.US, "Saved %s  ·  %s", fname, counts));
        FileOperations.writetofile(this, fname,
                taken.toArray(new SensorChannel.Snapshot[0]));
    }

    // --- display toggles ---------------------------------------------------

    private void setPanelVisible(Panel panel, boolean visible) {
        // Keep at least one panel on screen; the last one refuses to switch off.
        if (!visible && visiblePanelCount() == 1) {
            nudge(panel.chip);
            return;
        }
        panel.channel.visible = visible;
        applyPanelVisibility(panel);
    }

    private int visiblePanelCount() {
        int n = 0;
        for (SensorChannel channel : channels) {
            if (channel.visible) {
                n++;
            }
        }
        return n;
    }

    private void applyPanelVisibility(Panel panel) {
        panel.chip.setSelected(panel.channel.visible);
        panel.root.setVisibility(panel.channel.visible ? View.VISIBLE : View.GONE);
    }

    /** A short blink, so a refused toggle does not read as an unresponsive tap. */
    private void nudge(View view) {
        if (!animationsEnabled()) {
            return;
        }
        AlphaAnimation blink = new AlphaAnimation(1f, 0.4f);
        blink.setDuration(110);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(1);
        view.startAnimation(blink);
    }

    // --- chart ------------------------------------------------------------

    /** Dark instrument styling: hairline grid, no chrome, the traces carry the colour. */
    private void styleChart(LineChart chart) {
        int muted = ContextCompat.getColor(this, R.color.text_muted);
        int dim = ContextCompat.getColor(this, R.color.text_dim);
        int hairline = ContextCompat.getColor(this, R.color.hairline);

        chart.getDescription().setEnabled(false);
        chart.getLegend().setEnabled(false);
        chart.setNoDataText(getString(R.string.chart_empty));
        chart.setNoDataTextColor(dim);
        chart.setDrawGridBackground(false);
        chart.setDrawBorders(false);
        chart.setAutoScaleMinMaxEnabled(true);
        chart.setMinOffset(0f);
        chart.setExtraTopOffset(6f);
        chart.setExtraBottomOffset(2f);
        chart.setExtraRightOffset(8f);

        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(dim);
        xAxis.setTextSize(9f);
        xAxis.setDrawAxisLine(false);
        xAxis.setDrawGridLines(false);
        xAxis.setLabelCount(4, false);
        xAxis.setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                return String.format(Locale.US, "%.0fs", value);
            }
        });

        YAxis left = chart.getAxisLeft();
        left.setTextColor(muted);
        left.setTextSize(9f);
        left.setDrawAxisLine(false);
        left.setGridColor(hairline);
        left.setGridLineWidth(0.8f);
        left.enableGridDashedLine(4f, 6f, 0f);
        left.setDrawZeroLine(true);
        left.setZeroLineColor(hairline);
        left.setZeroLineWidth(1f);
        left.setLabelCount(3, false);

        chart.getAxisRight().setEnabled(false);
    }

    /**
     * Pinch to zoom, drag to pan. The first gesture takes the trace off live
     * follow so the view being inspected stops sliding; Reset view puts it back.
     */
    private void enableChartGestures(final Panel panel) {
        LineChart chart = panel.chart;
        chart.setTouchEnabled(true);
        chart.setDragEnabled(true);
        chart.setScaleEnabled(true);
        chart.setScaleXEnabled(true);
        chart.setScaleYEnabled(true);
        chart.setPinchZoom(true);
        chart.setDoubleTapToZoomEnabled(true);

        chart.setOnChartGestureListener(new OnChartGestureListener() {
            @Override
            public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture gesture) {
                if (gesture == ChartTouchListener.ChartGesture.DRAG
                        || gesture == ChartTouchListener.ChartGesture.X_ZOOM
                        || gesture == ChartTouchListener.ChartGesture.Y_ZOOM
                        || gesture == ChartTouchListener.ChartGesture.PINCH_ZOOM
                        || gesture == ChartTouchListener.ChartGesture.DOUBLE_TAP) {
                    setViewAdjusted(panel, true);
                }
            }

            @Override
            public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture gesture) { }

            @Override
            public void onChartLongPressed(MotionEvent me) { }

            @Override
            public void onChartDoubleTapped(MotionEvent me) { }

            @Override
            public void onChartSingleTapped(MotionEvent me) { }

            @Override
            public void onChartFling(MotionEvent e1, MotionEvent e2, float vx, float vy) { }

            @Override
            public void onChartScale(MotionEvent me, float scaleX, float scaleY) {
                setViewAdjusted(panel, true);
            }

            @Override
            public void onChartTranslate(MotionEvent me, float dx, float dy) {
                setViewAdjusted(panel, true);
            }
        });

        panel.reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setViewAdjusted(panel, false);
                followLive(panel);
                panel.chart.invalidate();
            }
        });
    }

    private void setViewAdjusted(Panel panel, boolean adjusted) {
        if (panel.viewAdjusted == adjusted) {
            return;
        }
        panel.viewAdjusted = adjusted;
        panel.reset.setVisibility(adjusted ? View.VISIBLE : View.GONE);
        if (adjusted) {
            // Relax the live cap so the whole buffered history can be reached.
            if (hasUsableRange(panel.chart)) {
                panel.chart.setVisibleXRangeMaximum(HISTORY_SECONDS);
            }
        } else {
            panel.chart.fitScreen();
        }
    }

    /** Pins the viewport to the newest {@link #windowSeconds()} of trace. */
    private void followLive(Panel panel) {
        LineChart chart = panel.chart;
        if (!hasUsableRange(chart)) {
            chart.invalidate();
            return;
        }
        chart.setVisibleXRangeMaximum(windowSeconds());
        chart.moveViewToX(Math.max(0f, panel.lastX - windowSeconds()));
    }

    /**
     * An empty chart reports an infinite x range, and feeding that to
     * setVisibleXRangeMaximum pins the viewport's minimum scale at
     * Float.MAX_VALUE permanently, leaving the chart blank forever.
     */
    private static boolean hasUsableRange(LineChart chart) {
        float range = chart.getXAxis().mAxisRange;
        return range > 0f && !Float.isInfinite(range) && !Float.isNaN(range);
    }

    private LineDataSet newSet(int colorRes) {
        LineDataSet set = new LineDataSet(new ArrayList<Entry>(), "");
        set.setDrawCircles(false);
        set.setDrawValues(false);
        set.setLineWidth(1.4f);
        set.setColor(ContextCompat.getColor(this, colorRes));
        set.setMode(LineDataSet.Mode.LINEAR);
        set.setHighlightEnabled(false);
        return set;
    }

    private void resetTrace(Panel panel) {
        panel.setX = newSet(R.color.channel_x);
        panel.setY = newSet(R.color.channel_y);
        panel.setZ = newSet(R.color.channel_z);
        panel.lineData = new LineData(panel.setX, panel.setY, panel.setZ);
        panel.lastX = 0f;
        panel.lastPlotted = Double.NEGATIVE_INFINITY;
        panel.chart.setData(panel.lineData);
        panel.chart.fitScreen();
        panel.chart.invalidate();
    }

    // --- status -----------------------------------------------------------

    private void setRecordingUi(boolean isRecording) {
        int color = ContextCompat.getColor(this,
                isRecording ? R.color.amber : R.color.text_dim);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        statusText.setText(isRecording ? R.string.status_recording : R.string.status_ready);
        statusText.setTextColor(ContextCompat.getColor(this,
                isRecording ? R.color.amber : R.color.text_muted));

        if (isRecording && animationsEnabled()) {
            dotPulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.25f);
            dotPulse.setDuration(700);
            dotPulse.setRepeatMode(ValueAnimator.REVERSE);
            dotPulse.setRepeatCount(ValueAnimator.INFINITE);
            dotPulse.start();
        } else if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
            statusDot.setAlpha(1f);
        }
    }

    private void showNoSensor() {
        statusText.setText(R.string.status_no_sensor);
        statusText.setTextColor(ContextCompat.getColor(this, R.color.channel_x));
        statusDot.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.channel_x)));
        sampleCount.setText(R.string.no_sensor_detail);
    }

    /** Honour the system "remove animations" accessibility setting. */
    private boolean animationsEnabled() {
        ContentResolver cr = getContentResolver();
        return Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    // --- sensor stream ----------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        Panel panel = panelFor(sensorEvent.sensor.getType());
        if (panel == null) {
            return;
        }
        if (baseNanos == NO_BASE) {
            baseNanos = sensorEvent.timestamp;
        }
        // One origin shared by every sensor, taken from the sensor clock itself,
        // so the three files line up with each other and with the chart axis.
        double t = (sensorEvent.timestamp - baseNanos) / 1e9d;

        SensorChannel channel = panel.channel;
        float x = sensorEvent.values[0] / channel.scale;
        float y = sensorEvent.values[1] / channel.scale;
        float z = sensorEvent.values[2] / channel.scale;

        if (recording) {
            channel.add(t, x, y, z);
        }

        // The trace and readout run whether or not we are recording, so the app
        // shows the sensors are working before anyone presses Record.
        appendToTrace(panel, t, x, y, z);
        updateReadouts(panel, x, y, z);
        updateFooter(false);
    }

    private Panel panelFor(int sensorType) {
        for (Panel panel : panels) {
            if (panel.channel.type == sensorType) {
                return panel;
            }
        }
        return null;
    }

    private void appendToTrace(Panel panel, double t, float x, float y, float z) {
        if (t - panel.lastPlotted < 1d / DISPLAY_HZ) {
            return;
        }
        panel.lastPlotted = t;
        panel.lastX = (float) t;

        panel.setX.addEntry(new Entry(panel.lastX, x));
        panel.setY.addEntry(new Entry(panel.lastX, y));
        panel.setZ.addEntry(new Entry(panel.lastX, z));
        while (panel.setX.getEntryCount() > 1
                && panel.lastX - panel.setX.getEntryForIndex(0).getX() > HISTORY_SECONDS) {
            panel.setX.removeFirst();
            panel.setY.removeFirst();
            panel.setZ.removeFirst();
        }

        if (!panel.channel.visible) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - panel.lastChartDraw < CHART_FRAME_MS) {
            return;
        }
        panel.lastChartDraw = now;
        panel.lineData.notifyDataChanged();
        panel.chart.notifyDataSetChanged();
        if (panel.viewAdjusted) {
            // The user is holding a view; keep drawing new data but leave it put.
            panel.chart.invalidate();
        } else {
            followLive(panel);
        }
    }

    private void updateReadouts(Panel panel, float x, float y, float z) {
        if (!panel.channel.visible) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - panel.lastReadoutDraw < READOUT_FRAME_MS) {
            return;
        }
        panel.lastReadoutDraw = now;
        panel.valueX.setText(String.format(Locale.US, "%+.2f", x));
        panel.valueY.setText(String.format(Locale.US, "%+.2f", y));
        panel.valueZ.setText(String.format(Locale.US, "%+.2f", z));
    }

    /** Footer counts cover every recorded sensor, including hidden ones. */
    private void updateFooter(boolean force) {
        if (!recording) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastFooterDraw < READOUT_FRAME_MS) {
            return;
        }
        lastFooterDraw = now;

        float seconds = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000f;
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "%.1f s  ·", seconds));
        for (SensorChannel channel : channels) {
            if (!channel.isPresent()) {
                continue;
            }
            text.append("  ")
                    .append(channel.key.substring(0, 1).toUpperCase(Locale.US))
                    .append(' ')
                    .append(String.format(Locale.US, "%,d", channel.count()));
        }
        sampleCount.setText(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (SensorChannel channel : channels) {
            if (channel.isPresent()) {
                sensorManager.registerListener(this, channel.sensor,
                        SensorManager.SENSOR_DELAY_FASTEST);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
