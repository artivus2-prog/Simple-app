#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app

echo "📝 ИСПРАВЛЯЮ НАСТРОЙКИ GRADLE..."

# Исправляем settings.gradle
cat > settings.gradle << 'SETTINGS_EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SimpleApp"
include ':app'
SETTINGS_EOF

# Исправляем корневой build.gradle (убираем репозитории)
cat > build.gradle << 'BUILD_EOF'
// Top-level build file
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
BUILD_EOF

echo "✅ Настройки Gradle исправлены!"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add settings.gradle build.gradle
git commit -m "Fix Gradle repository configuration"
git push origin main

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ИЗМЕНЕНИЯ ОТПРАВЛЕНЫ!"
echo "════════════════════════════════════════════════════"
echo ""
echo "🔍 Теперь сборка должна пройти успешно!"
echo "   Проверь: https://github.com/artivus2-prog/simple-app/actions"
