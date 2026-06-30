package dev.fanis.expensenotification;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;

// Periodically brings the notification listener back when the system has unbound it.
// ExpenseNotificationListener.onListenerDisconnected() requests a rebind too, but it
// only runs on an orderly unbind; a hard process kill never calls it, leaving the
// listener dead until the app is reopened or the device reboots. This job runs
// independently of that callback, so it also covers the silent-kill case.
public class ListenerWatchdogJob extends JobService {
    private static final int JOB_ID = 4117;
    // 15 minutes is the platform floor for periodic jobs; anything shorter is clamped.
    private static final long INTERVAL_MS = 15 * 60 * 1000L;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        // Re-issuing a periodic job resets its period, which would push the next run
        // 15 minutes out on every app launch and could starve it. Leave a healthy
        // job alone.
        if (scheduler.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ListenerWatchdogJob.class))
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true) // survive reboot; requires RECEIVE_BOOT_COMPLETED
                .build();
        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        revive(this);
        return false; // work is synchronous and quick; nothing keeps running afterwards
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // reschedule if the system stopped us early
    }

    private static void revive(Context context) {
        long now = System.currentTimeMillis();
        if (ExpenseNotificationListener.isConnected()) {
            // Connected: sweep the shade for anything still active that was posted
            // during an earlier disconnected window.
            ExpenseNotificationListener.scanActive(context);
            record(context, now, "connected; rescanned");
            return;
        }
        NotificationListenerService.requestRebind(
                new ComponentName(context, ExpenseNotificationListener.class));
        record(context, now, "disconnected; rebind requested");
    }

    private static void record(Context context, long now, String action) {
        context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit()
                .putLong("last_watchdog_at", now)
                .putString("last_watchdog_action", action)
                .apply();
    }
}
