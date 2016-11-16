/*
 * Copyright 2013 Thomas Hoffmann
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.j4velin.pedometer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;

import java.text.NumberFormat;
import java.util.Locale;

import de.j4velin.pedometer.ui.Activity_Main;
import de.j4velin.pedometer.util.Logger;
import de.j4velin.pedometer.util.Util;
import de.j4velin.pedometer.widget.WidgetUpdateService;

/**
 * Background service which keeps the step-sensor listener alive to always get
 * the number of steps since boot.
 * <p/>
 * This service won't be needed any more if there is a way to read the
 * step-value without waiting for a sensor event
 */
public class SensorListener extends Service implements SensorEventListener {

    private final static int NOTIFICATION_ID = 1;

    public final static String ACTION_PAUSE = "pause";
    public final static String FORCE_UPDATE = "update";

    private static int steps;

    private final static int MICROSECONDS_IN_ONE_MINUTE = 60000000;
    private final static long THRESHOLD_UPDATE_MS = 60 * 60 * 1000;
    private final static int THRESHOLD_UPDATE_STEPS = 1000;

    public final static String ACTION_UPDATE_NOTIFICATION = "updateNotificationState";

    private static long lastUpdateTime;
    private static int lastUpdateSteps;

    @Override
    public void onAccuracyChanged(final Sensor sensor, int accuracy) {
        // nobody knows what happens here: step value might magically decrease
        // when this method is called...
        if (BuildConfig.DEBUG) Logger.log(sensor.getName() + " accuracy changed: " + accuracy);
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        if (event.values[0] > Integer.MAX_VALUE || event.values[0] <= 0) {
            if (BuildConfig.DEBUG) Logger.log("probably not a real value: " + event.values[0]);
        } else {
            steps = (int) event.values[0];
            if (needUpdate()) {
                saveAndUpdate();
            }
        }
    }

    /**
     * Checks if we need to update the widget & notification.
     * Updates should happen if the last update is older then {@link #THRESHOLD_UPDATE_MS} ms or if
     * we walked more then {@link #THRESHOLD_UPDATE_STEPS} steps since the last update
     *
     * @return true, if an update should happen now
     */
    private static boolean needUpdate() {
        return steps - lastUpdateSteps > THRESHOLD_UPDATE_STEPS ||
                System.currentTimeMillis() - lastUpdateTime > THRESHOLD_UPDATE_MS;
    }

    /**
     * Saves the step value in the database and updates notification & widget
     */
    private void saveAndUpdate() {
        lastUpdateSteps = steps;
        lastUpdateTime = System.currentTimeMillis();
        Database db = Database.getInstance(this);
        if (db.getSteps(Util.getToday()) == Integer.MIN_VALUE) {
            int pauseDifference = steps - getSharedPreferences("pedometer", Context.MODE_PRIVATE)
                    .getInt("pauseCount", steps);
            db.insertNewDay(Util.getToday(), steps - pauseDifference);
            if (pauseDifference > 0) {
                // update pauseCount for the new day
                getSharedPreferences("pedometer", Context.MODE_PRIVATE).edit()
                        .putInt("pauseCount", steps).apply();
            }
        }
        db.saveCurrentSteps(steps);
        db.close();
        updateNotificationState();
        startService(new Intent(this, WidgetUpdateService.class));
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent != null) {
            if (BuildConfig.DEBUG) Logger.log("onStartCommand action: " + intent.getAction());
            if (ACTION_PAUSE.equals(intent.getAction())) {
                if (steps == 0) {
                    Database db = Database.getInstance(this);
                    steps = db.getCurrentSteps();
                    db.close();
                }
                SharedPreferences prefs = getSharedPreferences("pedometer", Context.MODE_PRIVATE);
                if (prefs.contains("pauseCount")) { // resume counting
                    int difference = steps - prefs.getInt("pauseCount",
                            steps); // number of steps taken during the pause
                    Database db = Database.getInstance(this);
                    db.addToLastEntry(-difference);
                    db.close();
                    prefs.edit().remove("pauseCount").apply();
                    updateNotificationState();
                } else { // pause counting
                    prefs.edit().putInt("pauseCount", steps).apply();
                    updateNotificationState();
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else if (FORCE_UPDATE.equals(intent.getAction())) {
                if (steps > lastUpdateSteps) {
                    saveAndUpdate();
                }
            }

            if (intent.getBooleanExtra(ACTION_UPDATE_NOTIFICATION, false)) {
                updateNotificationState();
            }
        }
        lastUpdateTime = 0; // update as soon as possible

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) Logger.log("SensorListener onCreate");
        registerSensor();
        updateNotificationState();
    }

