#!/bin/bash
set -uo pipefail
GEN=/home/runner/work/OpenKI/OpenKI/termux-generator
LOG=/tmp/termux-gen.log
exec >> /tmp/after-termux-gen.log 2>&1

BUILD_PID="$1"
echo "[watch] $(date -u +%T) waiting for PID $BUILD_PID"
while kill -0 "$BUILD_PID" 2>/dev/null; do
  sleep 30
  echo "[wait] $(date -u +%T) build running; docker=$(docker ps --format '{{.Names}}' 2>/dev/null | tr '\n' ' ') tail=$(tail -n 1 "$LOG" 2>/dev/null | cut -c1-120)"
done
wait "$BUILD_PID" 2>/dev/null || true
echo "[done] $(date -u +%T) build process exited"

APK=$(find "$GEN" -path "*arm64*" -name "*.apk" 2>/dev/null | sort | tail -n 1 || true)
[ -z "$APK" ] && APK=$(find "$GEN" -name "*.apk" 2>/dev/null | sort | tail -n 1 || true)
echo "APK found: $APK"
if [ -z "$APK" ]; then echo "NO_APK" > /tmp/androco_apk_path.txt; exit 0; fi

APP="$GEN/termux-apps-main/termux-app"
if [ -d "$APP" ]; then
  echo "[patch] applying customizations"
  mkdir -p "$APP/app/src/main/assets" "$APP/app/src/main/res/layout" "$APP/app/src/main/java/com/andoco/app/app/activities"
  cat > "$APP/app/src/main/assets/bootstrap-opencode.sh" <<'SH'
#!/data/data/com.andoco.app/files/usr/bin/bash
pkg update -y
pkg install -y nodejs npm
npm install -g opencode
opencode web --port 4096 &
SH
  chmod +x "$APP/app/src/main/assets/bootstrap-opencode.sh"
  cat > "$APP/app/src/main/res/layout/activity_webview.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<WebView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/webview"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
XML
  cat > "$APP/app/src/main/java/com/andoco/app/app/activities/WebViewActivity.java" <<'JAVA'
package com.andoco.app.app.activities;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.andoco.app.R;

public final class WebViewActivity extends AppCompatActivity {
    public static final String EXTRA_URL = "url";
    public static final String DEFAULT_URL = "http://127.0.0.1:4096";
    private WebView mWebView;
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview);
        mWebView = findViewById(R.id.webview);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) { }
        });
        String url = getIntent() != null ? getIntent().getStringExtra(EXTRA_URL) : null;
        if (url == null || url.trim().isEmpty()) url = DEFAULT_URL;
        mWebView.loadUrl(url);
    }
    @Override public void onBackPressed() { if (mWebView != null && mWebView.canGoBack()) mWebView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (mWebView != null) { mWebView.stopLoading(); mWebView.destroy(); } super.onDestroy(); }
}
JAVA
  python3 - <<'PY'
import os,re
app='/home/runner/work/OpenKI/OpenKI/termux-generator/termux-apps-main/termux-app'
p=app+'/app/src/main/AndroidManifest.xml'
if os.path.exists(p):
    s=open(p).read(); s=re.sub(r'\n    android:sharedUserId="\$\{TERMUX_PACKAGE_NAME\}"','',s); s=re.sub(r'\n    android:sharedUserLabel="@string/shared_user_label"','',s)
    if 'usesCleartextTraffic' not in s: s=s.replace('<application','<application\n        android:usesCleartextTraffic="true"',1)
    if 'WebViewActivity' not in s:
        ins='        <activity\n            android:name=".app.activities.WebViewActivity"\n            android:exported="false"\n            android:label="@string/action_opencode_web"\n            android:theme="@style/Theme.TermuxApp.DayNight.DarkActionBar" />\n'
        m='        <activity\n            android:name=".app.activities.HelpActivity"'
        s=s.replace(m,ins+'\n'+m) if m in s else s.replace('    </application>',ins+'    </application>')
    open(p,'w').write(s)
p=app+'/app/src/main/res/values/strings.xml'
if os.path.exists(p):
    s=open(p).read()
    if 'action_opencode_web' not in s: open(p,'w').write(s.replace('\n</resources>','\n    <string name="action_opencode_web">OpenCode Web</string>\n    <string name="action_setup_opencode">Setup OpenCode</string>\n</resources>'))
p=app+'/app/src/main/java/com/andoco/app/app/TermuxActivity.java'
if os.path.exists(p):
    s=open(p).read()
    if 'CONTEXT_MENU_OPENCODE_WEB_ID' not in s:
        s=s.replace('    private static final int CONTEXT_MENU_REPORT_ID = 9;\n','    private static final int CONTEXT_MENU_REPORT_ID = 9;\n    private static final int CONTEXT_MENU_OPENCODE_WEB_ID = 12;\n    private static final int CONTEXT_MENU_SETUP_OPENCODE_ID = 13;\n')
        s=s.replace('        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);\n        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);','        menu.add(Menu.NONE, CONTEXT_MENU_SETTINGS_ID, Menu.NONE, R.string.action_open_settings);\n        menu.add(Menu.NONE, CONTEXT_MENU_OPENCODE_WEB_ID, Menu.NONE, R.string.action_opencode_web);\n        menu.add(Menu.NONE, CONTEXT_MENU_SETUP_OPENCODE_ID, Menu.NONE, R.string.action_setup_opencode);\n        menu.add(Menu.NONE, CONTEXT_MENU_REPORT_ID, Menu.NONE, R.string.action_report_issue);')
        s=s.replace('            case CONTEXT_MENU_SETTINGS_ID:\n                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));\n                return true;\n            case CONTEXT_MENU_REPORT_ID:','            case CONTEXT_MENU_SETTINGS_ID:\n                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));\n                return true;\n            case CONTEXT_MENU_OPENCODE_WEB_ID:\n                ActivityUtils.startActivity(this, new Intent(this, com.andoco.app.app.activities.WebViewActivity.class));\n                return true;\n            case CONTEXT_MENU_SETUP_OPENCODE_ID:\n                if (mTermuxTerminalSessionActivityClient != null) {\n                    mTermuxTerminalSessionActivityClient.addNewSession(false, "opencode");\n                    TerminalSession ses = getCurrentSession();\n                    if (ses != null) ses.write("pkg update -y && pkg install -y nodejs npm && npm install -g opencode && opencode web --port 4096 &\\n");\n                }\n                return true;\n            case CONTEXT_MENU_REPORT_ID:')
        open(p,'w').write(s)
print('patched')
PY
  cd "$APP" && chmod +x gradlew 2>/dev/null || true && ./gradlew :app:assembleDebug || true
  NEWAPK=$(find "$APP" -path "*arm64*" -name "*.apk" 2>/dev/null | sort | tail -n 1 || true)
  [ -z "$NEWAPK" ] && NEWAPK=$(find "$APP" -name "*.apk" 2>/dev/null | sort | tail -n 1 || true)
  [ -n "$NEWAPK" ] && APK="$NEWAPK"
fi
echo "FINAL_APK=$APK"
if [ -n "$APK" ] && [ -f "$APK" ]; then echo "$APK" > /tmp/androco_apk_path.txt; ls -lh "$APK"; else echo "NO_APK" > /tmp/androco_apk_path.txt; fi
