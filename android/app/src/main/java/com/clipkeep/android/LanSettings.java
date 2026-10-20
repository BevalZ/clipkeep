package com.clipkeep.android;

import android.content.*;
import org.json.*;
import java.io.IOException;
import java.util.Base64;

final class LanSettings {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("lan", Context.MODE_PRIVATE); }
    static synchronized byte[] key(Context c) {
        String saved = prefs(c).getString("key", null);
        if (saved != null) try { byte[] key = Base64.getUrlDecoder().decode(saved); if (key.length == 32) return key; } catch (IllegalArgumentException ignored) { }
        byte[] key = LanProtocol.newKey();
        if (!prefs(c).edit().putString("key", Base64.getUrlEncoder().withoutPadding().encodeToString(key)).commit()) throw new IllegalStateException("无法保存配对密钥");
        return key;
    }
    static synchronized JSONArray peers(Context c) {
        try { return new JSONArray(prefs(c).getString("peers", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }
    static synchronized void add(Context c, String name, LanProtocol.Peer peer) throws Exception {
        name = name.trim(); if (name.isEmpty() || name.length() > 40) throw new IOException("名称需为 1–40 字");
        JSONArray saved = peers(c), next = new JSONArray();
        for (int i=0; i<saved.length(); i++) {
            JSONObject old = saved.getJSONObject(i);
            if (!LanProtocol.parseCode(old.getString("code")).address.equals(peer.address)) next.put(old);
        }
        if (next.length() >= 32) throw new IOException("最多保存 32 台设备");
        next.put(new JSONObject().put("name", name).put("code", peer.code()));
        if (!prefs(c).edit().putString("peers", next.toString()).commit()) throw new IOException("无法保存设备");
    }
    static synchronized void remove(Context c, String address) throws Exception {
        JSONArray next = new JSONArray(), saved = peers(c);
        for (int i=0; i<saved.length(); i++) {
            JSONObject peer = saved.getJSONObject(i);
            if (!LanProtocol.parseCode(peer.getString("code")).address.equals(address)) next.put(peer);
        }
        SharedPreferences.Editor edit = prefs(c).edit().putString("peers", next.toString());
        if (address.equals(prefs(c).getString("sync_peer", ""))) edit.remove("sync_peer");
        if (!edit.commit()) throw new IOException("无法移除设备");
    }
}
