# Consumer proguard rules for safetycore-core library
# These rules are automatically applied to consumers of this library

# Keep public API
-keep public class com.nihildigit.safetycore.** {
    public protected *;
}

# Keep data classes used in API
-keepclassmembers class com.nihildigit.safetycore.** {
    public <fields>;
    public <methods>;
}

# Keep Parcelable implementations (if any)
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
