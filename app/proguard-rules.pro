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

# ez-vcard 的 CaseClasses 通过 getFields() 枚举常量，并比较字段的声明类与类型。
# 保留类身份及公开静态字段，避免裁剪和类合并；反射不依赖名称，仍允许混淆。
-keep,allowobfuscation class ezvcard.VCardDataType {
    public static ezvcard.VCardDataType *;
}
-keep,allowobfuscation class ezvcard.parameter.** extends ezvcard.parameter.VCardParameter {
    public static <fields>;
    # 未知参数与媒体类型通过 getDeclaredConstructor() 创建实例。
    <init>(java.lang.String);
    <init>(java.lang.String, ezvcard.VCardVersion[]);
    <init>(java.lang.String, java.lang.String, java.lang.String);
}
