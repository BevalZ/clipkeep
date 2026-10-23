package com.clipkeep.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.util.function.BooleanSupplier;

public class ServiceRecoveryTest {
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
    @Test public void bindFailureRecoversAndExplicitStopPersists() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        ReceiveService.setEnabled(context, false);
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            try (ServerSocket occupied = new ServerSocket(LanProtocol.PORT)) {
                context.startForegroundService(new Intent(context, ReceiveService.class));
                await(() -> ReceiveService.state.contains("正在重试"));
                assertFalse(ReceiveService.running); assertTrue(ReceiveService.enabled(context));
            }
            await(() -> ReceiveService.running);
            Transfer.probe(LanProtocol.parseCode(LanProtocol.PREFIX + "127.0.0.1:45788/"
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(LanSettings.key(context))));
            context.startService(new Intent(context, ReceiveService.class).setAction("stop"));
            await(() -> !ReceiveService.running && !ReceiveService.enabled(context));
            assertFalse(ReceiveService.enabled(context));
        } finally {
            ReceiveService.setEnabled(context, false); context.stopService(new Intent(context, ReceiveService.class));
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
        }
    }
}
