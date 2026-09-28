# GeckoView ships its own consumer rules inside the AAR, including
# `-keep class org.mozilla.geckoview.** { *; }` and the JNI/reflection
# annotations the engine resolves at runtime, so the engine itself is covered.
# These are the app-side entry points R8 cannot see for itself.

# Components are named in AndroidManifest.xml and instantiated by the platform,
# not from Kotlin, so their names have to survive. AGP keeps the components the
# manifest declares, but the shell also references these by name from code.
-keep class com.palash.librewolfandroid.MainActivity { *; }
-keep class com.palash.librewolfandroid.GeckoCrashService { *; }
-keep class com.palash.librewolfandroid.DownloadService { *; }
-keep class com.palash.librewolfandroid.BrowserMediaService { *; }
-keep class com.palash.librewolfandroid.MediaActionReceiver { *; }
-keep class com.palash.librewolfandroid.LibreWolfApplication { *; }

# GeckoView looks these up as the runtime's collaborators. They are passed as
# objects, so R8 would normally inline or rename them, but the engine keeps
# them alive past the call and reaches back into them later.
-keep class com.palash.librewolfandroid.BrowserAutocompleteDelegate { *; }
-keep class com.palash.librewolfandroid.BrowserNotificationDelegate { *; }
-keep class com.palash.librewolfandroid.BasicSelectionActionDelegate { *; }
-keep class com.palash.librewolfandroid.BrowserPermissionDelegate { *; }

# Dialog and bottom-sheet fragments are recreated by name from saved state after
# a process death or a rotation, which is a reflective lookup.
-keep public class * extends androidx.fragment.app.DialogFragment { *; }
-keep public class * extends androidx.fragment.app.Fragment { *; }

# The permission prompt is built from a JSON blob the engine hands back, and the
# download and history records are persisted as JSON, so their field names are
# part of the on-disk format and must not be obfuscated.
-keepclassmembers class com.palash.librewolfandroid.** {
    <fields>;
}

# Kotlin metadata is read reflectively by the crash handler and by tooling.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

# No telemetry or analytics SDK is present, so nothing else needs suppressing.
