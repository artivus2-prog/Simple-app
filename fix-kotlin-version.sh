#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app
git checkout dev

echo "📝 ИСПРАВЛЯЕМ ВЕРСИИ KOTLIN И COMPOSE..."

# Обновляем корневой build.gradle
cat > build.gradle << 'BUILD_EOF'
// Top-level build file
buildscript {
    ext.kotlin_version = '1.9.22'
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
BUILD_EOF

# Обновляем app/build.gradle
cat > app/build.gradle << 'APP_EOF'
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.example.simpleapp'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.simpleapp"
        minSdk 24
        targetSdk 34
        versionCode 2
        versionName "2.0"
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

echo "✅ Версии обновлены:"
echo "   - Kotlin: 1.9.0 → 1.9.22"
echo "   - Compose Compiler: 1.5.4 → 1.5.8"
echo "   - Compose BOM: 2023.10.01 → 2024.02.00"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add build.gradle app/build.gradle
git commit -m "Fix Kotlin and Compose versions compatibility"
git push origin dev

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ВЕРСИИ ИСПРАВЛЕНЫ! СБОРКА ЗАПУЩЕНА"
echo "════════════════════════════════════════════════════"
echo ""

