package com.rideledger;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.concurrent.Executor;

/** Loads travel statistics off the UI thread and presents the result for the selected card. */
final class TripReports {
    private final Activity activity;
    private final LedgerStore store;
    private final Executor reader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean foreground;
    private int reportRequest;
    private Dialog reportDialog;
    private String openTripId;

    TripReports(Activity activity, LedgerStore store, Executor reader) {
        this.activity = activity;
        this.store = store;
        this.reader = reader;
    }

    void start() { foreground = true; }

    void stop() {
        foreground = false;
        invalidatePendingReport();
    }

    void close() {
        stop();
        if (reportDialog != null) reportDialog.dismiss();
        main.removeCallbacksAndMessages(null);
    }

    void invalidatePendingReport() { reportRequest++; }

    String openTripId() { return openTripId; }

    void show(String tripId) {
        int request = ++reportRequest;
        reader.execute(() -> {
            try {
                TripStatistics statistics = TripStatistics.load(activity, store, tripId);
                main.post(() -> {
                    if (canShow(request)) showResult(statistics);
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (canShow(request))
                        Toast.makeText(activity, "无法读取旅行报告，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showResult(TripStatistics statistics) {
        if (reportDialog != null) reportDialog.dismiss();
        Dialog dialog = TripReportView.show(activity, statistics);
        reportDialog = dialog;
        openTripId = statistics.trip.id;
        dialog.setOnDismissListener(ignored -> {
            if (reportDialog == dialog) {
                reportDialog = null;
                openTripId = null;
            }
        });
    }

    private boolean canShow(int request) {
        return foreground && request == reportRequest && !activity.isFinishing() && !activity.isDestroyed();
    }
}
