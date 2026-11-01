# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep SafetyCore public API
-keep public class com.nihildigit.safetycore.SafetyCoreEngine {
    public *;
}

-keep public class com.nihildigit.safetycore.orchestrator.FrameProcessor {
    public *;
}

# Keep data classes (used for API)
-keep class com.nihildigit.safetycore.core.** { *; }
-keep class com.nihildigit.safetycore.orchestrator.FrameResult { *; }
-keep class com.nihildigit.safetycore.orchestrator.VisualData { *; }
-keep class com.nihildigit.safetycore.orchestrator.TelemetryData { *; }
-keep class com.nihildigit.safetycore.sensors.ImuSample { *; }
-keep class com.nihildigit.safetycore.sensors.ImuPoseDelta { *; }

# Keep extension point interfaces
-keep interface com.nihildigit.safetycore.sensors.ImuProvider { *; }
-keep interface com.nihildigit.safetycore.depth.DepthNormalizer { *; }
-keep interface com.nihildigit.safetycore.threat.ThreatAssessor { *; }

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep OpenCV
-keep class org.opencv.** { *; }

# Keep QNN SDK
-keep class com.nihildigit.qnninferencesdk.** { *; }
