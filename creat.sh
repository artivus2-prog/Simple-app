#!/bin/bash

# ============================================
# Скрипт создания иконки для Фонбет Бот
# Буква "Ф" на зелёном фоне (#0ECB81)
# ============================================

PROJECT_DIR="."

echo "🎨 Создание иконок для Фонбет Бот..."

# Создаём необходимые папки
mkdir -p $PROJECT_DIR/app/src/main/res/drawable
mkdir -p $PROJECT_DIR/app/src/main/res/mipmap-anydpi-v26
mkdir -p $PROJECT_DIR/app/src/main/res/values

# 1. Файл с цветами
cat > $PROJECT_DIR/app/src/main/res/values/colors.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0ECB81</color>
</resources>
EOF

# 2. Векторная иконка с буквой "Ф"
cat > $PROJECT_DIR/app/src/main/res/drawable/ic_launcher_foreground.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    
    <!-- Стилизованная буква "Ф" -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M40,30 L40,78 L48,78 L48,58 L60,58 L60,78 L68,78 L68,30 L60,30 L60,38 L48,38 L48,30 Z" />
    
    <!-- Декоративный овал -->
    <path
        android:fillColor="#00000000"
        android:pathData="M28,28 L80,28 L80,80 L28,80 Z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="2" />
    
</vector>
EOF

# 3. Адаптивная иконка (обычная)
cat > $PROJECT_DIR/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
EOF

# 4. Адаптивная иконка (круглая)
cat > $PROJECT_DIR/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
</adaptive-icon>
EOF

# 5. Название приложения
cat > $PROJECT_DIR/app/src/main/res/values/strings.xml << 'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Фонбет Бот</string>
</resources>
EOF

echo "✅ Все файлы иконки созданы успешно!"
echo ""
echo "Созданные файлы:"
echo "  ├── app/src/main/res/values/colors.xml"
echo "  ├── app/src/main/res/drawable/ic_launcher_foreground.xml"
echo "  ├── app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
echo "  ├── app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"
echo "  └── app/src/main/res/values/strings.xml"
echo ""
echo "Теперь выполните в Android Studio:"
echo "  Build → Clean Project"
echo "  Build → Rebuild Project"
echo ""
echo "Иконка: 🟢 Зелёный фон + ⚪ Буква 'Ф' + Название 'Фонбет Бот'"