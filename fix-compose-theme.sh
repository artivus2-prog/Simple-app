#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app
git checkout dev

echo "📝 ИСПРАВЛЯЕМ ТЕМУ ДЛЯ COMPOSE..."

# Исправляем AndroidManifest.xml - убираем тему вообще (Compose сам управляет)
cat > app/src/main/AndroidManifest.xml << 'MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    
    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Fonbet Bot"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
    </application>
</manifest>
MANIFEST_EOF

echo "✅ Тема исправлена на Theme.DeviceDefault.NoActionBar"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add app/src/main/AndroidManifest.xml
git commit -m "Fix theme for Compose"
git push origin dev

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ИСПРАВЛЕНИЕ ОТПРАВЛЕНО! СБОРКА ЗАПУЩЕНА"
echo "════════════════════════════════════════════════════"
echo ""
echo "🔍 Проверить: https://github.com/artivus2-prog/simple-app/actions"
echo ""

