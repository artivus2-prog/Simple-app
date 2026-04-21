#!/data/data/com.termux/files/usr/bin/bash

cd ~/simple-app

echo "════════════════════════════════════════════════════"
echo "🌿 СОЗДАНИЕ ВЕТКИ DEV ДЛЯ РАЗРАБОТКИ"
echo "════════════════════════════════════════════════════"

# Создаём и переключаемся на ветку dev
git checkout -b dev

echo ""
echo "✅ Ветка dev создана!"
echo ""
echo "📂 ТЕКУЩАЯ ВЕТКА:"
git branch

echo ""
echo "💾 КОМАНДЫ ДЛЯ РАБОТЫ С ВЕТКАМИ:"
echo ""
echo "   📌 Сохранить изменения в dev:"
echo "      git add ."
echo "      git commit -m 'описание изменений'"
echo "      git push origin dev"
echo ""
echo "   🔄 Вернуться к рабочей версии (main):"
echo "      git checkout main"
echo ""
echo "   🔄 Вернуться к разработке (dev):"
echo "      git checkout dev"
echo ""
echo "   📦 Слить dev в main (когда всё готово):"
echo "      git checkout main"
echo "      git merge dev"
echo "      git push origin main"
echo ""
echo "   ⏪ Откатить изменения в dev:"
echo "      git log --oneline  (посмотреть историю)"
echo "      git reset --hard <хеш_коммита>"
echo ""
echo "════════════════════════════════════════════════════"
echo "✅ ГОТОВО! Можешь смело экспериментировать в ветке dev"
echo "════════════════════════════════════════════════════"

