# Keep model classes used via reflection by EUDI libs.
-keep class eu.europa.ec.eudi.** { *; }
-keep class com.nimbusds.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**