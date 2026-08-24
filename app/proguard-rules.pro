# Keep only exported / reflection entry points if minify is ever enabled.
-keep class com.drivehub.mgha.ui.MainActivity
-keep class com.drivehub.mgha.service.HaBridgeService
-keep class com.drivehub.mgha.receiver.BootReceiver
-dontwarn androidx.core.app.**
