#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app
git checkout dev

echo "📝 МЕНЯЕМ ИМЯ ПАКЕТА ДЛЯ FONBET BOT..."

# Меняем пакет на com.example.fonbetbot
cat > app/build.gradle << 'APP_EOF'
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.fonbetbot'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.fonbetbot"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = '17'
    }
    
    buildFeatures {
        compose true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.8'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
    implementation 'androidx.activity:activity-compose:1.8.0'
    
    implementation platform('androidx.compose:compose-bom:2024.02.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
}
APP_EOF

# Создаём папку для нового пакета
mkdir -p app/src/main/java/com/example/fonbetbot

# Перемещаем MainActivity.kt в новый пакет
mv app/src/main/java/com/example/simpleapp/MainActivity.kt app/src/main/java/com/example/fonbetbot/MainActivity.kt

# Обновляем package в MainActivity.kt
sed -i 's/package com.example.simpleapp/package com.example.fonbetbot/' app/src/main/java/com/example/fonbetbot/MainActivity.kt

# Удаляем старую папку
rm -rf app/src/main/java/com/example/simpleapp

echo "✅ Пакет изменён на com.example.fonbetbot"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add .
git commit -m "Change package name to com.example.fonbetbot"
git push origin dev

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ НОВЫЙ APK БУДЕТ С ДРУГИМ ИМЕНЕМ ПАКЕТА!"
echo "════════════════════════════════════════════════════"
echo ""
echo "📱 После сборки Fonbet Bot установится рядом со старым"
echo "   Не нужно удалять Simple Bot!"
echo ""

