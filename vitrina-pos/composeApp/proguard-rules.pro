# SQLDelight y el driver de Android no usan reflexión; se conservan los modelos por si acaso.
-keep class com.vitrinapos.db.** { *; }
# Escáner de códigos de Google Play
-keep class com.google.mlkit.** { *; }
-dontwarn org.slf4j.**

# BouncyCastle: el proveedor busca sus clases por nombre (lectura de certificados .p12)
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
