package com.cgogoel.artframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Moves the library and settings between two installs over ADB, for example from an older package name.
 * Only the shell can send it (the receiver needs DUMP):
 *
 * <pre>
 * am broadcast -n &lt;package&gt;/com.cgogoel.artframe.TransferReceiver -a com.cgogoel.artframe.EXPORT
 * am broadcast -n &lt;package&gt;/com.cgogoel.artframe.TransferReceiver -a com.cgogoel.artframe.IMPORT
 * </pre>
 *
 * EXPORT copies the app's files and settings to Android/data/&lt;package&gt;/files/transfer; IMPORT reads the same
 * folder of this install, replaces its library and settings, then ends the process so everything reloads.
 */
public final class TransferReceiver extends BroadcastReceiver {
    static final String EXPORT = "com.cgogoel.artframe.EXPORT";
    static final String IMPORT = "com.cgogoel.artframe.IMPORT";
    private static final String TAG = "ArtFrameTransfer";
    private static final String PREFS = "settings";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            boolean ok = false;
            try {
                File ext = app.getExternalFilesDir(null);
                if (ext == null) throw new IOException("no external files dir");
                File transfer = new File(ext, "transfer");
                if (EXPORT.equals(action)) {
                    export(app, transfer);
                    ok = true;
                } else if (IMPORT.equals(action)) {
                    importFrom(app, transfer);
                    ok = true;
                }
                Log.i(TAG, action + (ok ? " done: " + transfer : " ignored"));
                pending.setResultCode(ok ? 1 : 0);
                pending.setResultData(ok ? "done " + count(new File(transfer, "files")) + " files" : "unknown action");
            } catch (IOException | JSONException e) {
                Log.e(TAG, action + " failed", e);
                pending.setResultCode(0);
                pending.setResultData("failed: " + e);
            } finally {
                pending.finish();
                // After an import the in-memory library and settings are stale: start fresh.
                if (ok && IMPORT.equals(action)) android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, "art-transfer").start();
    }

    private static void export(Context c, File transfer) throws IOException, JSONException {
        delete(transfer);
        copyTree(c.getFilesDir(), new File(transfer, "files"));
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> e : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject typed = new JSONObject();
            if (v instanceof Boolean) typed.put("b", v);
            else if (v instanceof Integer) typed.put("i", v);
            else if (v instanceof Long) typed.put("l", v);
            else if (v instanceof Float) typed.put("f", ((Float) v).doubleValue());
            else if (v instanceof String) typed.put("s", v);
            else if (v instanceof Set) typed.put("set", new JSONArray((Set<?>) v));
            else continue;
            out.put(e.getKey(), typed);
        }
        write(new File(transfer, "settings.json"), out.toString(1).getBytes(StandardCharsets.UTF_8));
    }

    private static void importFrom(Context c, File transfer) throws IOException, JSONException {
        File files = new File(transfer, "files");
        File settings = new File(transfer, "settings.json");
        if (!files.isDirectory() || !settings.isFile()) throw new IOException("nothing to import in " + transfer);
        File dest = c.getFilesDir();
        File[] old = dest.listFiles();
        if (old != null) for (File f : old) delete(f);
        copyTree(files, dest);
        JSONObject in = new JSONObject(new String(read(settings), StandardCharsets.UTF_8));
        SharedPreferences.Editor ed = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear();
        for (java.util.Iterator<String> it = in.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject t = in.getJSONObject(k);
            if (t.has("b")) ed.putBoolean(k, t.getBoolean("b"));
            else if (t.has("i")) ed.putInt(k, t.getInt("i"));
            else if (t.has("l")) ed.putLong(k, t.getLong("l"));
            else if (t.has("f")) ed.putFloat(k, (float) t.getDouble("f"));
            else if (t.has("s")) ed.putString(k, t.getString("s"));
            else if (t.has("set")) {
                JSONArray a = t.getJSONArray("set");
                Set<String> s = new HashSet<>();
                for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
                ed.putStringSet(k, s);
            }
        }
        if (!ed.commit()) throw new IOException("settings not saved");
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else if (from.isFile()) {
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        }
    }

    private static int count(File f) {
        if (f.isFile()) return 1;
        int n = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) n += count(k);
        return n;
    }

    private static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
