package com.rideledger;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Single periodic fallback and rate-limited one-shot wakes. Called on the main looper. */
final class SyncBackground {
    static final int PERIODIC = 14729, RADIO_EVENT = 14730;
    static final long PERIOD = 15 * 60 * 1000L;
    private static int foreground;
    private static long lastEvent = -60000;
    private static SyncJobService job;
    private static NearbySyncService nearby;
    private static boolean eventDuringJob;

    static void foregroundEntered(Context context) {
        foreground++;
        if (job != null) job.finish();
        if (nearby != null) nearby.visibilityChanged();
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = context.getApplicationContext();
        SyncBleWake.stop(app);
        SyncLog.maintain(app);
        SyncTriggerReceiver.install(app);
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler != null && scheduler.getPendingJob(PERIODIC) == null)
                schedule(app, scheduler, builder(app, PERIODIC).setPeriodic(PERIOD).setPersisted(true).build());
        } catch (RuntimeException error) { SyncLog.write(app, SyncLog.Event.PERIODIC_JOB, error); }
    }

    static void foregroundLeft(Context context) {
        foreground = Math.max(0, foreground - 1);
        if (foreground != 0 || Build.VERSION.SDK_INT < 29) return;
        Context app = context.getApplicationContext();
        if (nearby != null) nearby.visibilityChanged();
        SyncBleWake.start(app);
        radioChanged(app);
    }

    static void beginMainSession(Context context) {
        context.getSharedPreferences("background_sync", Context.MODE_PRIVATE).edit().remove("stopped_trip").apply();
    }

    static boolean tripAllowed(Context context, String tripId) {
        return tripId != null && !tripId.equals(context.getSharedPreferences("background_sync", Context.MODE_PRIVATE)
                .getString("stopped_trip", ""));
    }

    static void endSession(Context context, String tripId) {
        if (tripId == null) return;
        context.getSharedPreferences("background_sync", Context.MODE_PRIVATE).edit().putString("stopped_trip", tripId).apply();
    }

    static void attach(NearbySyncService service) { nearby = service; }

    static void detach(NearbySyncService service) {
        if (nearby != service) return;
        nearby = null;
        if (foreground == 0) {
            SyncBleWake.start(service.getApplicationContext());
            radioChanged(service.getApplicationContext());
        }
    }

    static String activeTrip() { return nearby == null ? null : nearby.activeTrip(); }

    static void claimForService() { if (job != null) job.finish(); }

    static boolean isForeground() { return foreground > 0; }

    static void radioChanged(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (foreground > 0) return;
        if (job != null) { eventDuringJob = true; return; }
        Context app = context.getApplicationContext();
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null || scheduler.getPendingJob(RADIO_EVENT) != null) return;
            long delay = Math.max(3000, lastEvent + 60000 - now);
            schedule(app, scheduler, builder(app, RADIO_EVENT).setMinimumLatency(delay).build());
        } catch (RuntimeException error) { SyncLog.write(app, SyncLog.Event.RADIO_JOB, error); }
    }

    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, SyncJobService.class)).setRequiresBatteryNotLow(true);
    }

    private static void schedule(Context context, JobScheduler scheduler, JobInfo info) {
        if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS)
            SyncLog.write(context, SyncLog.Event.JOB_SCHEDULE, "Rejected");
    }

    static boolean acquire(SyncJobService service) {
        if (foreground > 0 || job != null) return false;
        job = service;
        return true;
    }

    static void jobStarted() { lastEvent = SystemClock.elapsedRealtime(); }

    static void release(SyncJobService service) {
        if (job != service) return;
        job = null;
        boolean pending = eventDuringJob;
        eventDuringJob = false;
        // Post until after jobFinished, so scheduling the same ID cannot replace a running job.
        if (pending && foreground == 0)
            new Handler(Looper.getMainLooper()).post(() -> radioChanged(service.getApplicationContext()));
    }
}
