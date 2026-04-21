#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app

echo "📝 ИСПРАВЛЯЮ ВСЕ ФАЙЛЫ..."

# 1. Исправляем gradlew скрипт
cat > gradlew << 'GRADLEW_EOF'
#!/bin/sh

# Ищем где находится скрипт
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"

# Если jar нет - скачиваем
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading gradle-wrapper.jar to $WRAPPER_DIR..."
    mkdir -p "$WRAPPER_DIR"
    curl -L https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar -o "$WRAPPER_JAR"
fi

# Запускаем Gradle
java -jar "$WRAPPER_JAR" "$@"
GRADLEW_EOF

chmod +x gradlew

# 2. Исправляем gradle-wrapper.properties
cat > gradle/wrapper/gradle-wrapper.properties << 'PROPS_EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.0-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
PROPS_EOF

# 3. Исправляем workflow
cat > .github/workflows/build-apk.yml << 'WORKFLOW_EOF'
name: Build APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'

    - name: Setup Android SDK
      uses: android-actions/setup-android@v3
      with:
        packages: 'platforms;android-34 build-tools;34.0.0'
        accept-android-sdk-licenses: true

    - name: Build Debug APK
      run: ./gradlew assembleDebug --stacktrace

    - name: Upload APK to Artifacts
      uses: actions/upload-artifact@v4
      with:
        name: SimpleBot-App
        path: app/build/outputs/apk/debug/*.apk
        retention-days: 30
WORKFLOW_EOF

echo "✅ Файлы исправлены!"
echo ""
echo "📦 КОММИТИМ И ПУШИМ..."

git add gradlew
git add gradle/wrapper/gradle-wrapper.properties
git add .github/workflows/build-apk.yml
git commit -m "Fix gradlew script and wrapper"
git push origin main

echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ИЗМЕНЕНИЯ ОТПРАВЛЕНЫ!"
echo "════════════════════════════════════════════════════"
echo ""
echo "🔍 Теперь gradlew сам скачает jar в правильное место"
echo "   Сборка должна пройти успешно через 3-5 минут"
echo ""
echo "📱 APK будет в: Actions → последний запуск → Artifacts"
