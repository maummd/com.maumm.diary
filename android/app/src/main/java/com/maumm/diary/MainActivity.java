package com.maumm.diary;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 마음 다이어리 화면(assets/index.html)을 안전한 https 주소로 열어 줍니다.
 * 다이어리 기록은 이 휴대폰 안(WebView 저장소)에만 저장되며 서버로 보내지 않습니다.
 *
 * 마음엠디 앱과 같은 구조에 다음을 더했습니다.
 *  - 파일 고르기: 백업 파일 복원
 *  - 파일 저장: 백업 파일을 사용자가 고른 위치에 저장 (DiaryAndroid.saveFile)
 *  - 노래 링크(유튜브 · 스포티파이)는 휴대폰의 해당 앱이나 브라우저로 엽니다.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_PICK = 1001;
    private static final int REQ_SAVE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> pickCallback;
    private String pendingSaveContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        ReminderReceiver.ensureChannel(this);
        webView.setBackgroundColor(Color.parseColor("#F3F6F2"));
        setContentView(webView);

        webView.setOnApplyWindowInsetsListener((View v, WindowInsets insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });

        // <input type="file"> 를 누르면 휴대폰의 파일 고르기 화면을 엽니다.
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return openChooser(callback, params);
            }
        });

        webView.addJavascriptInterface(new Bridge(), "DiaryAndroid");

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl("https://" + HOST + "/assets/index.html");

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    /** 화면(JavaScript)에서 부르는 기능 */
    private class Bridge {

        /** 알림 예약: 화면(JavaScript)이 앞으로 35일 동안의 알림 목록을 넘겨주면 휴대폰 알림으로 맡겨요. */
        @android.webkit.JavascriptInterface
        public void setReminders(String json) {
            ReminderReceiver.schedule(getApplicationContext(), json == null ? "[]" : json);
            try { if (new org.json.JSONArray(json).length() > 0) runOnUiThread(MainActivity.this::askNotifyPerm); } catch (Exception ignored) { }
        }
        @android.webkit.JavascriptInterface
        public void testNotification(String title, String body) { ReminderReceiver.show(getApplicationContext(), 7, title, body); }
        @android.webkit.JavascriptInterface
        public void askNotify() { runOnUiThread(MainActivity.this::askNotifyPerm); }
        /** 문자 · 카카오톡 · 이메일 · 다른 앱으로 바로 보내기 (글자). kind: sms | kakao | email | any */
        @android.webkit.JavascriptInterface
        public void sendTo(String kind, String name, String b64, String text, String subject, String address) {
            runOnUiThread(() -> {
                try {
                    Intent i;
                    if ("sms".equals(kind)) { i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + (address == null ? "" : address))); i.putExtra("sms_body", text); }
                    else if ("email".equals(kind)) { i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + (address == null ? "" : address))); i.putExtra(Intent.EXTRA_SUBJECT, subject); i.putExtra(Intent.EXTRA_TEXT, text); }
                    else { i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text); if (subject != null) i.putExtra(Intent.EXTRA_SUBJECT, subject); }
                    if ("kakao".equals(kind)) {
                        i.setPackage("com.kakao.talk");
                        try { startActivity(i); return; } catch (android.content.ActivityNotFoundException e) {
                            android.widget.Toast.makeText(MainActivity.this, "카카오톡이 설치되어 있지 않아요 — 다른 앱을 골라 주세요", android.widget.Toast.LENGTH_SHORT).show(); i.setPackage(null); }
                    }
                    if ("sms".equals(kind) || "email".equals(kind)) { try { startActivity(i); return; } catch (android.content.ActivityNotFoundException e) { i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_TEXT, text); } }
                    startActivity(Intent.createChooser(i, "보내기"));
                } catch (Exception e) {
                    android.widget.Toast.makeText(MainActivity.this, "보내지 못했어요", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void saveFile(String name, String mime, String content) {
            runOnUiThread(() -> {
                pendingSaveContent = content;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/json" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); }
                catch (Exception e) { pendingSaveContent = null; toast("저장 화면을 열 수 없어요"); }
            });
        }

        @JavascriptInterface
        public boolean copyText(String text) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("마음 다이어리", text));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHOOSE) { chooserResult(resultCode, data); return; }
        if (requestCode == REQ_PICK) {
            if (pickCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{ data.getData() };
                pickCallback.onReceiveValue(result);
                pickCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            String content = pendingSaveContent;
            pendingSaveContent = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || content == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("no stream");
                out.write(content.getBytes(StandardCharsets.UTF_8));
                toast("백업 파일을 저장했어요");
            } catch (Exception e) {
                toast("저장하지 못했어요");
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** 뒤로 가기: 앞 화면(메뉴)으로 돌아가고, 더 갈 곳이 없으면 앱을 닫습니다. */
    private void handleBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { handleBack(); }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    // ---------- 알림 권한 ----------
    private boolean askedNotify = false;
    private void askNotifyPerm() {
        if (askedNotify) return;
        askedNotify = true;
        if (android.os.Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 2001);
        }
    }

    // ---------- 파일 · 사진 고르기 (카메라로 바로 찍기 포함) ----------
    private static final int REQ_CHOOSE = 4101;
    private android.webkit.ValueCallback<Uri[]> chooseCb;
    private Uri cameraUri;
    private boolean openChooser(android.webkit.ValueCallback<Uri[]> callback, android.webkit.WebChromeClient.FileChooserParams params) {
        if (chooseCb != null) chooseCb.onReceiveValue(null);
        chooseCb = callback; cameraUri = null;
        String[] acc = params != null ? params.getAcceptTypes() : null;
        boolean imageOnly = acc != null && acc.length > 0;
        if (acc != null) for (String a : acc) if (a == null || !a.startsWith("image")) imageOnly = false;
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(imageOnly ? "image/*" : "*/*");
        Intent chooser = Intent.createChooser(pick, "파일 · 사진 선택");
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "camera");
            dir.mkdirs();
            java.io.File f = new java.io.File(dir, "scan_" + System.currentTimeMillis() + ".jpg");
            cameraUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent cam = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            cam.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri);
            cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (params != null && params.isCaptureEnabled()) {
                try { startActivityForResult(cam, REQ_CHOOSE); return true; } catch (Exception e) { /* 카메라 앱이 없으면 고르기 화면으로 */ }
            }
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ cam });
        } catch (Exception e) { cameraUri = null; }
        try { startActivityForResult(chooser, REQ_CHOOSE); } catch (Exception e) { chooseCb = null; return false; }
        return true;
    }
    private void chooserResult(int resultCode, Intent data) {
        if (chooseCb == null) return;
        Uri[] r = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) r = new Uri[]{ data.getData() };
            else if (cameraUri != null) r = new Uri[]{ cameraUri };
        }
        chooseCb.onReceiveValue(r);
        chooseCb = null; cameraUri = null;
    }
}
