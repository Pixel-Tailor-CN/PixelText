package vip.mystery0.pixel.text.mms.vendor;

/** 上游调试输出可能包含消息字段，应用构建统一禁用。 */
public final class ParserLog {
    private ParserLog() {}
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
}
