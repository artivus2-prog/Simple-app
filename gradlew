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
