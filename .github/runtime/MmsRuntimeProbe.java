package vip.mystery0.pixel.text.mmsruntimeprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 独立、合成数据、仅回调后的生命周期；从不创建可提交的 READY/PREPARING 请求。 */
public final class MmsRuntimeProbe extends Instrumentation {
    private SQLiteDatabase db;
    private String phase;
    private File restartMarker(){return new File(getTargetContext().getCacheDir(),"mms-probe-restart.json");}
    private final List<String> passed = new ArrayList<>();
    private static final String PKG="vip.mystery0.pixel.text";
    private static final String RECEIVER=PKG+".mms.outgoing.MmsSentReceiver";
    private static final String ACTION=PKG+".action.MMS_SENT";
    @Override public void onCreate(Bundle args){super.onCreate(args);phase=args==null?null:args.getString("phase");start();}
    private void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    private interface Check { boolean get() throws Exception; }
    private void await(Check check,String message) throws Exception {long end=System.currentTimeMillis()+60000;while(System.currentTimeMillis()<end){if(check.get())return;Thread.sleep(200);}throw new AssertionError(message);}
    private long number(String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getLong(0):0;}}
    private String value(String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    private String snapshot()throws Exception{return new JSONObject().put("recipient","+12025550123").put("body","synthetic callback fixture").put("subject","")
        .put("subId",1).put("createdAt",1770000000000L).put("attachments",new JSONArray()).put("policy",new JSONObject().put("subId",1).put("maxBytes",300000).put("width",640).put("height",480).put("subject",40).put("text",20000).put("fingerprint","synthetic-policy")).toString();}
    private String seed(String state,boolean deleted)throws Exception {
        String token=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        ContentValues req=new ContentValues();req.put("id",id);req.put("draftId",UUID.randomUUID().toString());req.put("draftRevision",1);req.put("snapshot",snapshot());req.put("currentAttempt",token);req.putNull("sourceId");req.put("providerReady",1);req.put("providerSyncPending",1);req.put("deleted",deleted?1:0);req.putNull("possibleDuplicateOf");req.put("lateSuccess",0);req.putNull("originRequestId");
        ContentValues a=new ContentValues();a.put("token",token);a.put("requestId",id);a.put("number",1);a.put("transactionId","pt-"+token.replace("-",""));a.putNull("previousTransactionId");a.put("subscriptionId",1);a.put("state",state);a.put("createdAt",1770000000000L);a.put("submittedAt",System.currentTimeMillis()-16*60*1000);a.put("callbackRecorded",0);a.put("providerReady",1);
        db.beginTransaction();try{db.insertOrThrow("send_request",null,req);db.insertOrThrow("send_attempt",null,a);db.setTransactionSuccessful();}finally{db.endTransaction();}return token;
    }
    private void callback(String token,int code,byte[] response)throws Exception {
        Intent original=new Intent(ACTION).setClassName(PKG,RECEIVER).setData(Uri.parse("pixeltext://mms-send/"+token));
        PendingIntent sent=PendingIntent.getBroadcast(getTargetContext(),0,original,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        Intent fill=new Intent("unexpected-fill-action").setData(Uri.parse("pixeltext://mms-send/00000000-0000-0000-0000-000000000000"));
        if(response!=null)fill.putExtra(android.telephony.SmsManager.EXTRA_MMS_DATA,response);
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        sent.send(getTargetContext(),code,fill,(pending,intent,result,data,extras)->done.countDown(),null);
        check(done.await(30,java.util.concurrent.TimeUnit.SECONDS),"PendingIntent callback did not finish");
    }
    private byte[] conf(String token,int status)throws Exception {ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(0x8c);b.write(0x81);b.write(0x98);b.write(("pt-"+token.replace("-","")).getBytes(StandardCharsets.US_ASCII));b.write(0);b.write(0x8d);b.write(0x93);b.write(0x92);b.write(status);return b.toByteArray();}
    private void state(String token,String expected)throws Exception {await(()->expected.equals(value("SELECT state FROM send_attempt WHERE token=?",token)),"expected "+expected+" got "+value("SELECT state FROM send_attempt WHERE token=?",token));}
    @Override public void onStart(){Bundle out=new Bundle();try{
        check(getTargetContext().checkSelfPermission(android.Manifest.permission.SEND_SMS)==android.content.pm.PackageManager.PERMISSION_DENIED,"SEND_SMS must remain denied in synthetic probe");
        android.app.AppOpsManager ops=getTargetContext().getSystemService(android.app.AppOpsManager.class);
        check(ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_SEND_SMS,android.os.Process.myUid(),PKG)!=android.app.AppOpsManager.MODE_ALLOWED,"SEND_SMS app-op must remain blocked");
        passed.add("send_sms_permission_and_appop_blocked");
        File path=getTargetContext().getDatabasePath("outgoing_mms.db");await(()->path.isFile(),"outgoing Room database did not initialize");
        db=SQLiteDatabase.openDatabase(path.getPath(),null,SQLiteDatabase.OPEN_READWRITE);
        await(()->number("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='callback_fact'")==1,"missing callback schema");
        if("after_restart".equals(phase)) {
            JSONObject marker;
            try(FileInputStream input=new FileInputStream(restartMarker());ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[1024];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                marker=new JSONObject(bytes.toString("UTF-8"));
            }
            check(marker.getInt("pid")!=android.os.Process.myPid(),"target process did not restart");
            String token=marker.getString("token");state(token,"UNKNOWN");callback(token,Activity.RESULT_OK,null);state(token,"SENT");
            check(number("SELECT count(*) FROM send_attempt WHERE state IN ('PREPARING','READY','DISPATCHING')")==0,"restart produced submission state");
            out.putString("stream","\nPIXELTEXT_MMS_RESTART_PASS unknown_late_callback_after_real_process_restart\n");finish(Activity.RESULT_OK,out);return;
        }
        check(number("SELECT count(*) FROM send_request")==0,"probe requires clean synthetic app data");
        String sent=seed("AWAITING_RESULT",false),unknown=seed("UNKNOWN",false),dispatching=seed("DISPATCHING",false),deleted=seed("AWAITING_RESULT",true),reject=seed("AWAITING_RESULT",false),partial=seed("AWAITING_RESULT",false);
        callback(sent,Activity.RESULT_OK,null);state(sent,"SENT");callback(sent,Activity.RESULT_OK,null);Thread.sleep(1500);check(number("SELECT count(*) FROM callback_fact WHERE attemptToken=?",sent)==1,"duplicate callback was not deduplicated");passed.add("manifest_receiver_room_worker_platform_only_and_duplicate");
        callback(unknown,Activity.RESULT_OK,null);state(unknown,"SENT");passed.add("unknown_late_success");
        state(dispatching,"UNKNOWN");state(deleted,"UNKNOWN");check(number("SELECT count(*) FROM send_attempt WHERE state IN ('DISPATCHING','AWAITING_RESULT') AND token=?",deleted)==0,"deleted request retained SIM claim");check(number("SELECT deleted FROM send_request WHERE currentAttempt=?",deleted)==1,"deleted callback resurrected request");passed.add("restart_dispatch_fence_and_deleted_timeout");
        callback(reject,Activity.RESULT_OK,conf(reject,0x82));state(reject,"FAILED");passed.add("matching_mmsc_rejection_overrides_platform_ok");passed.add("mutable_pendingintent_preserves_identity_and_response");
        callback(partial,Activity.RESULT_OK,conf(partial,0xc4));await(()->number("SELECT count(*) FROM callback_fact WHERE attemptToken=? AND processed=1 AND responseStatus=196 AND confirmation='inconsistent_response'",partial)==1,"partial confirmation was not processed");state(partial,"UNKNOWN");passed.add("partial_success_does_not_retry");
        callback(reject,Activity.RESULT_OK,conf(reject,0x80));state(reject,"SENT");check(number("SELECT count(*) FROM callback_fact WHERE attemptToken=?",reject)==2,"distinct late outcome lost");passed.add("same_attempt_distinct_late_success_kept");
        long requests=number("SELECT count(*) FROM send_request");callback(UUID.randomUUID().toString(),Activity.RESULT_OK,null);Thread.sleep(1000);check(number("SELECT count(*) FROM send_request")==requests,"unassociated callback created request");check(number("SELECT count(*) FROM send_attempt WHERE state IN ('PREPARING','READY','DISPATCHING')")==0,"probe accidentally created submission state");passed.add("unassociated_callback_rejected_no_submission_states");
        Intent malformed=new Intent(Intent.ACTION_SEND).setClassName(PKG,PKG+".ComposeSmsActivity").setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,new Intent("synthetic-invalid-parcel")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=startActivitySync(malformed);await(()->{String content=value("SELECT content FROM draft ORDER BY updatedAt DESC LIMIT 1");return content!=null&&!new JSONObject(content).isNull("importError");},"malformed parcel did not persist import block");String json=value("SELECT content FROM draft ORDER BY updatedAt DESC LIMIT 1");check(new JSONObject(json).getString("recipient").isEmpty(),"malformed share adopted a recipient");runOnMainSync(activity::finish);passed.add("malformed_external_parcel_safe_ui_and_durable_block");
        String restartToken=seed("UNKNOWN",false);
        JSONObject marker=new JSONObject().put("token",restartToken).put("pid",android.os.Process.myPid());
        try(FileOutputStream output=new FileOutputStream(restartMarker())){output.write(marker.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        out.putString("stream","\nPIXELTEXT_MMS_RUNTIME_PASS "+String.join(",",passed)+"\n");finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","\nPIXELTEXT_MMS_RUNTIME_FAIL passed="+passed+"\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}finally{if(db!=null)db.close();}}
}
