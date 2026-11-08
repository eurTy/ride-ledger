package com.rideledger;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

/** Single periodic fallback and rate-limited one-shot wakes. Called on the main looper. */
final class SyncBackground {
    static final int PERIODIC = 14729, RADIO_EVENT = 14730;
    static final long PERIOD = 15 * 60 * 1000L;
    private static int foreground;
    private static long lastEvent = -60000;
    private static SyncJobService job;

    static void foregroundEntered(Context context) {
        foreground++;
        if (job != null) job.finish();
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = context.getApplicationContext();
        SyncLog.maintain(app);
        SyncTriggerReceiver.install(app);
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler != null && scheduler.getPendingJob(PERIODIC) == null)
                schedule(app, scheduler, builder(app, PERIODIC).setPeriodic(PERIOD).setPersisted(true).build());
        } catch (RuntimeException error) { SyncLog.write(app, "PERIODIC_JOB", error); }
    }

    static void foregroundLeft() { foreground = Math.max(0, foreground - 1); }

    static void radioChanged(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (foreground > 0 || job != null || now - lastEvent < 60000) return;
        Context app = context.getApplicationContext();
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler == null || scheduler.getPendingJob(RADIO_EVENT) != null) return;
            lastEvent = now;
            schedule(app, scheduler, builder(app, RADIO_EVENT).setMinimumLatency(3000).build());
        } catch (RuntimeException error) { SyncLog.write(app, "RADIO_JOB", error); }
    }

    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, SyncJobService.class)).setRequiresBatteryNotLow(true);
    }

    private static void schedule(Context context, JobScheduler scheduler, JobInfo info) {
        if (scheduler.schedule(info) != JobScheduler.RESULT_SUCCESS) SyncLog.write(context, "JOB_SCHEDULE", "Rejected");
    }

    static boolean acquire(SyncJobService service) {
        if (foreground > 0 || job != null) return false;
        job = service;
        return true;
    }

    static void release(SyncJobService service) { if (job == service) job = null; }
}