    @Override
    public void onTaskRemoved(final Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (BuildConfig.DEBUG) Logger.log("sensor service task removed");
        // Restart service in 500 ms
        ((AlarmManager) getSystemService(Context.ALARM_SERVICE))
                .set(AlarmManager.RTC, System.currentTimeMillis() + 500, PendingIntent
                        .getService(this, 3, new Intent(this, SensorListener.class), 0));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (BuildConfig.DEBUG) Logger.log("SensorListener onDestroy");
        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            sm.unregisterListener(this);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Logger.log(e);
            e.printStackTrace();
        }
    }

    /**
     * Updates the notification, if enabled in settings
     */

    private void updateNotificationState() {
        if (BuildConfig.DEBUG) Logger.log("SensorListener updateNotificationState");
        SharedPreferences prefs = getSharedPreferences("pedometer", Context.MODE_PRIVATE);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (prefs.getBoolean("notification", true)) {
            int goal = prefs.getInt("goal", 10000);
            Database db = Database.getInstance(this);
            int today_offset = db.getSteps(Util.getToday());
            if (steps == 0)
                steps = db.getCurrentSteps(); // use saved value if we haven't anything better
            db.close();
            Notification.Builder notificationBuilder = new Notification.Builder(this);
            if (steps > 0) {
                if (today_offset == Integer.MIN_VALUE) today_offset = -steps;
                notificationBuilder.setProgress(goal, today_offset + steps, false).setContentText(
                        today_offset + steps >= goal ? getString(R.string.goal_reached_notification,
                                NumberFormat.getInstance(Locale.getDefault())
                                        .format((today_offset + steps))) :
                                getString(R.string.notification_text,
                                        NumberFormat.getInstance(Locale.getDefault())
                                                .format((goal - today_offset - steps))));
            } else { // still no step value?
                notificationBuilder
                        .setContentText(getString(R.string.your_progress_will_be_shown_here_soon));
            }
            boolean isPaused = prefs.contains("pauseCount");
            notificationBuilder.setPriority(Notification.PRIORITY_MIN).setShowWhen(false)
                    .setContentTitle(isPaused ? getString(R.string.ispaused) :
                            getString(R.string.notification_title)).setContentIntent(PendingIntent
                    .getActivity(this, 0, new Intent(this, Activity_Main.class),
                            PendingIntent.FLAG_UPDATE_CURRENT))
                    .setSmallIcon(R.drawable.ic_notification)
                    .addAction(isPaused ? R.drawable.ic_resume : R.drawable.ic_pause,
                            isPaused ? getString(R.string.resume) : getString(R.string.pause),
                            PendingIntent.getService(this, 4,
                                    new Intent(this, SensorListener.class).setAction(ACTION_PAUSE),
                                    PendingIntent.FLAG_UPDATE_CURRENT)).setOngoing(true);
            nm.notify(NOTIFICATION_ID, notificationBuilder.build());
        } else {
            nm.cancel(NOTIFICATION_ID);
        }
    }

    /**
     * Registers the sensor listener
     */
    private void registerSensor() {
        if (BuildConfig.DEBUG) Logger.log("re-register sensor listener");
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        try {
            sm.unregisterListener(this);
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Logger.log(e);
            e.printStackTrace();
        }

        if (BuildConfig.DEBUG) {
            Logger.log("step sensors: " + sm.getSensorList(Sensor.TYPE_STEP_COUNTER).size());
            if (sm.getSensorList(Sensor.TYPE_STEP_COUNTER).size() < 1) return; // emulator
            Logger.log("default: " + sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER).getName());
        }

        // enable batching with delay of max 5 min
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER),
                SensorManager.SENSOR_DELAY_NORMAL, 5 * MICROSECONDS_IN_ONE_MINUTE);
    }
}
