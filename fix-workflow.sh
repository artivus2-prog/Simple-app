#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app

echo "📝 ИСПРАВЛЯЮ WORKFLOW..."

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

    - name: Create gradle wrapper directory
      run: mkdir -p gradle/wrapper

    - name: Download gradle-wrapper.jar
      run: |
        cd gradle/wrapper
        curl -L https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar -o gradle-wrapper.jar
        ls -la
        cd ../..

    - name: Grant execute permission for gradlew
      run: |
        chmod +x gradlew
        ls -la gradle/wrapper/

    - name: Build Debug APK
      run: ./gradlew assembleDebug --stacktrace --no-daemon

    - name: Upload APK to Artifacts
      uses: actions/upload-artifact@v4
      with:
        name: SimpleBot-App
        path: app/build/outputs/apk/debug/*.apk
        retention-days: 30
WORKFLOW_EOF

echo "✅ Workflow исправлен!"
echo ""
echo "📦 КОММИТИМ И ПУШИМ ИЗМЕНЕНИЯ..."

git add .github/workflows/build-apk.yml
git commit -m "Fix gradle-wrapper.jar path"
git push origin main

echo ""
echo "✅ ИЗМЕНЕНИЯ ОТПРАВЛЕНЫ!"
echo ""
echo "🔍 Проверь GitHub Actions: сборка должна начаться автоматически"
