package vip.mystery0.pixel.text.runtimeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** 独立测试 APK：只加载最终 Release 的类，不给应用添加 keep 或测试入口。 */
public final class RuntimeProbe extends Instrumentation {
    private JSONObject config;
    private ClassLoader loader;
    private final List<String> passed = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private String readText(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private String n(String key) throws Exception { return config.getString(key); }
    private Class<?> c(String key) throws Exception { return Class.forName(n(key), true, loader); }
    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(n(name)); field.setAccessible(true); return field.get(target);
    }
    private Object singleton(String cls, String key) throws Exception {
        Field field = c(cls).getDeclaredField(n(key)); field.setAccessible(true); return field.get(null);
    }
    private void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    private Method method(Class<?> cls, String name, int args) {
        for (Method m : cls.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == args) { m.setAccessible(true); return m; }
        throw new AssertionError("missing runtime method " + cls.getName() + "." + name);
    }
    private Object call(Method m, Object target, Object... args) throws Exception {
        try { return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, args); }
        catch (InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
    }
    private void contact(String label, String text, String name, String phone, String email, String type, boolean photo) throws Exception {
        Object unit = singleton("unit", "unitInstance");
        Method parse = method(c("parser"), n("parse"), 2);
        Class<?> callbackClass = parse.getParameterTypes()[1];
        Object noop;
        if (callbackClass.isInterface()) {
            noop = Proxy.newProxyInstance(loader, new Class[]{callbackClass}, (p,m,a) -> unit);
        } else {
            // 生产调用方的取消检查 lambda 已被 R8 收窄；用空协程上下文构造同一个 lambda。
            Constructor<?> callbackCtor = callbackClass.getDeclaredConstructors()[0]; callbackCtor.setAccessible(true);
            Class<?>[] types = callbackCtor.getParameterTypes();
            check(types.length == 2 && types[1] == byte.class, "unexpected optimized callback signature");
            noop = callbackCtor.newInstance(singleton("emptyContext", "emptyContextInstance"), (byte)0);
        }
        Object parser = Modifier.isStatic(parse.getModifiers()) ? null : c("parser").getDeclaredConstructor().newInstance();
        List<?> cards = (List<?>) call(parse, parser, text.replace("\n", "\r\n"), noop);
        check(cards.size() == 1, label + " wrong card count"); Object card = cards.get(0);
        check(field(card,"contact_importWarning") == null, label + " degraded: " + field(card,"contact_importWarning"));
        check(name.equals(field(card,"contact_name")), label + " name mismatch");
        if (phone != null) {
            List<?> values = (List<?>)field(card,"contact_phones"); check(values.size() == 1, label + " phone count");
            check(phone.equals(field(values.get(0),"value_value")), label + " phone mismatch");
            check(type.equalsIgnoreCase((String)field(values.get(0),"value_label")), label + " type mismatch");
        }
        if (email != null) { List<?> values=(List<?>)field(card,"contact_emails"); check(values.size()==1 && email.equals(field(values.get(0),"value_value")), label+" email mismatch"); }
        if (type.equals("x-ci")) {
            for (String listKey : new String[]{"contact_emails", "contact_addresses"}) {
                List<?> values = (List<?>)field(card, listKey);
                check(values.size() == 1 && type.equalsIgnoreCase((String)field(values.get(0), "value_label")), label + " unknown parameter mismatch " + listKey);
            }
        }
        if (photo) check(Arrays.equals((byte[])field(card,"contact_photoBytes"),new byte[]{1,2,3}),label+" photo mismatch");
        passed.add(label);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            config = new JSONObject(readText(getContext().getAssets().open("mapping.json")));
            contact("vcard21_quoted_printable", "BEGIN:VCARD\nVERSION:2.1\nFN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E6=B5=8B=E8=AF=95=\n=E8=81=94=E7=B3=BB=E4=BA=BA\nTEL;CELL:10000000001\nEND:VCARD\n", "测试联系人", "10000000001", null, "cell", false);
            contact("vcard30_standard_fields_photo", "BEGIN:VCARD\nVERSION:3.0\nFN:CI Contact\nTEL;TYPE=CELL:10000000002\nEMAIL;TYPE=WORK:ci@example.invalid\nPHOTO;ENCODING=b;TYPE=JPEG:AQID\nEND:VCARD\n", "CI Contact", "10000000002", "ci@example.invalid", "cell", true);
            contact("vcard40_unknown_reflective_types", "BEGIN:VCARD\nVERSION:4.0\nFN:CI Unknown\nTEL;TYPE=X-CI:tel:+10000000003\nEMAIL;TYPE=X-CI:unknown@example.invalid\nADR;TYPE=X-CI:;;Synthetic Street;Example;;;\nPHOTO;MEDIATYPE=image/x-ci:data:image/x-ci;base64,AQID\nEND:VCARD\n", "CI Unknown", "+10000000003", "unknown@example.invalid", "x-ci", true);
            call(method(c("tflite"), n("tfliteInit"), 0), null);
            passed.add("production_arm64_tensorflow_jni_initialization");
            result.putString("stream", "\nPIXELTEXT_RUNTIME_PASS " + String.join(",", passed) + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nPIXELTEXT_RUNTIME_FAIL passed=" + passed + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
