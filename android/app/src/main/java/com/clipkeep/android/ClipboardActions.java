package com.clipkeep.android;

import android.content.*;

final class ClipboardActions {
    static void copy(Context context, ClipStore store, long id) throws Exception {
        ClipData data = store.isImage(id) ? ClipData.newUri(context.getContentResolver(), "clipkeep 图片", ImageProvider.uri(id))
            : ClipData.newPlainText("clipkeep", store.text(id));
        context.getSystemService(ClipboardManager.class).setPrimaryClip(data);
    }
}
