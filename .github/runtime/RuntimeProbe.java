package vip.mystery0.pixel.text.runtimeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** 独立测试 APK：只加载最终 Release 的类，不给应用添加 keep 或测试入口。 */
public final class RuntimeProbe extends Instrumentation {
    private JSONObject config;
    private ClassLoader loader;
    private final List<String> passed = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
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
    private void backup() throws Exception {
        Constructor<?> ctor = c("codec").getDeclaredConstructor(Context.class); ctor.setAccessible(true);
        Object codec = ctor.newInstance(getTargetContext());
        Object adapter = field(codec, "codecAdapter");
        Method from = c("adapter").getDeclaredMethod(n("fromJson"), String.class); from.setAccessible(true);
        Method to = c("adapter").getDeclaredMethod(n("toJson"), Object.class); to.setAccessible(true);
        String json = "{\"format\":\"pixeltext-backup\",\"schemaVersion\":1,\"appVersion\":\"synthetic-ci\",\"createdAt\":0,\"smsSyncedAt\":null,\"sections\":[\"SETTINGS\",\"RULES\",\"SMS\"],\"smsCount\":0,\"mirrorVersion\":3,\"spamVersion\":3,\"archiveVersion\":1,\"entries\":[]}";
        Object manifest = call(from, adapter, json);
        JSONObject roundtrip = new JSONObject((String) call(to, adapter, manifest));
        check(roundtrip.getJSONArray("sections").toString().equals("[\"SETTINGS\",\"RULES\",\"SMS\"]"), "backup section names changed");
        passed.add("backup_adapter_all_enum_values_json_roundtrip");
        Object context = singleton("emptyContext", "emptyContextInstance");
        Object continuation = Proxy.newProxyInstance(loader, new Class[]{c("continuation")}, (p,m,a) -> {
            if (m.getParameterTypes().length == 0) return context;
            throw new AssertionError("unexpected asynchronous suspension in archive codec");
        });
        File root = new File(getTargetContext().getCacheDir(), "runtime-regression"); root.mkdirs();
        for (boolean encrypted : new boolean[]{false, true}) {
            File exportDir = new File(root, "export-" + encrypted); exportDir.mkdirs();
            File inspectDir = new File(root, "inspect-" + encrypted); inspectDir.mkdirs();
            File archive = new File(root, "synthetic-" + encrypted + ".zip");
            char[] password = encrypted ? "synthetic-ci-only".toCharArray() : null;
            call(method(c("codec"), n("export"), 5), codec, exportDir, manifest, archive.toURI().toString(), password, continuation);
            check(archive.length() > 0, "archive export empty");
            Object validated = call(method(c("codec"), n("inspect"), 4), codec, archive.toURI().toString(), password, inspectDir, continuation);
            check(validated != null, "archive inspect null");
            JSONObject inspected = new JSONObject(Files.readString(new File(inspectDir, "manifest.json").toPath()));
            check(inspected.getJSONArray("sections").length() == 3, "archive enum roundtrip failed");
            passed.add(encrypted ? "backup_aes_archive_export_inspect" : "backup_plain_archive_export_inspect");
        }
    }
    private void contact(String label, String text, String name, String phone, String email, String type, boolean photo) throws Exception {
        Object unit = singleton("unit", "unitInstance");
        Object noop = Proxy.newProxyInstance(loader, new Class[]{c("function")}, (p,m,a) -> unit);
        Method parse = method(c("parser"), n("parse"), 2);
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
        if (photo) check(Arrays.equals((byte[])field(card,"contact_photoBytes"),new byte[]{1,2,3}),label+" photo mismatch");
        passed.add(label);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            loader = getTargetContext().getClassLoader();
            config = new JSONObject(new String(getContext().getAssets().open("mapping.json").readAllBytes(), StandardCharsets.UTF_8));
            backup();
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
