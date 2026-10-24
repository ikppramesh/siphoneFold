package com.rameshinampudi.frostfold;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the fold animation after the phone reboots, if it was switched on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context.getSharedPreferences(FrostService.PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)) {
            context.startForegroundService(new Intent(context, FrostService.class));
        }
    }
}
