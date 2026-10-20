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
    private LanReceiver receiver;
    private AlertDialog dialog;
    private final Runnable searchLater = () -> { offset = 0; reload(); };
    private final Runnable receivedLater = () -> { if (!started) return; status.setText("已收到局域网文本"); offset = 0; reload(); };

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
        header.addView(button("局域网", this::lanDialog)); root.addView(header);
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(16);
        search.setHint("搜索剪贴板历史"); search.setContentDescription("搜索剪贴板历史");
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(54)));
        LinearLayout actions = row();
        actions.addView(button("粘贴保存", this::captureClipboard), weight());
        actions.addView(button("新建", () -> compose(null)), weight());
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
        TextView empty = label("还没有记录\n点击“粘贴保存”，或从其他应用分享文本到 clipkeep", 16);
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

    @Override protected void onStart() { super.onStart(); started = true; reload(); }
    @Override protected void onStop() {
        started = false; ++loadGeneration;
        handler.removeCallbacks(searchLater); handler.removeCallbacks(receivedLater);
        if (loadTask != null) { loadTask.cancel(false); app.worker.purge(); }
        stopReceiver();
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
                    handler.post(() -> { busy = false; if (started && !isDestroyed()) toast(error.getMessage()); });
                }
            });
        } catch (RejectedExecutionException error) { busy = false; toast("正在处理，请稍后重试"); }
    }
    private void captureClipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        ClipData data = clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0 || data.getItemAt(0).getText() == null) { toast("剪贴板没有文本"); return; }
        CharSequence value = data.getItemAt(0).getText();
        if (value.length() > LanProtocol.MAX_TEXT_BYTES) { toast("文本超过 2 MB"); return; }
        String text = value.toString();
        task(() -> { app.store.save(text); return "已保存"; }, message -> { toast(message); offset = 0; reload(); });
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
        task(() -> app.store.text(id), text -> {
            try { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipkeep", text)); toast("已复制"); }
            catch (RuntimeException error) { toast("系统剪贴板无法接收这么长的文本，请使用局域网发送"); }
        });
    }
    private void clipActions(ClipStore.Row clip) {
        String[] choices = {"复制", "预览", clip.favorite ? "取消收藏" : "收藏", clip.pinned ? "取消置顶" : "置顶", "发送到设备", "系统分享", "删除"};
        show(new AlertDialog.Builder(this).setTitle("文本操作").setItems(choices, (d, index) -> {
            if (index == 0) copy(clip.id);
            else if (index == 1) task(() -> app.store.text(clip.id), text -> {
                TextView preview = label(text.substring(0, Math.min(text.length(), 16000)) + (text.length() > 16000 ? "\n\n… 预览仅显示前 16000 字，复制与局域网发送使用全文。" : ""), 16);
                preview.setTextIsSelectable(true); preview.setPadding(dp(20), dp(12), dp(20), dp(12));
                ScrollView scroll = new ScrollView(this); scroll.addView(preview);
                show(new AlertDialog.Builder(this).setTitle("文本预览").setView(scroll).setPositiveButton("关闭", null).create());
            });
            else if (index == 2 || index == 3) task(() -> { app.store.toggle(clip.id, index == 2); return true; }, done -> reload());
            else if (index == 4) sendDialog(clip.id);
            else if (index == 5) task(() -> app.store.text(clip.id), text -> {
                // Binder transactions are bounded; large texts use the LAN protocol instead.
                if (text.length() > 64000) { toast("长文本请使用局域网发送"); return; }
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享文本"));
            });
            else show(new AlertDialog.Builder(this).setTitle("删除这条记录？").setNegativeButton("取消", null)
                .setPositiveButton("删除", (a, b) -> task(() -> { app.store.delete(clip.id); return true; }, done -> reload())).create());
        }).create());
    }

    private android.content.SharedPreferences preferences() { return getSharedPreferences("lan", MODE_PRIVATE); }
    private JSONArray peers() {
        try { return new JSONArray(preferences().getString("peers", "[]")); }
        catch (JSONException error) { return new JSONArray(); }
    }
    private byte[] receiveKey() {
        String saved = preferences().getString("key", null);
        if (saved != null) try {
            byte[] key = Base64.getUrlDecoder().decode(saved); if (key.length == 32) return key;
        } catch (IllegalArgumentException ignored) { }
        byte[] key = LanProtocol.newKey();
        preferences().edit().putString("key", Base64.getUrlEncoder().withoutPadding().encodeToString(key)).apply();
        return key;
    }
    private void stopReceiver() {
        if (receiver != null) { receiver.close(); receiver = null; }
        if (status != null) status.setText("点击条目复制 · 长按查看更多操作");
    }
    private void toggleReceive() {
        if (receiver != null) { stopReceiver(); return; }
        try {
            receiver = new LanReceiver(receiveKey(), app.store, new LanReceiver.Listener() {
                public void received() { handler.removeCallbacks(receivedLater); handler.post(receivedLater); }
                public void failed(Exception error) { /* Untrusted failed frames never touch UI or clipboard. */ }
            });
            status.setText("局域网接收已开启 · 离开应用后自动关闭");
        } catch (IOException error) { toast("接收启动失败：" + error.getMessage()); }
    }
    private void lanDialog() {
        String[] actions = {receiver == null ? "开启前台接收" : "关闭接收", "复制本机配对码", "添加设备", "管理已配对设备", "重置本机配对码"};
        show(new AlertDialog.Builder(this).setTitle("局域网加密互传").setItems(actions, (d, index) -> {
            if (index == 0) toggleReceive();
            if (index == 1) localCodes();
            if (index == 2) addPeer();
            if (index == 3) managePeers();
            if (index == 4) show(new AlertDialog.Builder(this).setTitle("重置配对码？").setMessage("旧配对码将失效，其他设备需要重新添加此手机。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (a, b) -> {
                    stopReceiver(); preferences().edit().remove("key").apply(); receiveKey(); toast("已重置，接收已关闭");
                }).create());
        }).setNegativeButton("关闭", null).create());
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
            if (addresses.isEmpty()) { toast("请先连接局域网 Wi-Fi"); return; }
            show(new AlertDialog.Builder(this).setTitle("选择与电脑相通的本机地址")
                .setItems(addresses.toArray(new String[0]), (d, i) -> {
                    String code = LanProtocol.PREFIX + addresses.get(i) + ":" + LanProtocol.PORT + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(receiveKey());
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("clipkeep 配对码", code));
                    toast("配对码已复制；开启接收后请保持应用在前台");
                }).create());
        });
    }
    private void addPeer() {
        LinearLayout fields = column(); fields.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this); name.setHint("设备名称"); name.setSingleLine(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        EditText code = new EditText(this); code.setHint("粘贴 cklan1:// 配对码"); code.setSingleLine(true); code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        fields.addView(name); fields.addView(code);
        AlertDialog add = new AlertDialog.Builder(this).setTitle("添加发送设备").setView(fields)
            .setNegativeButton("取消", null).setPositiveButton("添加", null).create();
        show(add);
        add.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String label = name.getText().toString().trim(); if (label.isEmpty()) throw new IOException("请输入设备名称");
                LanProtocol.Peer peer = LanProtocol.parseCode(code.getText().toString());
                JSONArray saved = peers(), updated = new JSONArray();
                for (int i = 0; i < saved.length(); i++) {
                    JSONObject old = saved.getJSONObject(i);
                    if (!LanProtocol.parseCode(old.getString("code")).address.equals(peer.address)) updated.put(old);
                }
                if (updated.length() >= 32) throw new IOException("最多保存 32 台设备，请先移除不用的设备");
                updated.put(new JSONObject().put("name", label).put("code", peer.code()));
                preferences().edit().putString("peers", updated.toString()).apply(); add.dismiss(); toast("已添加设备");
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
        show(new AlertDialog.Builder(this).setTitle("选择要移除的设备").setItems(peerNames(saved), (d, index) -> {
            saved.remove(index); preferences().edit().putString("peers", saved.toString()).apply(); toast("设备已移除");
        }).create());
    }
    private void sendDialog(long id) {
        JSONArray saved = peers();
        if (saved.length() == 0) { addPeer(); return; }
        show(new AlertDialog.Builder(this).setTitle("发送到设备").setItems(peerNames(saved), (d, index) -> task(() -> {
            LanProtocol.Peer peer = LanProtocol.parseCode(saved.getJSONObject(index).getString("code"));
            String text = app.store.text(id);
            try (Socket socket = new Socket()) {
                // Absolute deadline also interrupts a peer that stops reading during write().
                ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
                deadline.schedule(() -> { try { socket.close(); } catch (IOException ignored) { } }, 8, TimeUnit.SECONDS);
                try {
                    socket.connect(peer.socketAddress(), 3000); socket.setSoTimeout(4000);
                    LanProtocol.writeText(socket.getOutputStream(), peer.key, text);
                    LanProtocol.verifyAck(socket.getInputStream(), peer.key);
                } finally { deadline.shutdownNow(); }
            }
            return "已发送，对方已确认保存";
        }, this::toast)).create());
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
