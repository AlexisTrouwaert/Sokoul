-dontwarn **
-keep class org.sqlite.** { *; }
-keep class com.sokoul.** { *; }
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.** { *; }

# QuickJS (local extraction): libquickjs.so reads fields and calls methods by name over JNI. Renamed members abort the
# process ("JNI DETECTED ERROR: mid == null", Google TV 2026-11-15). UByteArray's "storage" field is read natively too.
-keep class com.dokar.quickjs.** { *; }
-keep class kotlin.UByteArray { *; }

# A11yGate (tv/.../A11yGate.kt) finds this field by name.
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat { java.util.List enabledServices; }
-keepnames class androidx.compose.ui.platform.AndroidComposeView
