# Apache POI
-keep class org.apache.poi.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemasMicrosoftComOfficeExcel.** { *; }
-keep class schemasMicrosoftComOfficeWord.** { *; }
-keep class schemasMicrosoftComOfficePowerpoint.** { *; }
-keep class org.apache.poi.schemas.** { *; }

# Игнорируем предупреждения
-dontwarn org.apache.poi.**
-dontwarn org.openxmlformats.**
-dontwarn org.apache.xmlbeans.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn java.lang.invoke.**