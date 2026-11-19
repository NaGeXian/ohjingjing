package com.shenxun.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.Locale;

/**
 * 深寻 · 野生版
 *
 * 一个纯壳：把打包进 assets 的 index.html 交给 WebView 渲染，
 * 页面自己直连 api.deepseek.com，App 不参与任何网络转发，也不保存任何数据。
 */
public class MainActivity extends Activity {

    private static final String TAG = "Shenxun";
    private static final String START_URL = "file:///android_asset/index.html";

        private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private static final int REQ_PICK = 4101;
    private static final int REQ_CAPTURE = 4102;

    private TextToSpeech tts;
    private boolean ttsReady = false;

    /** WebView 有时候丢了输入连接，点了输入框键盘不出来——这里强制叫一次 */
    public class UiBridge {
        @android.webkit.JavascriptInterface
        public void keyboard() {
            if (web == null) return;
            web.post(new Runnable() {
                @Override public void run() {
                    if (web == null) return;
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    // 键盘已经开着就别再叫一次，反复 show/hide 容易把输入法搞乱
                    if (imm != null && imm.isAcceptingText()) return;
                    web.requestFocus();
                    if (imm != null) imm.showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
    }

    /** 暴露给页面的朗读接口 */
    public class TtsBridge {        @android.webkit.JavascriptInterface
        public void speak(String text) {
            if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
            try {
                tts.stop();
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sx" + System.currentTimeMillis());
            } catch (Exception e) { Log.e(TAG, "tts speak", e); }
        }
        @android.webkit.JavascriptInterface
        public void stop() {
            try { if (tts != null) tts.stop(); } catch (Exception e) { }
        }
        @android.webkit.JavascriptInterface
        public boolean ready() { return ttsReady; }
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(Color.WHITE);
        root.addView(web);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        // 页面是打包在 assets 里的本地文件，用 file:// 打开。
        // WebView 对 file:// 源的跨域请求会走 CORS，而接口回显的 Origin 未必是 null，
        // 所以这里直接放开本地源的跨域限制——页面内容是我们自己的，不存在外来脚本风险。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 朗读用系统自带 TTS，离线、不花 API 的钱
        web.addJavascriptInterface(new TtsBridge(), "NativeTTS");
        web.addJavascriptInterface(new UiBridge(), "NativeUI");
        initTts(1);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return handleUrl(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handleUrl(Uri.parse(url));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m != null) {
                    // 分发版：不转发页面日志
                }
                return true;
            }

            /** 页面里的 <input type="file"> 由这里接管（拍照 / 相册 / 文件） */
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                if (params == null) { filePathCallback = null; return false; }
                try {
                    boolean wantImage = false;
                    String[] accepts = params.getAcceptTypes();
                    if (accepts != null) {
                        for (String t : accepts) {
                            if (t != null && t.contains("image")) wantImage = true;
                        }
                    }

                    // 带 capture 的图片输入 = 拍照
                    if (params.isCaptureEnabled() && wantImage) {
                        Uri out = createImageUri();
                        if (out != null) {
                            cameraUri = out;
                            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                            cam.putExtra(MediaStore.EXTRA_OUTPUT, out);
                            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            startActivityForResult(cam, REQ_CAPTURE);
                            return true;
                        }
                    }

                    // 其余交给系统选择器（相册 / 文件）
                    Intent pick = params.createIntent();
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    boolean zipOnly = false;
                    if (accepts != null) {
                        for (String t : accepts) {
                            if (t != null && (t.contains("zip") || t.contains("authority"))) zipOnly = true;
                        }
                    }
                    // zip 只选一个，多选模式会让某些 ROM 的选择器崩掉
                    if (!zipOnly) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(pick, REQ_PICK);
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "file chooser failed", e);
                    if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(null);
                        filePathCallback = null;
                    }
                    return false;
                }
            }
        });
        web.loadUrl(START_URL);

        whiteStatusBar();
        applyIntentExtras(getIntent());
    }

    /** 给拍照准备一个可写的 MediaStore 条目 */
    private void notifyPage(final String js) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override public void run() { web.evaluateJavascript(js, null); }
        });
    }

    private void initTts(final int attempt) {
        try {
            if (tts != null) { tts.shutdown(); tts = null; }
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) {
                    ttsReady = (status == TextToSpeech.SUCCESS);
                    Log.i(TAG, "tts init status=" + status + " attempt=" + attempt);
                    if (ttsReady) {
                        try {
                            int r = tts.setLanguage(Locale.CHINA);
                            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                tts.setLanguage(Locale.getDefault());
                            }
                            tts.setSpeechRate(1.0f);
                        } catch (Exception e) { Log.e(TAG, "tts language", e); }
                        notifyPage("window.__ttsReady&&window.__ttsReady()");
                    } else if (attempt < 3) {
                        web.postDelayed(new Runnable() {
                            @Override public void run() { initTts(attempt + 1); }
                        }, 1200);
                    } else {
                        notifyPage("window.__ttsUnavailable&&window.__ttsUnavailable()");
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "tts create", e);
        }
    }

    private Uri createImageUri() {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "sx_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Shenxun");
            }
            return getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        } catch (Exception e) {
            Log.e(TAG, "createImageUri failed", e);
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (filePathCallback == null) {
            super.onActivityResult(req, res, data);
            return;
        }
        Uri[] uris = null;
        if (res == RESULT_OK) {
            if (req == REQ_CAPTURE && cameraUri != null) {
                uris = new Uri[]{ cameraUri };
            } else if (data != null) {
                ClipData cd = data.getClipData();
                if (cd != null && cd.getItemCount() > 0) {
                    uris = new Uri[cd.getItemCount()];
                    for (int i = 0; i < cd.getItemCount(); i++) {
                        uris[i] = cd.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    uris = new Uri[]{ data.getData() };
                }
            }
        }
        filePathCallback.onReceiveValue(uris);
        filePathCallback = null;
        cameraUri = null;
    }

    /** 把页面 console 之外的东西也顺手记一笔，方便 adb logcat 观察 */
    private void applyIntentExtras(Intent it) {
        // 分发版：不处理任何调试用 intent 参数
    }

    private static String json(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        applyIntentExtras(it);
    }

    /** 页面里的外链交给系统浏览器，本地的 file:// 留在 WebView 里。 */
    private boolean handleUrl(Uri u) {
        if (u == null) return false;
        String scheme = u.getScheme();
        if (scheme == null || "file".equals(scheme) || "about".equals(scheme)) return false;
        if ("https".equals(scheme) || "http".equals(scheme) || "mailto".equals(scheme)) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }
        return false;
    }

    private void whiteStatusBar() {
        try {
            getWindow().setStatusBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 23) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Exception ignored) { }
    }

    /**
     * 返回键：先问页面有没有要关的（抽屉、弹层），页面说没有才退出。
     * 顺序是 页面处理 → 网页历史后退 → 退出。
     */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        // 键盘开着时，返回键只该收键盘——不能把整个 App 退掉
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && imm.isAcceptingText()) {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return !!(window.__handleBack&&window.__handleBack());}catch(e){return false;}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if ("true".equals(value)) return;
                        if (web != null && web.canGoBack()) {
                            web.goBack();
                            return;
                        }
                        MainActivity.super.onBackPressed();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        try {
            if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        } catch (Exception ignored) { }
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
