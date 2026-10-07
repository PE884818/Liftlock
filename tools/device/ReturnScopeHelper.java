package com.zhuanz.flymemotion.probe;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Process;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Root-only, module-specific scope edit with a consistent SQLite backup. No service restart. */
public final class ReturnScopeHelper {
    public static void main(String[] args){
        try{run(args);}catch(Throwable t){t.printStackTrace(System.out);System.exit(1);}System.exit(0);
    }
    private static void run(String[] args)throws Exception {
        if(Process.myUid()!=0||!"myron".equals(Build.DEVICE)||!"OS4.0.0.30.XPMCNXM".equals(Build.VERSION.INCREMENTAL))
            throw new IllegalStateException("Authorized target root required");
        String database="/data/adb/lspd/config/modules_config.db";
        boolean remove=args.length==2&&"remove-added".equals(args[0]);
        boolean write=args.length==2&&("apply".equals(args[0])||remove);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database,null,(write?SQLiteDatabase.OPEN_READWRITE|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING:SQLiteDatabase.OPEN_READONLY)|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
            JSONObject result=new JSONObject();JSONArray schema=new JSONArray();
            try(Cursor rows=db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table'",null)){
                while(rows.moveToNext())schema.put(new JSONObject().put("table",rows.getString(0)).put("sql",rows.getString(1)));
            }
            result.put("schema",schema);
            String module="com.zhuanz.flymemotion";
            JSONArray before=scopes(db,module);result.put("before",before);
            if(!write){System.out.println(result);return;}
            try(Cursor rows=db.rawQuery("SELECT enabled FROM modules_state WHERE module_pkg_name=? AND user_id=0",new String[]{module})){
                if(!rows.moveToFirst()||rows.getInt(0)!=1)throw new IllegalStateException("Existing module must already be enabled");
            }
            JSONArray requested=new JSONArray(Files.readString(Path.of(args[1])));
            if(requested.length()>256)throw new IllegalStateException("Unexpected scope count");
            for(int i=0;i<requested.length();i++)if(!requested.getString(i).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))throw new IllegalStateException("Invalid package");
            Path folder=Path.of("/data/adb/lspd/config/flyme-scope-backups");Files.createDirectories(folder);android.system.Os.chmod(folder.toString(),0700);
            String backup=folder.resolve((remove?"before-scope-removal-":"before-0.2.18-")+System.currentTimeMillis()+".db").toString();
            db.execSQL("VACUUM INTO '"+backup+"'");android.system.Os.chmod(backup,0600);
            db.beginTransaction();
            try{
                for(int i=0;i<requested.length();i++){
                    String pkg=requested.getString(i);
                    if(remove){
                        if(java.util.Set.of("com.android.systemui","com.android.settings","com.miui.miwallpaper").contains(pkg))throw new IllegalStateException("Do not remove original scope");
                        db.execSQL("DELETE FROM scope WHERE module_pkg_name=? AND app_pkg_name=? AND user_id=0",new Object[]{module,pkg});
                    }else db.execSQL("INSERT OR IGNORE INTO scope(module_pkg_name,app_pkg_name,user_id) VALUES(?,?,?)",new Object[]{module,pkg,0});
                }
                db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            result.remove("schema");result.put("backup",backup).put("after",scopes(db,module)).put("serviceRestarted",false);
            System.out.println(result);
        }
    }
    private static JSONArray scopes(SQLiteDatabase db,String module){
        JSONArray result=new JSONArray();
        try(Cursor rows=db.rawQuery("SELECT app_pkg_name FROM scope WHERE module_pkg_name=? AND user_id=0 ORDER BY app_pkg_name",new String[]{module})){
            while(rows.moveToNext())result.put(rows.getString(0));
        }return result;
    }
}
