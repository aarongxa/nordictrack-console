package com.aarong.whoran;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        UsbOkService.ensureEnabled(context);
        // v1.9: only a real boot brings iFit up; an app update (MY_PACKAGE_REPLACED) does not
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) HomeButtonService.startBoot(context);
        else HomeButtonService.start(context);
    }
}
