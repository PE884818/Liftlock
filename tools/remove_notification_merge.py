"""Remove the withdrawn notification-center feature from runtime sources and tests."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
def edit(relative,fn):
    p=root/relative;p.write_text(fn(p.read_text(encoding='utf-8')),encoding='utf-8')
def remove_method(s,signature):
    start=s.index(signature);brace=s.index('{',start);i=brace+1;level=1
    while level:
        if s[i]=='{':level+=1
        elif s[i]=='}':level-=1
        i+=1
    return s[:start]+s[i:]
base='app/src/main/java/com/zhuanz/flymemotion/'
edit('motion-core/src/main/java/com/zhuanz/flymemotion/core/MotionConfig.java',lambda s:s.replace('notifications','lateDesktopAnimation').replace('withNotifications','withLateDesktopAnimation'))
edit('motion-core/src/main/java/com/zhuanz/flymemotion/core/MotionPolicy.java',lambda s:s.replace('surface == Surface.LOCKSCREEN || (surface == Surface.NOTIFICATIONS && config.notifications)','surface == Surface.LOCKSCREEN'))
edit(base+'ConfigCodec.java',lambda s:s.replace('.put("notifications", c.notifications)','.put("lateDesktopAnimation", c.lateDesktopAnimation)').replace('j.optBoolean("notifications", false)','j.optBoolean("lateDesktopAnimation", false)'))
edit(base+'hook/MotionModule.java',lambda s:s.replace('                NotificationMotionHooks notification=new NotificationMotionHooks(this,loader,reporter,hooks::ownsWindow);\n                notification.install();hooks.onExternalRevoke(notification::revoke);\n',''))
def manager(s):
    s=s.replace('ID_NOTIFICATION','ID_LATE_DESKTOP').replace('notificationEnabled','lateDesktopEnabled').replace('notificationStatus','desktopAnimationStatus')
    a=s.index('        section("通知中心")');b=s.index('        section("生效状态")',a)
    s=s[:a]+'''        section("锁屏到桌面"); LinearLayout desktop=card();
        LinearLayout desktopRow=row(),desktopDescription=column();
        desktopDescription.addView(text("晚加载桌面动画",16,primary));
        desktopDescription.addView(text("保留滑动透出，过渡交接完成后播放原生桌面入场；返回应用不变",12,secondary));
        desktopRow.addView(desktopDescription,new LinearLayout.LayoutParams(0,-2,1));
        lateDesktopEnabled=new Switch(this);lateDesktopEnabled.setId(ID_LATE_DESKTOP);
        lateDesktopEnabled.setContentDescription("晚加载桌面动画");lateDesktopEnabled.setChecked(store.get().lateDesktopAnimation);
        lateDesktopEnabled.setThumbTintList(ColorStateList.valueOf(accent));desktopRow.addView(lateDesktopEnabled);
        lateDesktopEnabled.setOnCheckedChangeListener((button,checked)->{if(!updating)store.save(store.get().withLateDesktopAnimation(checked));});
        desktopRow.setOnClickListener(v->lateDesktopEnabled.setChecked(!lateDesktopEnabled.isChecked()));desktop.addView(desktopRow);
        desktopAnimationStatus=text("默认关闭 · 当前手势结束后应用新选择",12,secondary);desktop.addView(desktopAnimationStatus);
        content.addView(desktop);gap(18);

'''+s[b:]
    s=s.replace('store.get().notifications','store.get().lateDesktopAnimation').replace('c.notifications','c.lateDesktopAnimation')
    start=s.index('            desktopAnimationStatus.setText(');end=s.index('\n',start)
    s=s[:start]+'            desktopAnimationStatus.setText("桌面动画："+ui.desktopAnimationState+"\\n"+ui.desktopAnimationDetail);'+s[end:]
    return s
edit(base+'ManagerActivity.java',manager)
def wallpaper(s):
    a=s.index('    private final SceneLease presentationLease');b=s.index('    private Context context;',a);s=s[:a]+s[b:]
    s=s.replace('if (!lease.active(now)&&!presentationLease.active(now)) { end();endPresentation();return; }','if (!lease.active(now)) { end();return; }')
    s=s.replace('            if(!presentationLease.active(now)&&presentationLease.id()>=0)endPresentation();\n','').replace('                    if(presentationLease.id()>=0)publishPresentation();\n','')
    s=s.replace('lease.id() >= 0||presentationLease.id()>=0','lease.id() >= 0')
    a=s.index('            context.registerReceiver(new BroadcastReceiver(){');b=s.index('            reporter.wallpaper = ',a)
    s=s[:a]+'            SceneReceivers.revoke(context, main, reporter, this::end);\n'+s[b:]
    s=s.replace('                if(presentationLease.active(SystemClock.uptimeMillis())&&!lease.active(SystemClock.uptimeMillis())&&(e.which&2)==0)continue;\n','')
    for signature in ('    private void receivePresentation(', '    private void publishPresentation(', '    private void endPresentation('):s=remove_method(s,signature)
    return s.replace('if(presentationLease.id()<0)main.removeCallbacks(keepAlive);','main.removeCallbacks(keepAlive);').replace('end();endPresentation();','end();')
edit(base+'hook/WallpaperHooks.java',wallpaper)
edit(base+'SceneProtocol.java',lambda s:'\n'.join(line for line in s.split('\n') if 'public static final String PRESENTATION' not in line))
def instrumentation(s):
    for name in ('notificationChannelPermissionRegression','notificationWallpaperMirrorRegression','sharedPresentationLeaseRegression','notificationRenderBindingRegression'):
        s=s.replace('        '+name+'();\n','').replace('        runOnMainSync(this::'+name+');\n','')
        s=remove_method(s,'    private void '+name+'(')
    s=s.replace('.notifications','.lateDesktopAnimation').replace('ID_NOTIFICATION','ID_LATE_DESKTOP')
    s=s.replace('"notifications", true','"notifications", true')
    a=s.index('        JSONObject notification =');b=s.index('        runOnMainSync(this::poseRegression);',a)
    s=s[:a]+'''        JSONObject oldConfig=new JSONObject(ConfigCodec.encode(initial)).remove("lateDesktopAnimation") instanceof JSONObject ? new JSONObject(ConfigCodec.encode(initial)) : null;
        oldConfig.remove("lateDesktopAnimation");oldConfig.put("notifications",true);
        check(!ConfigCodec.decode(oldConfig.toString()).lateDesktopAnimation,"legacy_notification_flag_cannot_enable_desktop_animation");
        check(!new JSONObject(ConfigCodec.encode(ConfigCodec.decode(oldConfig.toString()))).has("notifications"),"withdrawn_notification_flag_not_written");
        check(ConfigCodec.decode(ConfigCodec.encode(initial.withLateDesktopAnimation(true))).lateDesktopAnimation,"late_desktop_roundtrip");
'''+s[b:]
    s=s.replace('notification_toggle','late_desktop_toggle').replace('notification_enablement','late_desktop_choice').replace('notification_default','late_desktop_default')
    return s
edit('app/src/androidTest/java/com/zhuanz/flymemotion/ManagerInstrumentation.java',instrumentation)
edit('motion-core/src/test/java/com/zhuanz/flymemotion/core/MotionCoreTest.java',lambda s:s.replace('c.notifications','c.lateDesktopAnimation'))
files=[base+'hook/'+n+'.java' for n in ('NotificationMotionHooks','NativeNotificationPresentation','SharedLockscreenLayout','NotificationWallpaper')]
files+=[base+'ViewPresentationLease.java']
files+=['motion-core/src/main/java/com/zhuanz/flymemotion/core/'+n+'.java' for n in ('NotificationSheet','NotificationPresentationPolicy','SheetTouchProgress','ValuePresentationLease')]
files+=['motion-core/src/test/java/com/zhuanz/flymemotion/core/'+n+'.java' for n in ('NotificationSheetTest','NotificationPresentationTest')]
for relative in files:
    p=(root/relative).resolve();assert p.is_relative_to(root);p.unlink()
print('Removed notification feature sources; legacy preferences ignored')
