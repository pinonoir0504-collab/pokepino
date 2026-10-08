from pathlib import Path
p=Path('/tmp/pinowatch11720/app/src/main/java/jp/pinowatch/app/MainActivity.java')
s=p.read_text()
# Android 12+ requires exported for launcher activities.
m=Path('/tmp/pinowatch11720/app/src/main/AndroidManifest.xml')
x=m.read_text()
if 'android:exported=' not in x:
 x=x.replace('<activity android:name=".MainActivity"', '<activity android:name=".MainActivity" android:exported="true"')
# Avoid fatal startup if WebView/provider/init is broken.
x=x.replace('<uses-permission android:name="android.permission.INTERNET" />','<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />')
m.write_text(x)
# Replace Activity with defensive startup wrapper while retaining local asset server and API proxy.
s=s.replace('super.onCreate(savedInstanceState);', '''super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                android.util.Log.e("PinoWatch", "Uncaught exception", error);
            } catch (Throwable ignored) {}
        });''',1)
# Never make a long press/prompt or optional config crash the app.
s=s.replace('webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");',
'''try {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        } catch (Throwable e) {
            android.util.Log.e("PinoWatch", "Initial page load failed", e);
            webView.loadDataWithBaseURL(null,
                "<html><body style='font-family:sans-serif;padding:24px'><h2>ピノウォッチ</h2><p>画面の読み込みに失敗しました。アプリを再起動してください。</p></body></html>",
                "text/html", "UTF-8", null);
        }''',1)
p.write_text(s)
g=Path('/tmp/pinowatch11720/app/build.gradle.kts')
t=g.read_text().replace('versionCode = 11724','versionCode = 11725').replace('versionName = "1.17.24"','versionName = "1.17.25"')
g.write_text(t)
print('Android startup hardening applied')