#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app

echo "📝 ИСПРАВЛЯЮ ТЕМУ В ANDROIDMANIFEST.XML..."

# Исправляем AndroidManifest.xml с правильной темой
cat > app/src/main/AndroidManifest.xml << 'MANIFEST_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    
    <uses-permission android:name="android.permission.INTERNET" />
    
    <application
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="Простой Бот"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
        
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
    </application>
</manifest>
MANIFEST_EOF

echo "✅ Тема исправлена на Theme.AppCompat.Light.DarkActionBar"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add app/src/main/AndroidManifest.xml
git commit -m "Fix theme to AppCompat"
git push origin main

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ИСПРАВЛЕНИЕ ОТПРАВЛЕНО!"
echo "════════════════════════════════════════════════════"
echo ""
echo "🔍 Теперь всё должно работать!"
