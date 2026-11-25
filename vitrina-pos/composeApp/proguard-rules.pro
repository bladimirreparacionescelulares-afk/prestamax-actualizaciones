# SQLDelight y el driver de Android no usan reflexión; se conservan los modelos por si acaso.
-keep class com.vitrinapos.db.** { *; }
# Escáner de códigos de Google Play
-keep class com.google.mlkit.** { *; }
-dontwarn org.slf4j.**
