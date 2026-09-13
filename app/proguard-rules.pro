# Proguard rules for VoiceQA
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.voiceqa.app.llm.** { *; }
