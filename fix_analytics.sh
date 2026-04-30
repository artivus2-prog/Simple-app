#!/bin/bash

echo "🔍 Проверяем AnalyticsScreen.kt..."

# Проверяем наличие isLoading = true в лаунчерах
if grep -n "importMatches\|importExpresses" app/src/main/java/com/example/fonbetbot/AnalyticsScreen.kt | while read line; do
    echo "Найдена строка импорта: $line"
done

echo ""
echo "Убедитесь, что после строк:"
echo "  importResult = \"✅ ...\""
echo "  isImporting = false"
echo "Присутствует строка:"
echo "  isLoading = true"
echo ""
echo "Если её нет, добавьте вручную!"

