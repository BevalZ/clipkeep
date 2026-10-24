package com.clipkeep.android;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private ClipkeepApp app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<ClipStore.Row> rows = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private EditText search;
    private TextView pageLabel, status;
    private Button previous, next, favorites;
    private boolean onlyFavorites, started, busy, hasNext;
    private int offset, loadGeneration;
    private Future<?> loadTask;
    private String lastClipboard = "";
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardListener = this::captureAutomatic;
    private AlertDialog dialog;
    private Transfer.Control sending;
    private final Runnable searchLater = () -> { offset = 0; reload(); };


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (ClipkeepApp) getApplication();
        if (state != null) { offset = state.getInt("offset"); onlyFavorites = state.getBoolean("favorites"); }
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(245, 247, 251));
        root.setPadding(dp(16), dp(10), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(), dp(10) + insets.getSystemWindowInsetTop(),
                dp(16) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout header = row();
        TextView title = label("clipkeep", 27); title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        header.addView(button("设备", this::lanDialog)); root.addView(header);
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(16);
        search.setHint("搜索剪贴板历史"); search.setContentDescription("搜索剪贴板历史");
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout actions = row();
        actions.addView(button("粘贴保存", this::captureClipboard), weight());
        actions.addView(button("新建", () -> compose(null)), weight());
        actions.addView(button("图片", this::chooseImage), weight());
        favorites = button(onlyFavorites ? "全部" : "收藏", () -> {
            onlyFavorites = !onlyFavorites; favorites.setText(onlyFavorites ? "全部" : "收藏"); offset = 0; reload();
        });
        actions.addView(favorites, weight()); root.addView(actions);
        status = label("点击条目复制 · 长按查看更多操作", 12); root.addView(status);
        list = new ListView(this); list.setDividerHeight(dp(6)); list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int position) { return rows.get(position); }
            @Override public long getItemId(int position) { return rows.get(position).id; }
            @Override public boolean hasStableIds() { return true; }
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                LinearLayout card;
                if (recycled == null) {
                    card = column(); card.setPadding(dp(14), dp(10), dp(14), dp(10));
                    card.setBackgroundColor(Color.WHITE);
                    TextView body = label("", 16); body.setMaxLines(3); body.setEllipsize(TextUtils.TruncateAt.END);
                    card.addView(body); card.addView(label("", 12));
                } else card = (LinearLayout) recycled;
                ClipStore.Row clip = rows.get(position);
                ((TextView) card.getChildAt(0)).setText(clip.preview);
                ((TextView) card.getChildAt(1)).setText(getString(R.string.clip_metadata,
                    clip.pinned ? getString(R.string.pinned_prefix) : "", clip.favorite ? getString(R.string.favorite_prefix) : "",
                    android.text.format.DateFormat.format("MM-dd HH:mm", clip.updated), size(clip.bytes)));
                return card;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> copy(rows.get(position).id));
        list.setOnItemLongClickListener((parent, view, position, id) -> { clipActions(rows.get(position)); return true; });
        TextView empty = label("还没有记录\n点击“粘贴保存”，或从其他应用分享文本、图片到 clipkeep", 16);
        empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(30), 0, dp(30));
        FrameLayout content = new FrameLayout(this);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout pages = row();
        previous = button("上一页", () -> { offset = Math.max(0, offset - ClipStore.PAGE_SIZE); reload(); });
        next = button("下一页", () -> { if (hasNext) { offset += ClipStore.PAGE_SIZE; reload(); } });
        pageLabel = label("第 1 页", 13); pageLabel.setGravity(Gravity.CENTER);
        pages.addView(previous); pages.addView(pageLabel, weight()); pages.addView(next); root.addView(pages);
        root.addView(button("清理普通历史", () -> show(new AlertDialog.Builder(this).setTitle("清理普通历史？")
            .setMessage("收藏和置顶会保留。普通历史默认最多占用 50 MB，超过后自动清理最早记录。")
            .setNegativeButton("取消", null).setPositiveButton("清理", (d, w) -> task(() -> { app.store.clearHistory(); return "已清理"; }, message -> { toast(message); offset = 0; reload(); }))
            .create())));
        setContentView(root);
        if (state != null) search.setText(state.getString("query", ""));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                ++loadGeneration; handler.removeCallbacks(searchLater); handler.postDelayed(searchLater, 180);
            }
            public void afterTextChanged(Editable value) { }
        });
        if (state == null) handleIntent(getIntent());
    }

    @Override protected void onStart() {
        super.onStart(); started = true;
        app.updates = error -> { if (error != null) toast(error); refreshStatus(); reload(); };
        getSystemService(ClipboardManager.class).addPrimaryClipChangedListener(clipboardListener);
        if (ReceiveService.enabled(this) && !ReceiveService.running) startReceiver();
        refreshStatus(); reload();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused && started) captureAutomatic();
    }
    @Override protected void onStop() {
        started = false; ++loadGeneration;
        handler.removeCallbacks(searchLater);
        app.updates = null;
        getSystemService(ClipboardManager.class).removePrimaryClipChangedListener(clipboardListener);
        if (loadTask != null) { loadTask.cancel(false); app.worker.purge(); }
        if (sending != null) sending.cancel();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        rows.clear(); adapter.notifyDataSetChanged();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("offset", offset); state.putBoolean("favorites", onlyFavorites); state.putString("query", search.getText().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && intent.getType() != null && intent.getType().startsWith("image/")) {
            android.net.Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            intent.removeExtra(Intent.EXTRA_STREAM);
            if (uri != null) handler.post(() -> importImage(uri));
            return;
        }
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.ACTION_SEND.equals(action) ? Intent.EXTRA_TEXT : Intent.EXTRA_PROCESS_TEXT);
            intent.removeExtra(Intent.EXTRA_TEXT); intent.removeExtra(Intent.EXTRA_PROCESS_TEXT);
            if (shared != null) {
                if (shared.length() > LanProtocol.MAX_TEXT_BYTES) toast("文本超过 2 MB");
                else compose(shared.toString());
            }
        }
    }
    private void reload() {
        if (!started) return;
        final int generation = ++loadGeneration;
        String query = search.getText().toString(); boolean fav = onlyFavorites; int pageOffset = offset;
        if (loadTask != null) { loadTask.cancel(false); app.worker.purge(); }
        try {
            loadTask = app.worker.submit(() -> {
                try {
                    List<ClipStore.Row> result = app.store.list(query, fav, pageOffset);
                    handler.post(() -> {
                        if (!started || generation != loadGeneration) return;
                        if (result.isEmpty() && offset > 0) { offset = Math.max(0, offset - ClipStore.PAGE_SIZE); reload(); return; }
                        hasNext = result.size() > ClipStore.PAGE_SIZE;
                        rows.clear(); rows.addAll(result.subList(0, Math.min(result.size(), ClipStore.PAGE_SIZE)));
                        adapter.notifyDataSetChanged(); list.setSelection(0);
                        previous.setEnabled(offset > 0); next.setEnabled(hasNext);
                        pageLabel.setText(getString(R.string.page_number, offset / ClipStore.PAGE_SIZE + 1));
                    });
                } catch (Exception error) { handler.post(() -> { if (started && generation == loadGeneration) toast(error.getMessage()); }); }
            });
        } catch (RejectedExecutionException error) { toast("正在处理，请稍后重试"); }
    }
    private <T> void task(Callable<T> work, Consumer<T> complete) {
        if (busy) { toast("正在处理，请稍候"); return; }
        busy = true;
        try {
            app.worker.execute(() -> {
                try {
                    T result = work.call();
                    handler.post(() -> { busy = false; if (started && !isDestroyed()) complete.accept(result); });
                } catch (Exception error) {
                    handler.post(() -> { busy = false; if (started && !isDestroyed()) { refreshStatus(); toast(error.getMessage()); } });
                }
            });
        } catch (RejectedExecutionException error) { busy = false; toast("正在处理，请稍后重试"); }
    }
    private void chooseImage() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 42);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 42 && result == RESULT_OK && data != null && data.getData() != null) importImage(data.getData());
    }
    private void importImage(android.net.Uri uri) {
        task(() -> app.store.saveImage(Images.fromUri(this, uri)), id -> { toast("图片已保存"); offset = 0; reload(); });
    }
    private void captureClipboard() { capture(false); }
    private void captureAutomatic() {
        if (started && hasWindowFocus() && !busy && preferences().getBoolean("foreground_sync", false)) capture(true);
    }
    private void capture(boolean automatic) {
        ClipData data = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (data == null || data.getItemCount() == 0) { if (!automatic) toast("剪贴板为空"); return; }
        if (automatic && String.valueOf(data.getDescription().getLabel()).startsWith("clipkeep")) return;
        ClipData.Item item = data.getItemAt(0);
        android.net.Uri uri = item.getUri(); CharSequence value = item.getText();
        if (uri == null && value == null) { if (!automatic) toast("剪贴板没有文本或图片"); return; }
        if (value != null && value.length() > LanProtocol.MAX_TEXT_BYTES) { if (!automatic) toast("文本超过 2 MB"); return; }
        String text = value == null ? null : value.toString();
        if (text != null && text.contains(LanProtocol.PREFIX)) return;
        String fingerprint;
        try {
            String content = uri != null ? uri + ":" + data.getDescription().getTimestamp() : text;
            fingerprint = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) { toast("无法计算剪贴板摘要"); return; }
        if (automatic && fingerprint.equals(lastClipboard)) return;
        String target = automatic ? preferences().getString("sync_peer", "") : "";
        task(() -> {
            long id = uri != null ? app.store.saveImage(Images.fromUri(this, uri)) : app.store.save(text);
            if (!target.isEmpty()) {
                JSONArray saved = peers(); boolean found = false;
                for (int i=0; i<saved.length(); i++) {
                    LanProtocol.Peer peer = LanProtocol.parseCode(saved.getJSONObject(i).getString("code"));
                    if (peer.address.equals(target)) { Transfer.clip(app.store, id, peer); found = true; break; }
                }
                if (!found) throw new IOException("同步设备已移除，请重新选择");
            }
            return id;
        }, id -> { lastClipboard = fingerprint; if (!automatic) toast("已保存"); offset = 0; reload(); if (automatic) handler.post(this::captureAutomatic); });
    }
    private void compose(String initial) {
        EditText input = new EditText(this); input.setHint("输入或粘贴文本"); input.setMinLines(4); input.setMaxLines(10);
        input.setGravity(Gravity.TOP); input.setTextSize(16);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LanProtocol.MAX_TEXT_BYTES)});
        if (initial != null) input.setText(initial);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle("保存文本").setView(input)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        show(editor);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString();
            task(() -> { app.store.save(text); return "已保存"; }, message -> { editor.dismiss(); toast(message); offset = 0; reload(); });
        });
    }
    private void copy(long id) {
        task(() -> app.store.isImage(id) ? ClipData.newUri(getContentResolver(), "clipkeep 图片", ImageProvider.uri(id))
                : ClipData.newPlainText("clipkeep", app.store.text(id)), data -> {
            try { getSystemService(ClipboardManager.class).setPrimaryClip(data); toast("已复制，可到其他应用粘贴"); }
            catch (RuntimeException error) { toast("系统剪贴板无法接收，请使用设备发送"); }
        });
    }
    private void previewImage(long id) {
        task(() -> Images.preview(app.store.imageFile(id)), bitmap -> {
            if (bitmap == null) { toast("图片无法预览"); return; }
            ImageView preview = new ImageView(this); preview.setImageBitmap(bitmap); preview.setAdjustViewBounds(true);
            show(new AlertDialog.Builder(this).setTitle("图片预览").setView(preview).setPositiveButton("关闭", null).create());
        });
    }
    private void shareImage(long id) {
        android.net.Uri uri = ImageProvider.uri(id);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), "clipkeep 图片", uri));
        startActivity(Intent.createChooser(intent, "分享图片"));
    }
    private void clipActions(ClipStore.Row clip) {
        String[] choices = {"复制", "预览", clip.favorite ? "取消收藏" : "收藏", clip.pinned ? "取消置顶" : "置顶", "发送到设备", "系统分享", "删除"};
        show(new AlertDialog.Builder(this).setTitle(clip.image ? "图片操作" : "文本操作").setItems(choices, (d, index) -> {
            if (index == 0) copy(clip.id);
            else if (index == 1 && clip.image) previewImage(clip.id);
            else if (index == 1) task(() -> app.store.text(clip.id), text -> {
                TextView preview = label(text.substring(0, Math.min(text.length(), 16000)) + (text.length() > 16000 ? "\n\n… 预览仅显示前 16000 字，复制与局域网发送使用全文。" : ""), 16);
                preview.setTextIsSelectable(true); preview.setPadding(dp(20), dp(12), dp(20), dp(12));
                ScrollView scroll = new ScrollView(this); scroll.addView(preview);
                show(new AlertDialog.Builder(this).setTitle("文本预览").setView(scroll).setPositiveButton("关闭", null).create());
            });
            else if (index == 2 || index == 3) task(() -> { app.store.toggle(clip.id, index == 2); return true; }, done -> reload());
            else if (index == 4) sendDialog(clip.id);
            else if (index == 5 && clip.image) shareImage(clip.id);
            else if (index == 5) task(() -> app.store.text(clip.id), text -> {
                // Binder transactions are bounded; large texts use the LAN protocol instead.
                if (text.length() > 64000) { toast("长文本请使用局域网发送"); return; }
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享文本"));
            });
            else show(new AlertDialog.Builder(this).setTitle("删除这条记录？").setNegativeButton("取消", null)
                .setPositiveButton("删除", (a, b) -> task(() -> { app.store.delete(clip.id); return true; }, done -> reload())).create());
        }).create());
    }

    private android.content.SharedPreferences preferences() { return LanSettings.prefs(this); }
    private JSONArray peers() { return LanSettings.peers(this); }
    private byte[] receiveKey() { return LanSettings.key(this); }
    private void refreshStatus() {
        if (status != null) status.setText(ReceiveService.enabled(this) ? ReceiveService.state : "点击条目复制 · 长按预览、分享或发送");
    }
    private void stopReceiver() {
        ReceiveService.setEnabled(this, false);
        stopService(new Intent(this, ReceiveService.class)); refreshStatus();
    }
    private void startReceiver() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 43);
        startForegroundService(new Intent(this, ReceiveService.class));
        handler.postDelayed(this::refreshStatus, 300);
    }
    private void toggleReceive() { if (ReceiveService.enabled(this)) stopReceiver(); else startReceiver(); }
    private void lanDialog() {
        String[] actions = {ReceiveService.enabled(this) ? "关闭后台接收" : "开启后台接收", "扫描响应设备", "复制本机配对码", "手动添加设备", "管理已配对设备", "复制与同步设置", "重置本机配对码", "后台运行与省电设置"};
        show(new AlertDialog.Builder(this).setTitle("Wi-Fi / Tailnet 设备").setItems(actions, (d, index) -> {
            if (index == 0) toggleReceive();
            if (index == 1) scanDialog();
            if (index == 2) localCodes();
            if (index == 3) addPeer();
            if (index == 4) managePeers();
            if (index == 5) syncSettings();
            if (index == 7) batterySettings();
            if (index == 6) show(new AlertDialog.Builder(this).setTitle("重置配对码？").setMessage("旧码将失效，后台接收会停止，其他设备需要重新配对。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (a,b) -> {
                    stopReceiver(); preferences().edit().remove("key").apply(); receiveKey(); toast("已重置");
                }).create());
        }).setNegativeButton("关闭", null).create());
    }
    private void batterySettings() {
        boolean exempt = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        show(new AlertDialog.Builder(this).setTitle("后台运行与省电")
            .setMessage((exempt ? "系统电池优化已豁免。" : "系统仍可能在省电或锁屏时限制网络。")
                + "接收服务会在系统允许时恢复，网络恢复后继续接收。可在系统设置中允许后台运行；厂商的自启动设置需单独调整。强制停止后请重新打开应用。安卓后台无法持续读取其他应用剪贴板。")
            .setPositiveButton("电池优化设置", (d,w) -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (ActivityNotFoundException error) { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); }
            }).setNegativeButton("关闭", null).create());
    }
    private void syncSettings() {
        String[] labels = {"收到后复制到系统剪贴板", "前台记录剪贴板，可发送到所选设备"};
        boolean[] checked = {preferences().getBoolean("copy_received", false), preferences().getBoolean("foreground_sync", false)};
        show(new AlertDialog.Builder(this).setTitle("复制与同步")
            .setMultiChoiceItems(labels, checked, (d, index, value) -> preferences().edit().putBoolean(index == 0 ? "copy_received" : "foreground_sync", value).apply())
            .setNeutralButton("选择发送设备", (d,w) -> {
                JSONArray saved = peers();
                if (saved.length() == 0) { toast("请先配对设备"); return; }
                show(new AlertDialog.Builder(this).setTitle("前台自动发送到").setItems(peerNames(saved), (a,i) -> {
                    try { preferences().edit().putString("sync_peer", LanProtocol.parseCode(saved.getJSONObject(i).getString("code")).address).apply(); toast("已设置"); }
                    catch (Exception error) { toast(error.getMessage()); }
                }).setNegativeButton("取消", null).create());
            }).setPositiveButton("完成", (d,w) -> toast("安卓只能在前台读取剪贴板；后台可接收，通知保持静音。接收内容不会再次自动发送。")).create());
    }
    private void scanDialog() {
        EditText targets = new EditText(this); targets.setHint("可选：Tailscale IPv4 / MagicDNS，多台用空格分隔");
        targets.setText(preferences().getString("scan_hosts", "")); targets.setMaxLines(4);
        targets.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        show(new AlertDialog.Builder(this).setTitle("扫描响应设备")
            .setMessage("同 Wi-Fi 自动广播发现；Tailnet 请输入 100.x IPv4 或 MagicDNS 名称。对方需升级 clipkeep 并开启接收，防火墙允许 UDP/TCP 45788。")
            .setView(targets).setNegativeButton("取消", null).setPositiveButton("扫描", (d,w) -> {
                String hosts = targets.getText().toString(); preferences().edit().putString("scan_hosts", hosts).apply();
                status.setText("正在解析名称并扫描…");
                task(() -> Discovery.scan(hosts), devices -> {
                    refreshStatus();
                    if (devices.isEmpty()) { toast("未发现响应设备，请检查接收开关、网络和防火墙"); return; }
                    String[] names = new String[devices.size()]; for (int i=0;i<names.length;i++) names[i] = devices.get(i).toString();
                    show(new AlertDialog.Builder(this).setTitle("选择要配对的设备").setItems(names, (a,i) -> addPeer(devices.get(i)))
                        .setNegativeButton("取消", null).create());
                });
            }).create());
    }
    private void localCodes() {
        task(() -> {
            ArrayList<String> addresses = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement(); if (!network.isUp()) continue;
                Enumeration<InetAddress> ips = network.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (LanProtocol.allowed(ip) && !ip.isLoopbackAddress()) addresses.add(ip.getHostAddress());
                }
            }
            return addresses;
        }, addresses -> {
            if (addresses.isEmpty()) { toast("请先连接 Wi-Fi 或 Tailscale"); return; }
            show(new AlertDialog.Builder(this).setTitle("选择与电脑相通的本机地址")
                .setItems(addresses.toArray(new String[0]), (d, i) -> {
                    String code = LanProtocol.PREFIX + addresses.get(i) + ":" + LanProtocol.PORT + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(receiveKey());
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipkeep 配对码", code));
                    toast("配对码已复制；请开启后台接收");
                }).create());
        });
    }
    private void addPeer() { addPeer(null); }
    private void addPeer(Discovery.Device device) {
        LinearLayout fields = column(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this); name.setHint("设备名称"); name.setSingleLine(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        EditText code = new EditText(this); code.setHint("粘贴 cklan1:// 配对码"); code.setSingleLine(true); code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(320)});
        if (device != null) name.setText(device.name);
        fields.addView(name); fields.addView(code);
        CheckBox mutual = new CheckBox(this); mutual.setText("建立双向配对（对方需新版）"); mutual.setChecked(true); fields.addView(mutual);
        AlertDialog add = new AlertDialog.Builder(this).setTitle(device == null ? "添加设备" : device.address).setMessage("填写对方的完整配对码。扫描不传输密钥；双向配对会通过加密连接将本机添加到对方设备列表。").setView(fields)
            .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        show(add);
        add.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String label = name.getText().toString().trim(); if (label.isEmpty()) throw new IOException("请输入设备名称");
                LanProtocol.Peer peer = LanProtocol.parseCode(code.getText().toString());
                boolean both = mutual.isChecked();
                if (both) startReceiver();
                task(() -> {
                    if (device != null && !peer.socketAddress().getAddress().getHostAddress().equals(device.address)) throw new IOException("配对码地址与所选设备不一致");
                    if (both) {
                        String body = new JSONObject().put("name", Build.MODEL.substring(0, Math.min(40, Build.MODEL.length())))
                            .put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(receiveKey())).toString();
                        Transfer.send(peer, out -> LanProtocol.writePayload(out, peer.key, 0x434b5031, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    }
                    LanSettings.add(this, label, peer); return true;
                }, done -> { add.dismiss(); toast(both ? "双向配对完成，可互发文本和图片" : "已添加发送设备"); });
            } catch (Exception error) { toast(error.getMessage()); }
        });
    }
    private String[] peerNames(JSONArray peers) {
        String[] names = new String[peers.length()];
        for (int i = 0; i < names.length; i++) names[i] = peers.optJSONObject(i).optString("name");
        return names;
    }
    private void managePeers() {
        JSONArray saved = peers();
        if (saved.length() == 0) { toast("还没有配对设备"); return; }
        show(new AlertDialog.Builder(this).setTitle("管理已配对设备").setItems(peerNames(saved), (d, index) -> {
            show(new AlertDialog.Builder(this).setTitle(saved.optJSONObject(index).optString("name"))
                .setItems(new String[]{"测试连接", "移除设备"}, (a, action) -> task(() -> {
                    LanProtocol.Peer peer = LanProtocol.parseCode(saved.getJSONObject(index).getString("code"));
                    if (action == 0) {
                        long start = System.nanoTime();
                        try { Transfer.probe(peer); }
                        catch (Exception error) { throw new IOException("连接测试失败：检查接收开关、配对码和网络；对方需 0.3.1 或更新版本。" + error.getMessage(), error); }
                        return "加密连接正常 · " + ((System.nanoTime() - start) / 1_000_000) + " ms · 未写入历史";
                    }
                    LanSettings.remove(this, peer.address); return "设备已移除";
                }, this::toast)).setNegativeButton("取消", null).create());
        }).create());
    }

    private void sendDialog(long id) {
        JSONArray saved = peers();
        if (saved.length() == 0) { addPeer(); return; }
        show(new AlertDialog.Builder(this).setTitle("发送到设备").setItems(peerNames(saved), (d, index) -> {
            if (busy) { toast("正在处理，请稍候"); return; }
            busy = true; Transfer.Control control = new Transfer.Control(); sending = control;
            AlertDialog progress = new AlertDialog.Builder(this).setTitle("正在发送到 " + saved.optJSONObject(index).optString("name"))
                .setMessage("正在连接并上传，等待对方确认保存。离开应用会取消本次手动发送。")
                .setNegativeButton("取消发送", (a,w) -> control.cancel()).create();
            progress.setCanceledOnTouchOutside(false);
            progress.setOnCancelListener(a -> control.cancel());
            handler.post(() -> { if (started && sending == control) show(progress); });
            try {
                app.worker.execute(() -> {
                    String message;
                    try {
                        LanProtocol.Peer peer = LanProtocol.parseCode(saved.getJSONObject(index).getString("code"));
                        Transfer.clip(app.store, id, peer, control); message = "已发送，对方已确认保存";
                    } catch (Exception error) { message = error.getMessage(); }
                    String result = message;
                    handler.post(() -> { busy = false; if (sending == control) sending = null; progress.dismiss(); if (started && !isDestroyed()) { toast(result); refreshStatus(); } });
                });
            } catch (RejectedExecutionException error) { busy = false; sending = null; progress.dismiss(); toast("正在处理，请稍后重试"); }
        }).create());
    }
    private void show(AlertDialog nextDialog) {
        if (dialog != null) dialog.dismiss(); dialog = nextDialog;
        nextDialog.setOnDismissListener(d -> { if (dialog == nextDialog) dialog = null; }); nextDialog.show();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private TextView label(String value, int size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(Color.rgb(31, 41, 55)); view.setPadding(0, dp(5), 0, dp(5)); return view; }
    private Button button(String title, Runnable action) { Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setTextSize(14); view.setOnClickListener(v -> action.run()); return view; }
    private void toast(String message) { Toast.makeText(this, message == null ? "操作失败，请重试" : message, Toast.LENGTH_LONG).show(); }
    private String size(long bytes) { return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0); }
}
