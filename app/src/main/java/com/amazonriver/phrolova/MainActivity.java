package com.amazonriver.phrolova;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView web;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    class Bridge {
        @JavascriptInterface public String togglePet() {
            if (PetService.running) { stopService(new Intent(MainActivity.this, PetService.class)); return "stopped"; }
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                return "perm";
            }
            startForegroundService(new Intent(MainActivity.this, PetService.class));
            runOnUiThread(() -> moveTaskToBack(true));
            return "started";
        }
        @JavascriptInterface public boolean isPetRunning() { return PetService.running; }
        @JavascriptInterface public void setMood(String m) { PetService.mood = m; }
        @JavascriptInterface public void openAccessibility() { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    geoCb = cb; geoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                }
            }
        });
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == 1 && geoCb != null) {
            geoCb.invoke(geoOrigin, r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED, false);
            geoCb = null;
        }
    }

    @Override
    public void onBackPressed() { finish(); }
}
