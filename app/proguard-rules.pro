# Retichat JNI bridge
# Rust resolves these callback contracts by exact class/method name.
-keep class com.newendian.retichat.bridge.RetichatBridge { *; }
-keep interface com.newendian.retichat.bridge.MessageCallback { *; }
-keep interface com.newendian.retichat.bridge.AnnounceCallback { *; }
-keep interface com.newendian.retichat.bridge.RfedBlobCallback { *; }
-keep interface com.newendian.retichat.bridge.AppLinkStatusCallback { *; }
-keep interface com.newendian.retichat.bridge.AppLinkPacketCallback { *; }
-keep interface com.newendian.retichat.bridge.AppLinkRequestCallback { *; }
-keep interface com.newendian.retichat.bridge.AppLinkSendCallback { *; }
-keep class * implements com.newendian.retichat.bridge.MessageCallback { *; }
-keep class * implements com.newendian.retichat.bridge.AnnounceCallback { *; }
-keep class * implements com.newendian.retichat.bridge.RfedBlobCallback { *; }
-keep class * implements com.newendian.retichat.bridge.AppLinkStatusCallback { *; }
-keep class * implements com.newendian.retichat.bridge.AppLinkPacketCallback { *; }
-keep class * implements com.newendian.retichat.bridge.AppLinkRequestCallback { *; }
-keep class * implements com.newendian.retichat.bridge.AppLinkSendCallback { *; }
