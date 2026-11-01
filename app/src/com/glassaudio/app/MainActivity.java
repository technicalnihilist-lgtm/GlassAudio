package com.glassaudio.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Glass Audio - native shell around the Glass Audio web app.
 *
 * The UI lives in assets/www (index.html + lame.min.js + native.js) and is served
 * from a private https origin so that secure-context features (Web Audio,
 * localStorage, device orientation, fullscreen) all work.
 */
public class MainActivity extends Activity {

    /** Fake https origin used to serve the bundled assets (gives us a secure context). */
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/www/index.html";
    private static final String MUSIC_SUBDIR = "GlassAudio";

    private static final int REQ_FILE = 1001;
    private static final int REQ_PERM = 1002;

    private FrameLayout root;
    private WebView web;
    private WebChromeClient chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B1B3A);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1B3A);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "GlassNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null || !HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null) return null;
                if (path.startsWith("/")) path = path.substring(1);
                try {
                    InputStream is = getAssets().open(path);
                    WebResourceResponse r = new WebResourceResponse(mimeOf(path), "utf-8", is);
                    if (Build.VERSION.SDK_INT >= 21) {
                        java.util.Map<String, String> h = new java.util.HashMap<>();
                        h.put("Cache-Control", "no-cache");
                        r.setResponseHeaders(h);
                    }
                    return r;
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && HOST.equals(u.getHost())) return false;
                // Anything else opens in the browser (links, external help pages).
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "audio/*", "video/*", "application/ogg", "application/octet-stream"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Select audio files"), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = cb;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                goImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        };
        web.setWebChromeClient(chrome);

        root.addView(web);
        setContentView(root);

        // Legacy devices need storage permission up-front (API 29+ uses MediaStore, no permission).
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
        }

        if (state != null) {
            web.restoreState(state);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private void goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().hide(
                    android.view.WindowInsets.Type.systemBars());
        } else {
            web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (filePathCallback == null) return;
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        out[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(out);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".wasm")) return "application/wasm";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- JS bridge

    /**
     * Receives the converted audio from the web app in chunks (base64) so that a
     * 60 MB MP3 never has to exist as a single JS string, then publishes it to
     * Music/GlassAudio via MediaStore.
     */
    public class Bridge {

        private File tmp;
        private FileOutputStream fos;
        private String fileName = "audio.mp3";
        private String mime = "audio/mpeg";

        @JavascriptInterface
        public void beginFile(String name, String mimeType) {
            try {
                if (fos != null) fos.close();
            } catch (IOException ignored) {
            }
            fileName = (name == null || name.trim().isEmpty()) ? "audio.mp3" : name.trim();
            fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "_");
            mime = (mimeType == null || mimeType.isEmpty()) ? "application/octet-stream" : mimeType;
            try {
                tmp = File.createTempFile("ga_", ".bin", getCacheDir());
                fos = new FileOutputStream(tmp);
            } catch (IOException e) {
                tmp = null;
                fos = null;
                toast("Could not start saving: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void appendChunk(String b64) {
            if (fos == null || b64 == null) return;
            try {
                fos.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                toast("Write error: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void endFile() {
            if (fos == null || tmp == null) return;
            try {
                fos.flush();
                fos.close();
            } catch (IOException ignored) {
            }
            fos = null;
            final File finished = tmp;
            tmp = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String where;
                    try {
                        where = publish(finished, fileName, mime);
                    } catch (Exception e) {
                        where = null;
                        toast("Save failed: " + e.getMessage());
                    }
                    if (finished.exists()) finished.delete();
                    if (where != null) {
                        final String w = where;
                        toast("Saved to " + w);
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void toastMsg(String msg) {
            toast(msg);
        }
    }

    /** Copies the finished temp file into the user's Music/GlassAudio folder. */
    private String publish(File src, String name, String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            cv.put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/" + MUSIC_SUBDIR);
            cv.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = getContentResolver()
                    .insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("could not create the file in MediaStore");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("could not open the destination");
            try {
                copy(src, os);
            } finally {
                try {
                    os.close();
                } catch (IOException ignored) {
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return "Music/" + MUSIC_SUBDIR + "/" + name;
        }

        boolean canWritePublic = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        File dir;
        if (canWritePublic) {
            dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC), MUSIC_SUBDIR);
        } else {
            dir = new File(getExternalFilesDir(null), MUSIC_SUBDIR);
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("could not create " + dir.getAbsolutePath());
        }
        File out = new File(dir, name);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            copy(src, fos);
        } finally {
            try {
                fos.close();
            } catch (IOException ignored) {
            }
        }
        if (canWritePublic) {
            sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
        }
        return out.getAbsolutePath();
    }

    private static void copy(File src, OutputStream os) throws IOException {
        InputStream is = new java.io.FileInputStream(src);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
