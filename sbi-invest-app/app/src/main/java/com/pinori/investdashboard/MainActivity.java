package com.pinori.investdashboard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MainActivity extends Activity {
    private static final int REQ_OPEN_FILES = 1201;
    private static final int REQ_SAVE_FILE = 1202;
    private static final String APP_URL = "file:///android_asset/index.html";
    private static final String STATE_FILE = "dashboard_state.json";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingExportName;
    private String pendingExportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#07111F"));
        getWindow().setNavigationBarColor(Color.parseColor("#07111F"));
        getWindow().getDecorView().setSystemUiVisibility(0);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !("file".equals(uri.getScheme()) && uri.toString().startsWith("file:///android_asset/"));
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "text/csv", "text/plain", "application/csv", "application/vnd.ms-excel", "application/json"
                });
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(intent, REQ_OPEN_FILES);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "ファイル選択画面を開けませんでした", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        if (savedInstanceState == null) webView.loadUrl(APP_URL);
        else webView.restoreState(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OPEN_FILES) {
            if (fileCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
            return;
        }

        if (requestCode == REQ_SAVE_FILE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExportText != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
                    if (out != null) {
                        out.write(pendingExportText.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        Toast.makeText(this, "ファイルを保存しました", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
            pendingExportName = null;
            pendingExportText = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            super.onBackPressed();
            return;
        }

        webView.evaluateJavascript(
                "(function(){try{return !!(window.handleAndroidBack&&window.handleAndroidBack());}catch(e){return false;}})()",
                value -> {
                    boolean handledByPage = "true".equals(value);
                    if (handledByPage) return;

                    runOnUiThread(() -> {
                        if (webView != null && webView.canGoBack()) webView.goBack();
                        else MainActivity.super.onBackPressed();
                    });
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("Android");
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveText(String filename, String content) {
            pendingExportName = filename == null || filename.trim().isEmpty() ? "投資成績.csv" : filename;
            pendingExportText = content == null ? "" : content;
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                String lowerName = pendingExportName.toLowerCase();
                intent.setType(lowerName.endsWith(".json") ? "application/json" : "text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, pendingExportName);
                try {
                    startActivityForResult(intent, REQ_SAVE_FILE);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, "保存画面を開けませんでした", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public void setDarkMode(boolean dark) {
            runOnUiThread(() -> {
                int bg = Color.parseColor(dark ? "#07111F" : "#EEF4FB");
                getWindow().setStatusBarColor(bg);
                getWindow().setNavigationBarColor(bg);
                int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !dark) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
                if (webView != null) webView.setBackgroundColor(bg);
            });
        }

        @JavascriptInterface
        public void vibrate(String kind) {
            runOnUiThread(() -> {
                if (webView == null) return;
                int constant = HapticFeedbackConstants.KEYBOARD_TAP;
                if ("warning".equals(kind) || "medium".equals(kind)) constant = HapticFeedbackConstants.LONG_PRESS;
                webView.performHapticFeedback(constant);
            });
        }

        @JavascriptInterface
        public boolean saveState(String json) {
            try {
                java.io.File target = new File(getFilesDir(), STATE_FILE);
                java.io.File temp = new File(getFilesDir(), STATE_FILE + ".tmp");
                byte[] bytes = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
                    out.write(bytes);
                    out.getFD().sync();
                }
                java.nio.file.Files.move(temp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (Exception e) {
                android.util.Log.e("IncomePinorin", "State save failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public String loadState() {
            try {
                File f = new File(getFilesDir(), STATE_FILE);
                if (!f.exists()) return "";
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }
    }
}
