package com.clipkeep.android;

import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

public class PreviewLifecycleTest {
    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(50);
        assertTrue(condition.getAsBoolean());
    }
    private ListView list(View view) {
        if (view instanceof ListView) return (ListView)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            ListView result = list(((ViewGroup)view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private boolean visible(UiAutomation ui, String text) {
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        return root != null && root.findAccessibilityNodeInfosByText(text).stream().anyMatch(node -> text.contentEquals(node.getText()));
    }
    private void click(UiAutomation ui, String text) throws Exception {
        await(() -> visible(ui, text));
        for (AccessibilityNodeInfo node : ui.getRootInActiveWindow().findAccessibilityNodeInfosByText(text)) {
            if (!text.contentEquals(node.getText())) continue;
            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
            MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
            try { assertTrue(ui.injectInputEvent(down, true)); assertTrue(ui.injectInputEvent(up, true)); }
            finally { down.recycle(); up.recycle(); }
            return;
        }
        fail("Missing " + text);
    }
    @Test public void queuedPreviewDoesNotReappearAfterBackgroundAndReturn() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        ClipkeepApp app = (ClipkeepApp)context.getApplicationContext();
        ReceiveService.setEnabled(context, false); context.stopService(new Intent(context, ReceiveService.class));
        app.store.clearHistory();
        android.graphics.Bitmap source = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream png = new ByteArrayOutputStream(); source.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png); source.recycle();
        long id = app.store.saveImage(png.toByteArray());
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch release = new CountDownLatch(1), blocked = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityStopped(Activity value) { if (value == activity) stopped.countDown(); }
            public void onActivityCreated(Activity value, android.os.Bundle state) { }
            public void onActivityStarted(Activity value) { }
            public void onActivityResumed(Activity value) { }
            public void onActivityPaused(Activity value) { }
            public void onActivitySaveInstanceState(Activity value, android.os.Bundle state) { }
            public void onActivityDestroyed(Activity value) { }
        };
        app.registerActivityLifecycleCallbacks(callbacks);
        try {
            ListView[] target = new ListView[1];
            instrumentation.runOnMainSync(() -> target[0] = list(activity.getWindow().getDecorView()));
            await(() -> {
                boolean[] ready = {false};
                instrumentation.runOnMainSync(() -> ready[0] = target[0].getCount() > 0);
                return ready[0];
            });
            app.worker.execute(() -> { blocked.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(blocked.await(3, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> target[0].getOnItemLongClickListener().onItemLongClick(target[0], target[0].getChildAt(0), 0, id));
            click(instrumentation.getUiAutomation(), "预览");
            // Wait for the click to queue its work before leaving the activity.
            instrumentation.waitForIdleSync();
            assertTrue(app.worker.getQueue().size() > 0);
            try (ParcelFileDescriptor command = instrumentation.getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME");
                 InputStream output = new FileInputStream(command.getFileDescriptor())) { while (output.read() != -1) { } }
            assertTrue(stopped.await(3, TimeUnit.SECONDS));
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(activity::hasWindowFocus);
            release.countDown(); app.worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
            instrumentation.waitForIdleSync();
            assertFalse(visible(instrumentation.getUiAutomation(), "图片预览"));
            // A fresh request still works after the stale request was discarded.
            instrumentation.runOnMainSync(() -> target[0].getOnItemLongClickListener().onItemLongClick(target[0], target[0].getChildAt(0), 0, id));
            click(instrumentation.getUiAutomation(), "预览");
            await(() -> visible(instrumentation.getUiAutomation(), "图片预览"));
            click(instrumentation.getUiAutomation(), "关闭");
        } finally {
            release.countDown(); app.unregisterActivityLifecycleCallbacks(callbacks);
            instrumentation.runOnMainSync(activity::finish); instrumentation.waitForIdleSync();
            app.worker.submit(() -> { }).get(5, TimeUnit.SECONDS); app.store.delete(id);
        }
    }
}
