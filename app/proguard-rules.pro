# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# native_aom_encoder.cpp constructs NativeAomPacket through JNI (FindClass + "<init>([BJZI)V").
# Kotlin never calls that constructor, so R8 would otherwise remove or rewrite it.
-keep class com.example.bluetoothtest.NativeAomPacket {
    <init>(byte[], long, boolean, int);
    <fields>;
}
# LeConnectionParameters.kt: the framework calls the hidden (@hide) onConnectionUpdated callbacks on
# every LE connection update. They are not in the SDK stubs, so R8 sees no override and would remove
# or rename these methods.
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback {
    public void onConnectionUpdated(android.bluetooth.BluetoothGatt, int, int, int, int);
}
-keepclassmembers class * extends android.bluetooth.BluetoothGattServerCallback {
    public void onConnectionUpdated(android.bluetooth.BluetoothDevice, int, int, int, int);
}
