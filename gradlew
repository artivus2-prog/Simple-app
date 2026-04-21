#!/bin/sh
# Скрипт для запуска Gradle
exec sh -c '
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
if [ ! -f "$CLASSPATH" ]; then
    echo "Downloading gradle-wrapper.jar..."
    curl -L https://raw.githubusercontent.com/gradle/gradle/master/gradle/wrapper/gradle-wrapper.jar -o "$CLASSPATH"
fi
java -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
' sh "$@"
