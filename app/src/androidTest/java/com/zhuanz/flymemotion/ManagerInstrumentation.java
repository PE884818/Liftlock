package com.zhuanz.flymemotion;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiModeManager;
import android.app.Dialog;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.Color;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.view.View;
import com.zhuanz.flymemotion.core.MotionConfig;
import com.zhuanz.flymemotion.core.BottomGestureRegion;
import com.zhuanz.flymemotion.core.GestureSession;
import com.zhuanz.flymemotion.core.TouchDispatch;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** Manager-only checks on an emulator. Never use this runner on the user's phone. */
public final class ManagerInstrumentation extends Instrumentation {
    private final List<String> passed = new ArrayList<>();
    private ManagerActivity activity;
    private boolean channelOnly;
    @Override public void onCreate(Bundle args) { super.onCreate(args); channelOnly=args!=null&&"true".equals(args.getString("channelOnly")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        try {
            check(Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"), "emulator_guard");
            if(channelOnly)runOnMainSync(() -> {try{channelRevocationRegression();}catch(Exception e){throw new AssertionError(e);}});
            else tests();
            result.putString("summary", "PASS " + passed.size() + " manager checks");
        } catch (Throwable t) {
            code = Activity.RESULT_CANCELED; result.putString("failure", t.toString());
        } finally {
            try {
                JSONObject report = new JSONObject().put("passed", new JSONArray(passed)).put("count", passed.size())
                        .put("result", code == Activity.RESULT_OK ? "passed" : "failed")
                        .put("failure", result.getString("failure", ""))
                        .put("environment", Build.FINGERPRINT).put("systemUiHooksTested", false);
                write("manager-tests.json", report.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { result.putString("reportError", e.toString()); }
            finish(code, result);
        }
    }
    private void tests() throws Exception {
        runOnMainSync(() -> {try{channelRevocationRegression();}catch(Exception e){throw new AssertionError(e);}});
        check(!ConfigCodec.decode("{broken").enabled, "damaged_preferences_disable");
        check(!ConfigCodec.decode("{\"schema\":2}").enabled, "future_preferences_disable");
        MotionConfig initial = MotionConfig.defaults();
        MotionConfig roundtrip = ConfigCodec.decode(ConfigCodec.encode(initial));
        check(roundtrip.enabled && roundtrip.stiffness == 200 && roundtrip.expandDamping == .81f
                && roundtrip.closeDamping == .95f && !roundtrip.lateDesktopAnimation, "atomic_configuration_roundtrip");
        BottomGestureRegion region = new BottomGestureRegion(false,.25f,.6f,.8f);
        check(ConfigCodec.decode(ConfigCodec.encode(initial.withBottomRegion(region))).bottomRegion.equals(region),
                "bottom_region_configuration_roundtrip");
        JSONObject legacy = new JSONObject(ConfigCodec.encode(initial));
        for(String key : new String[]{"bottomEnabled","bottomHeight","bottomWidth","bottomCenter"})legacy.remove(key);
        check(ConfigCodec.decode(legacy.toString()).bottomRegion.equals(BottomGestureRegion.defaults()), "old_preferences_receive_default_region");
        JSONObject oldConfig=new JSONObject(ConfigCodec.encode(initial));
        oldConfig.remove("lateDesktopAnimation");oldConfig.put("notifications",true);
        check(!ConfigCodec.decode(oldConfig.toString()).lateDesktopAnimation,"legacy_notification_flag_cannot_enable_desktop_animation");
        check(!new JSONObject(ConfigCodec.encode(ConfigCodec.decode(oldConfig.toString()))).has("notifications"),"withdrawn_notification_flag_not_written");
        check(ConfigCodec.decode(ConfigCodec.encode(initial.withLateDesktopAnimation(true))).lateDesktopAnimation,"late_desktop_roundtrip");
        check(ConfigCodec.decode(ConfigCodec.encode(initial.withCornerRadius(31))).cornerRadiusDp==31,"corner_radius_roundtrip");
        JSONObject noCorner=new JSONObject(ConfigCodec.encode(initial.withCornerRadius(31)));noCorner.remove("cornerRadiusDp");
        check(ConfigCodec.decode(noCorner.toString()).cornerRadiusDp==MotionConfig.FOLLOW_SCREEN,"old_preferences_follow_screen_corner");
        check(com.zhuanz.flymemotion.ui.MotionPreset.Crisp.applyTo(initial.withCornerRadius(31)).cornerRadiusDp==31
                &&com.zhuanz.flymemotion.ui.MotionParams.Companion.getDefaults().applyTo(initial.withCornerRadius(31)).cornerRadiusDp==31,
                "presets_preserve_corner_radius");
        runOnMainSync(this::poseRegression);
        runOnMainSync(this::wallpaperRoleRegression);
        runOnMainSync(this::wallpaperCoveragePixelRegression);
        runOnMainSync(this::windowDispatchRegression);
        runOnMainSync(this::sceneDiagnosticsRegression);

        getTargetContext().getSystemService(UiModeManager.class).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
        launch();
        wholeWindowInputRegression();
        MotionApplication.configuration().save(initial); idle();
        managerUi(initial);
        statusModelRegression();

        try (var cursor = getTargetContext().getContentResolver().query(RuntimeProvider.URI, null, null, null, null)) {
            check(cursor != null && cursor.getCount() == 4, "four_private_receipts_readable_by_manager");
            check(cursor != null && cursor.getColumnIndex("sceneWorkMaxMs") >= 0, "scene_processing_duration_available_to_manager");
            check(cursor != null && cursor.getColumnIndex("lastHomeReadiness") >= 0, "home_failure_preserved_separately_from_app_scene");
        }
        boolean refused = false;
        try { getTargetContext().getContentResolver().call(RuntimeProvider.URI, "report", "com.android.systemui", new Bundle()); }
        catch (SecurityException expected) { refused = true; }
        check(refused, "manager_cannot_forge_systemui_receipt");
        refused = false;
        try { getTargetContext().getContentResolver().call(RuntimeProvider.URI, "report", "android", new Bundle()); }
        catch (SecurityException expected) { refused = true; }
        check(refused, "manager_cannot_forge_framework_receipt");
        refused = false;
        try { getTargetContext().getContentResolver().call(RuntimeProvider.URI, "report", "com.miui.miwallpaper", new Bundle()); }
        catch (SecurityException expected) { refused = true; }
        check(refused, "manager_cannot_forge_wallpaper_receipt");
        Bundle data = new Bundle(); String prefix = "com.android.systemui.";
        data.putString(prefix + "state", "loaded"); data.putString(prefix + "boot", "7"); data.putString(prefix + "elapsed", "5000");
        RuntimeStatus fresh = RuntimeStatus.read(data, "com.android.systemui", 5100, 7);
        check(fresh.fresh && fresh.state == RuntimeStatus.State.LOADED && fresh.motion == 0, "loaded_is_not_callback_proof");
        check(!fresh.currentModule(), "legacy_loaded_receipt_requires_reload");
        data.putString(prefix + "moduleVersion", TargetBuild.MODULE_VERSION);
        data.putString(prefix + "lastHomeReadiness", "target=HOME backdrop=true ready=false");
        check(RuntimeStatus.read(data, "com.android.systemui", 5100, 7).lastHomeReadiness.equals("target=HOME backdrop=true ready=false"), "wallpaper_fallback_is_not_launcher_frame_readiness");
        data.putString(prefix + "touch", "48"); data.putString(prefix + "drag", "36");
        data.putString(prefix + "scene", "原生认证界面"); data.putString(prefix + "gesture", "未应用位移");
        RuntimeStatus diagnostic = RuntimeStatus.read(data, "com.android.systemui", 5100, 7);
        check(diagnostic.currentModule() && diagnostic.touch == 48 && diagnostic.drag == 36
                && diagnostic.motion == 0 && diagnostic.scene.equals("原生认证界面"), "raw_touch_is_not_applied_motion_proof");
        check(!RuntimeStatus.read(data, "com.android.systemui", 160000, 7).currentModule(), "expired_version_is_not_current_module");
        data.putString(prefix + "moduleVersion", "0.1.1-stage1");
        check(!RuntimeStatus.read(data, "com.android.systemui", 5100, 7).currentModule(), "old_process_version_requires_reload");
        check(!RuntimeStatus.read(data, "com.android.systemui", 160000, 7).fresh, "expired_receipt_rejected");
        check(!RuntimeStatus.read(data, "com.android.systemui", 5100, 8).fresh, "previous_boot_receipt_rejected");
        check(!RuntimeStatus.read(data, "com.android.systemui", 4000, 7).fresh, "future_timestamp_rejected");
        check(!TargetBuild.compatibleRuntime("com.android.systemui", "0.2.5-stage1"), "systemui_requires_this_fix_version");
        check(!TargetBuild.compatibleRuntime("com.android.systemui", "0.3.5-stage1")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.3.5-stage2")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.3.5-stage3")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.3.5-stage4"), "old_systemui_stages_rejected");
        // stage15 changed every process (member fallbacks), so each needs the current adapter.
        boolean allCurrent = true;
        for (String pkg : new String[]{"android", "com.android.systemui", "com.miui.miwallpaper", "com.android.settings"})
            allCurrent &= TargetBuild.compatibleRuntime(pkg, TargetBuild.MODULE_VERSION)
                    && !TargetBuild.compatibleRuntime(pkg, "0.3.5-stage14") && !TargetBuild.compatibleRuntime(pkg, "0.3.5-stage10");
        check(allCurrent, "stage15_every_process_current");
        // stage16 changed only the framework (wallpaper token reconcile).
        check(!TargetBuild.compatibleRuntime("android", "0.3.5-stage15")
                && TargetBuild.compatibleRuntime("com.android.systemui", "0.3.5-stage15"), "stage16_framework_only");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper","0.3.2-stage2")&&TargetBuild.compatibleRuntime("com.miui.miwallpaper",TargetBuild.MODULE_VERSION),"lock_stream_engine_requires_new_wallpaper_adapter");
        check(TargetBuild.nativeScope("system") && TargetBuild.nativeScope("android")
                && TargetBuild.nativeScope("com.miui.miwallpaper"),"native_scope_and_framework_alias_preserved");
        check(!TargetBuild.nativeScope("com.miui.home") && !TargetBuild.nativeScope("com.tencent.mobileqq")
                && !TargetBuild.nativeScope("com.zhuanz.flymemotion") && !TargetBuild.nativeScope(null),"ordinary_apps_and_launcher_never_added_to_scope");
        check(!TargetBuild.compatibleRuntime("android", "0.2.18-stage1")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.2.19-stage1"), "native_exit_order_requires_paired_framework_and_ui");
        check(TargetBuild.compatibleProducerProtocol("0.2.18-stage1")
                && TargetBuild.compatibleProducerProtocol(TargetBuild.MODULE_VERSION), "producer_request_wire_versions_018_and_current");
        check(!TargetBuild.compatibleProducerProtocol("0.2.17-stage1")
                && !TargetBuild.compatibleProducerProtocol(null), "old_or_missing_producer_protocol_rejected");
        check(!TargetBuild.compatibleRuntime("android", "0.2.5-stage1")
                && !TargetBuild.compatibleRuntime("android", "0.2.6-stage1"), "changed_framework_requires_new_visibility_adapter");
        check(!TargetBuild.compatibleRuntime("android", "0.2.7-stage1"), "framework_activation_requires_new_version");
        check(!TargetBuild.compatibleRuntime("android", "0.2.8-stage1")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.2.8-stage1"), "sleep_handoff_requires_new_adapters");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper", "0.2.8-stage1"), "wallpaper_receipt_schema_rejects_028");
        check(!TargetBuild.compatibleRuntime("android", "0.2.9-stage1")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.2.9-stage1"), "source_reattach_requires_new_framework_and_paired_ui");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper", "0.2.9-stage1")
                && !TargetBuild.compatibleRuntime("com.android.settings", "0.2.9-stage1"), "old_wallpaper_and_settings_029_rejected");
        check(!TargetBuild.compatibleRuntime("android", "0.2.10-stage1")
                && !TargetBuild.compatibleRuntime("android", "0.2.11-stage1")
                && !TargetBuild.compatibleRuntime("com.android.systemui", "0.2.11-stage1"), "deferred_visibility_fix_requires_updated_framework_and_ui");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper", "0.2.10-stage1")
                && !TargetBuild.compatibleRuntime("com.android.settings", "0.2.10-stage1"), "old_wallpaper_and_settings_0210_rejected");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper", "0.2.7-stage1"), "wallpaper_schema_rejects_previous_version");
        check(!TargetBuild.compatibleRuntime("com.miui.miwallpaper", "0.2.5-stage1"), "wallpaper_schema_rejects_previous_baseline");
        processIdentityRegression();
        try (ZipFile apk = new ZipFile(getTargetContext().getApplicationInfo().sourceDir)) {
            String scope = new String(apk.getInputStream(apk.getEntry("META-INF/xposed/scope.list")).readAllBytes(), StandardCharsets.UTF_8);
            check(scope.strip().equals("com.android.systemui\ncom.android.settings\nsystem\ncom.miui.miwallpaper"), "authorized_native_scopes_without_application_observers");
            check(apk.getEntry("META-INF/xposed/java_init.list") != null, "modern_hook_entry_packaged");
        }
        appearanceScreens();
        MotionApplication.configuration().save(initial);
    }
    private void launch() { launch(null); }
    private void launch(String page) {
        // CLEAR_TASK: sub-pages are activities of their own and must not survive into the next launch.
        Intent intent = new Intent(getTargetContext(), ManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (page != null) intent.putExtra(ManagerActivity.EXTRA_PAGE, page);
        activity = (ManagerActivity) startActivitySync(intent);
        idle(); SystemClock.sleep(600);
    }

    /** The miuix manager, driven through Compose semantics (testTagsAsResourceId) on the emulator. */
    private void managerUi(MotionConfig initial) throws Exception {
        var ua = getUiAutomation();
        var info = ua.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ua.setServiceInfo(info);
        ConfigurationStore store = MotionApplication.configuration();
        BottomGestureRegion defaultRegion = initial.bottomRegion;

        // Home: status card; without LSPosed the four identical process rows fold into one.
        check(tag("status_card") != null, "home_status_card");
        check(tag("process_collapsed") != null && tag("process_android") == null, "home_process_rows_fold_without_lsposed");
        check(!text(tag("status_title")).contains("未适配"), "no_device_gate_headline");
        check(node("生效", true) == null && node("验收", true) == null, "home_never_claims_effect_or_acceptance");
        check(node("·", true) == null, "home_has_no_dot_separators");
        screenshot("manager-home-light.png");

        // Home: the master switch lives in the status card.
        clickTag("motion_enable");
        check(!store.get().enabled, "toggle_disables");
        Thread.sleep(300);
        check(!new ConfigurationStore(getTargetContext().createDeviceProtectedStorageContext()).get().enabled, "disable_persists_without_framework");
        check(!text(tag("status_title")).contains("未适配"), "no_device_gate_when_disabled");
        clickTag("motion_enable");
        check(store.get().enabled, "toggle_enables");
        check(tag("home_tuning") != null && tag("home_region") != null, "home_shortcut_tiles");

        // Motion tab: late desktop, presets, corners.
        clickTag("tab_1");
        check(tag("late_desktop") != null && tag("preset_tabs") != null && tag("corner_custom") != null, "motion_tab_controls");
        check(tag("late_desktop_state") == null, "late_desktop_off_has_no_status_line");
        check(node("·", true) == null, "motion_has_no_dot_separators");
        clickTag("late_desktop");
        check(store.get().lateDesktopAnimation, "late_desktop_toggle_enables_live_configuration");
        Thread.sleep(300);
        check(new ConfigurationStore(getTargetContext().createDeviceProtectedStorageContext()).get().lateDesktopAnimation,
                "late_desktop_toggle_persists_without_framework");
        screenshot("manager-motion-light.png");
        check(text(tag("late_desktop_state")).contains("还没收到"), "late_desktop_without_receipt_is_not_reported_ready");
        float blurBefore = store.get().blurStrength;
        clickText("跟手");
        MotionConfig responsive = store.get();
        check(responsive.stiffness == 300 && responsive.threshold == .58f && responsive.minimumVelocityDp == 200,
                "preset_writes_mapped_fields");
        check(responsive.blurStrength == blurBefore && responsive.lateDesktopAnimation && responsive.enabled
                && responsive.bottomRegion.equals(defaultRegion), "preset_preserves_other_choices");
        clickText("原版");
        check(store.get().stiffness == 200 && store.get().expandDamping == .81f, "preset_returns_to_flyme_defaults");

        // Tuning page: preview, slider commit, simulated swipe judged by core FlymeMotion.
        clickTag("open_tuning");
        check(tag("lock_preview") != null && tag("slider_stiffness") != null, "tuning_page_preview_and_sliders");
        BottomGestureRegion region = store.get().bottomRegion;
        Rect bounds = bounds(tag("slider_stiffness"));
        swipe(bounds.left + bounds.width() * .2f, bounds.exactCenterY(), bounds.left + bounds.width() * .66f, bounds.exactCenterY(), 300); idle();
        check(store.get().stiffness != 200, "slider_persists_new_value");
        check(store.get().bottomRegion.equals(region), "motion_slider_preserves_custom_region");
        check(store.get().lateDesktopAnimation, "motion_slider_preserves_late_desktop_choice");
        Rect preview = bounds(tag("lock_preview"));
        swipe(preview.exactCenterX(), preview.bottom - preview.height() * .1f, preview.exactCenterX(), preview.top + preview.height() * .15f, 90);
        SystemClock.sleep(250); screenshot("manager-tuning-swipe.png");
        check(text(tag("verdict")).contains("解锁"), "preview_fast_swipe_unlocks_by_core_policy");
        SystemClock.sleep(1800);
        swipe(preview.exactCenterX(), preview.bottom - preview.height() * .1f, preview.exactCenterX(), preview.bottom - preview.height() * .3f, 1500);
        idle(); check(text(tag("verdict")).contains("弹回"), "preview_short_slow_swipe_rebounds_by_core_policy");
        SystemClock.sleep(800); screenshot("manager-tuning.png");
        check(node("·", true) == null && node("→", true) == null, "tuning_has_no_dot_separators");
        back();

        // Region page: live preview, slider commit, try-swipe inside and outside.
        clickTag("open_region");
        check(tag("region_preview") != null, "region_preview_accessible");
        Rect height = bounds(tag("slider_region_height"));
        swipe(height.left + height.width() * .3f, height.exactCenterY(), height.left + height.width() * .7f, height.exactCenterY(), 300); idle();
        check(store.get().bottomRegion.height() != .15f, "region_slider_persists");
        check(store.get().stiffness != 200, "region_change_preserves_motion_parameters");
        Rect phone = bounds(tag("region_preview"));
        swipe(phone.exactCenterX(), phone.bottom - phone.height() * .04f, phone.exactCenterX(), phone.top + phone.height() * .3f, 120);
        idle(); check(text(tag("region_result")).startsWith("区域内"), "region_try_swipe_inside_reserved");
        screenshot("manager-region.png");
        swipe(phone.exactCenterX(), phone.top + phone.height() * .3f, phone.exactCenterX(), phone.top + phone.height() * .1f, 120);
        idle(); check(text(tag("region_result")).startsWith("区域外"), "region_try_swipe_outside_native");
        back();

        // More: restore; fault stop. Reload is no longer gated by device, so it is not
        // exercised here (it would really restart the emulator's SystemUI).
        clickTag("tab_2");
        check(tag("reload_com.android.systemui") != null, "reload_row_present");
        screenshot("manager-settings-light.png");
        check(node("·", true) == null, "settings_has_no_dot_separators");
        clickTag("emergency_disable"); clickTag("dialog_confirm");
        check(!store.get().enabled && store.get().lateDesktopAnimation && store.get().stiffness != 200, "emergency_disable_only_switch");
        BottomGestureRegion custom = store.get().bottomRegion;
        clickTag("restore_defaults");
        clickText("底部区域");
        clickTag("dialog_confirm");
        MotionConfig restored = store.get();
        check(!restored.enabled && restored.stiffness == 200 && restored.threshold == .5f && restored.blurStrength == 1,
                "defaults_preserve_disabled_state");
        check(restored.lateDesktopAnimation, "parameter_reset_preserves_late_desktop_choice");
        check(restored.bottomRegion.equals(custom), "parameter_reset_can_keep_region");
        clickTag("restore_defaults"); clickTag("dialog_confirm");
        check(store.get().bottomRegion.equals(BottomGestureRegion.defaults()), "region_reset_when_selected");
        clickTag("open_diagnostics");
        check(tag("copy_diagnostics") != null && waitNode("系统界面") != null, "diagnostics_page");
        screenshot("manager-diagnostics.png");
        back();
        clickTag("open_donate_more"); SystemClock.sleep(800);
        check(tag("donate_tabs") != null && tag("donate_primary") != null, "donate_page");
        check(node("**", true) == null, "donate_shows_no_real_name");
        screenshot("manager-donate.png");
        back();
        check(text(tag("author_line")).contains("稽品飞车"), "author_shown");
        screenshot("manager-more-light.png");
        clickTag("open_guide"); SystemClock.sleep(600);
        check(waitNode("勾选系统框架") != null, "first_run_guide");
        screenshot("manager-guide.png");
        back();
        clickTag("open_licenses"); clickText("HyperMusicCover v0.7.10"); SystemClock.sleep(500);
        check(waitNode("GNU AFFERO GENERAL PUBLIC LICENSE") != null, "agpl_license_text_shown");
        back(); back();

        // The Settings entry: page=lockscreen lands on home, where the master switch is.
        runOnMainSync(() -> activity.finish()); idle();
        launch(ManagerActivity.PAGE_LOCKSCREEN);
        check(visible(tag("motion_enable")), "settings_entry_opens_home");
        runOnMainSync(() -> activity.finish()); idle();
    }

    /** Pure receipt-to-text mapping, fed by constructed bundles (never injected into the provider). */
    private void statusModelRegression() {
        var model = com.zhuanz.flymemotion.ui.StatusModel.INSTANCE;
        MotionConfig on = MotionConfig.defaults();
        var link = new com.zhuanz.flymemotion.ui.Link(true, false, "", "LSPosed", List.of("com.android.systemui", "com.android.settings", "system", "com.miui.miwallpaper"));
        Bundle b = new Bundle();
        for (String pkg : new String[]{"com.android.systemui", "android"}) {
            b.putString(pkg + ".state", "loaded"); b.putString(pkg + ".boot", "7"); b.putString(pkg + ".elapsed", "5000");
            b.putString(pkg + ".moduleVersion", TargetBuild.MODULE_VERSION);
        }
        b.putString("com.android.systemui.configRevision", "0");
        var receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        var loaded = model.headline(on, link, receipts);
        check(loaded.getTitle().equals("已加载，等待上滑"), "loaded_without_motion_is_waiting");
        check(model.connection(on, link, receipts).stream().anyMatch(f -> f.getTitle().equals("系统界面读取")), "adoption_shown_with_receipt");
        b.putString("com.android.systemui.motion", "3"); b.putString("com.android.systemui.touch", "5");
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        check(model.headline(on, link, receipts).getTitle().equals("已接入"), "motion_without_wallpaper_is_triggered_only");
        b.putString("android.moduleVersion", "0.2.10-stage1"); // rejected by TargetBuild.compatibleRuntime
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        check(model.headline(on, link, receipts).getTitle().equals("需要重启手机"), "old_framework_requires_restart");
        var row = model.process("android", "系统框架", link, receipts.getFramework());
        check(row.getLevel() == com.zhuanz.flymemotion.ui.Level.Outdated && row.getNote().contains("重启手机"), "framework_row_outdated");
        b.putString("com.android.systemui.moduleVersion", "0.3.3-stage2");
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        var old = model.headline(on, link, receipts);
        check(old.getAction() == com.zhuanz.flymemotion.ui.HomeAction.ReloadSystemUi, "old_systemui_offers_reload");
        // A failure reported by an older loaded build (its removed device gate) means restart, not error.
        b.putString("com.android.systemui.state", "unsupported"); b.putString("com.android.systemui.error", "适配版本不符：X");
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        check(model.headline(on, link, receipts).getTitle().equals("需要重启手机")
                && model.process("com.android.systemui", "系统界面", link, receipts.getUi()).getLevel() == com.zhuanz.flymemotion.ui.Level.Outdated,
                "old_build_failure_is_restart_not_error");
        b.putString("com.android.systemui.moduleVersion", TargetBuild.MODULE_VERSION); b.putString("android.moduleVersion", TargetBuild.MODULE_VERSION);
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        check(model.headline(on, link, receipts).getTitle().equals("已交回原生处理"), "current_build_failure_is_native_fallback");
        b.putString("com.android.systemui.state", "loaded"); b.putString("com.android.systemui.error", "");
        // An optional part missing on this build: partial, never "error", and named in the headline.
        b.putString("com.android.systemui.compat", "面板拖动|on|\n壁纸跟随|off|Builder#setContainerLayer missing\n");
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        var partial = model.headline(on, link, receipts);
        check(partial.getTitle().equals("部分可用") && partial.getDetail().contains("壁纸跟随"), "optional_miss_is_partial_headline");
        check(model.process("com.android.systemui", "系统界面", link, receipts.getUi()).getLevel() == com.zhuanz.flymemotion.ui.Level.Partial,
                "optional_miss_is_partial_row");
        check(model.compat(receipts).size() == 2 && !model.compat(receipts).get(1).getOn(), "compat_rows_decoded");
        // The framework adapter failing on another build: SystemUI still runs, headline says partial.
        b.putString("com.android.systemui.compat", "面板拖动|on|\n");
        b.putString("android.state", "unsupported"); b.putString("android.compat", "框架场景|off|keyguardGoingAway missing\n");
        receipts = com.zhuanz.flymemotion.ui.Receipts.Companion.read(b, 5100, 7, null);
        check(model.headline(on, link, receipts).getTitle().equals("部分可用"), "framework_miss_is_partial");
        b.putString("android.state", "loaded"); b.remove("android.compat"); b.remove("com.android.systemui.compat");
        check(model.desktopState("played", "").getText().contains("画面请自己确认"), "played_is_delivery_not_acceptance");
        check(model.desktopState("requires-framework-restart", "").getText().contains("需要重启手机"), "desktop_requires_restart_shown");
        check(model.desktopState("released", "").getText().contains("请自己确认"), "released_is_handoff_not_acceptance");
        var displayContext = getTargetContext().createDisplayContext(getTargetContext()
                .getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY));
        var geometry = com.zhuanz.flymemotion.ui.preview.ScreenGeometry.Companion.read(displayContext);
        var real = getTargetContext().getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        check(Math.abs(geometry.getAspect() - (float) Math.min(real.width(), real.height()) / Math.max(real.width(), real.height())) < 1e-4f,
                "preview_aspect_follows_resolution");
        var radii = geometry.getRadii();
        boolean radiiOk = true;
        for (float r : radii) radiiOk &= r > 0 && r < geometry.getWidth() / 2;
        check(radiiOk, "preview_corner_radii_from_display");
        var corner = displayContext.getDisplay().getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT);
        check(corner == null || corner.getRadius() <= 0 || radii[0] == corner.getRadius(), "preview_uses_system_reported_radius");
        float pw = 300f, ph = pw / geometry.getAspect();
        var bounds = com.zhuanz.flymemotion.ui.preview.ScreenShapeKt.continuousRect(
                new androidx.compose.ui.geometry.Rect(0f, 0f, pw, ph), geometry.scaledRadii(pw)).getBounds();
        check(bounds.getLeft() >= -0.5f && bounds.getTop() >= -0.5f && bounds.getRight() <= pw + 0.5f && bounds.getBottom() <= ph + 0.5f
                && bounds.getRight() - bounds.getLeft() > pw - 1 && bounds.getBottom() - bounds.getTop() > ph - 1, "preview_g2_outline_fills_screen_rect");
        check(model.desktopAnimation(on, receipts) == null, "desktop_default_off_has_no_status");
        var facts = model.connection(on, link, com.zhuanz.flymemotion.ui.Receipts.Companion.getEmpty());
        check(facts.size() == 2 && facts.stream().noneMatch(f -> f.getTitle().equals("系统界面读取")), "adoption_hidden_without_receipt");
        var unscoped = new com.zhuanz.flymemotion.ui.Link(true, false, "", "LSPosed", List.of("com.android.systemui"));
        check(model.process("com.miui.miwallpaper", "壁纸", unscoped, com.zhuanz.flymemotion.ui.Receipts.Companion.getEmpty().getWallpaper())
                .getLevel() == com.zhuanz.flymemotion.ui.Level.Unselected, "missing_scope_row");
        for (String state : new String[]{"disabled", "requires-framework-restart", "framework-ready", "ready", "armed", "waiting-launcher",
                "waiting-handoff", "played", "deferred", "released", "cancelled", "unsupported", "failed", "uninitialized"}) {
            String t = model.desktopState(state, "d").getText();
            check(!t.contains("生效") && !t.contains("验收"), "desktop_state_wording_" + state);
        }
    }

    /** Light/dark/Monet and large fonts, captured for review. */
    private void appearanceScreens() throws Exception {
        var mode = getTargetContext().getSystemService(UiModeManager.class);
        mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES);
        launch(); screenshot("manager-home-dark.png");
        check((activity.getResources().getConfiguration().uiMode & 0x30) == 0x20, "dark_theme_applied");
        clickTag("tab_1"); SystemClock.sleep(800); screenshot("manager-motion-dark.png");
        clickTag("open_tuning"); SystemClock.sleep(1500); screenshot("manager-tuning-dark.png"); back(); SystemClock.sleep(800);
        clickTag("open_region"); SystemClock.sleep(1500); screenshot("manager-region-dark.png"); back(); SystemClock.sleep(800);
        clickTag("tab_2"); SystemClock.sleep(800); screenshot("manager-settings-dark.png");
        var vm = activity.viewModel();
        var ui = vm.getUi().getValue();
        runOnMainSync(() -> vm.updateUi(new com.zhuanz.flymemotion.ui.UiSettings("MonetSystem", true, ui.getBlur())));
        idle(); SystemClock.sleep(1000); clickTag("tab_0"); SystemClock.sleep(1000); screenshot("manager-home-monet-floating.png");
        runOnMainSync(() -> vm.updateUi(ui)); idle();
        runOnMainSync(() -> activity.finish()); idle();
        mode.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO);
        try {
            for (String scale : new String[]{"1.3", "2.0"}) {
                shell("settings put system font_scale " + scale);
                launch(); screenshot("manager-home-font" + scale + ".png");
                clickTag("tab_1"); SystemClock.sleep(800); clickTag("open_tuning"); SystemClock.sleep(1500); screenshot("manager-tuning-font" + scale + ".png"); back();
                runOnMainSync(() -> activity.finish()); idle();
            }
        } finally { shell("settings put system font_scale 1.0"); }
        passed.add("large_font_screens_captured");
    }

    private void shell(String command) throws Exception {
        try (var fd = getUiAutomation().executeShellCommand(command)) {
            new java.io.FileInputStream(fd.getFileDescriptor()).readAllBytes();
        }
        SystemClock.sleep(300);
    }
    private AccessibilityNodeInfo tag(String tag) {
        long deadline = SystemClock.uptimeMillis() + 3000;
        while (true) {
            getUiAutomation().clearCache();
            AccessibilityNodeInfo found = null;
            for (var root : roots()) { found = byTag(root, tag); if (found != null) break; }
            if (found != null || SystemClock.uptimeMillis() > deadline) return found;
            SystemClock.sleep(100);
        }
    }
    private AccessibilityNodeInfo byTag(AccessibilityNodeInfo n, String tag) {
        if (n == null) return null;
        AccessibilityNodeInfo hidden = null;
        if (tag.equals(n.getViewIdResourceName())) { if (n.isVisibleToUser()) return n; hidden = n; }
        for (int i = 0; i < n.getChildCount(); i++) {
            var found = byTag(n.getChild(i), tag);
            if (found != null && found.isVisibleToUser()) return found;
            if (found != null && hidden == null) hidden = found;
        }
        return hidden;
    }
    private String dump(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder out = new StringBuilder();
        if (n.getText() != null || n.getContentDescription() != null || n.getViewIdResourceName() != null)
            out.append("[").append(n.getViewIdResourceName()).append("|").append(n.getText()).append("|").append(n.getContentDescription()).append("]");
        for (int i = 0; i < n.getChildCount(); i++) out.append(dump(n.getChild(i)));
        return out.toString();
    }
    /** Every interactive window's root, topmost first: miuix WindowDialog is a window of its own. */
    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        var windows = new ArrayList<>(getUiAutomation().getWindows());
        windows.sort((a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        for (var w : windows) { var r = w.getRoot(); if (r != null) roots.add(r); }
        if (roots.isEmpty()) roots.add(getUiAutomation().getRootInActiveWindow());
        return roots;
    }
    private boolean visible(AccessibilityNodeInfo n) { return n != null && n.isVisibleToUser(); }
    private String text(AccessibilityNodeInfo n) {
        if (n == null) return "";
        StringBuilder out = new StringBuilder(n.getText() == null ? "" : n.getText().toString());
        for (int i = 0; i < n.getChildCount(); i++) out.append(text(n.getChild(i)));
        return out.toString();
    }
    private Rect bounds(AccessibilityNodeInfo n) {
        if (n == null) throw new AssertionError("Missing node");
        Rect r = new Rect(); n.getBoundsInScreen(r); return r;
    }
    private void clickTag(String tag) {
        var n = tag(tag);
        // Lazy lists compose only what is on screen; scroll the page until the control appears.
        for (int i = 0; i < 6 && !visible(n); i++) {
            var size = new android.graphics.Point();
            activity.getWindowManager().getDefaultDisplay().getRealSize(size);
            swipe(size.x / 2f, size.y * .7f, size.x / 2f, size.y * .35f, 250);
            idle(); n = tag(tag);
        }
        if (n == null) throw new AssertionError("Missing control: " + tag);
        activate(n);
    }
    private void clickText(String text) {
        var n = node(text, false);
        if (n == null) n = node(text, true); // merged preference rows carry title and summary together
        if (n == null) throw new AssertionError("Missing text: " + text);
        activate(n);
    }
    /** Semantics click when the node or an ancestor exposes one; otherwise a real tap at its centre (emulator only). */
    private void activate(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo target = n;
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect r = bounds(n); tap(r.exactCenterX(), r.exactCenterY());
        }
        idle(); SystemClock.sleep(350);
    }
    private void back() {
        getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
        idle(); SystemClock.sleep(600);
    }
    private void swipe(float x0, float y0, float x1, float y1, long durationMs) {
        long down = SystemClock.uptimeMillis();
        injectTouch(down, MotionEvent.ACTION_DOWN, x0, y0);
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            SystemClock.sleep(durationMs / steps);
            injectTouch(down, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps);
        }
        injectTouch(down, MotionEvent.ACTION_UP, x1, y1);
    }
    private void wallpaperRoleRegression() {
        FrameLayout window = new FrameLayout(getTargetContext());
        RecordingLayout depth = new RecordingLayout(getTargetContext());
        View foreground = new View(getTargetContext()), clock = new View(getTargetContext());
        depth.addView(foreground, new FrameLayout.LayoutParams(400, 1000));
        window.addView(depth, new FrameLayout.LayoutParams(400, 1000));
        window.addView(clock, new FrameLayout.LayoutParams(400, 1000));
        window.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        window.layout(0, 0, 400, 1000);
        depth.setElevation(.2f); clock.setElevation(.1f); depth.setAlpha(.8f); depth.setTranslationY(7);
        Rect clip = new Rect(0, 0, 400, 950); depth.setClipBounds(clip);
        var content = new LockscreenPose(); var wallpaper = new WallpaperLayerPose();
        var frame = com.zhuanz.flymemotion.core.MotionFrame.sample(500, -400, 1000, true, 1);
        content.apply(frame.contentY(), window);
        int roles = wallpaper.apply(frame, content, depth, foreground);
        check(roles == 1, "nested_depth_not_faded_twice");
        check(Math.abs(window.getTranslationY() + depth.getTranslationY() - 7) < .001f,
                "foreground_screen_position_stays_while_clock_moves");
        check(Math.abs(depth.getAlpha() - .8f * frame.wallpaperAlpha()) < .001f && clock.getAlpha() == 1,
                "depth_fades_with_wallpaper_not_controls");
        check(depth.getElevation() == .2f && clock.getElevation() == .1f && depth.getParent() == window,
                "depth_priority_and_parent_retained");
        check(depth.getClipBounds().bottom <= 500 && !window.getClipChildren(), "stationary_depth_uses_wallpaper_viewport");
        depth.writes.clear();depth.alphaWrites.clear();depth.clipWrites.clear();
        for(int i=0;i<120;i++){content.apply(frame.contentY(),window);wallpaper.apply(frame,content,depth,foreground);}
        check(depth.writes.isEmpty() && depth.alphaWrites.isEmpty() && depth.clipWrites.isEmpty(),
                "stationary_wallpaper_role_never_restores_alpha_crop_or_position_between_frames");
        wallpaper.restore(); content.restore();
        check(depth.getTranslationY() == 7 && depth.getAlpha() == .8f && depth.getClipBounds().equals(clip)
                && window.getClipChildren(), "wallpaper_cancel_restores_native_properties");
        content.apply(-200, window); wallpaper.apply(frame, content, depth);
        depth.setAlpha(.6f); depth.setTranslationY(19); depth.setClipBounds(null);
        wallpaper.restore(); content.restore();
        check(depth.getAlpha() == .6f && depth.getTranslationY() == 19 && depth.getClipBounds() == null,
                "native_depth_changes_survive_handoff");
    }
    private void channelRevocationRegression() throws Exception {
        Class<?> reporterType=Class.forName("com.zhuanz.flymemotion.hook.RuntimeReporter");
        var reporterCtor=reporterType.getDeclaredConstructor(String.class);reporterCtor.setAccessible(true);
        Object reporter=reporterCtor.newInstance("test-owned-channel");
        Class<?> type=Class.forName("com.zhuanz.flymemotion.hook.NativeSceneChannel");
        var ctor=type.getDeclaredConstructor(reporterType,Runnable.class);ctor.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger changed=new java.util.concurrent.atomic.AtomicInteger();
        Object channel=ctor.newInstance(reporter,(Runnable)changed::incrementAndGet);
        var expected=type.getDeclaredField("expectedToken");expected.setAccessible(true);expected.setLong(channel,42);
        var requested=type.getDeclaredField("requested");requested.setAccessible(true);requested.setBoolean(channel,true);
        var ready=type.getDeclaredField("nativeReady");ready.setAccessible(true);ready.setBoolean(channel,true);
        var token=type.getDeclaredField("token");token.setAccessible(true);token.setLong(channel,42);
        var orderField=type.getDeclaredField("order");orderField.setAccessible(true);
        ((com.zhuanz.flymemotion.core.SceneStatusOrder)orderField.get(channel)).begin(42);
        var receive=type.getDeclaredMethod("receive",Intent.class,android.view.SurfaceControl.class);receive.setAccessible(true);
        Class<?> runtimeBuild=Class.forName("com.zhuanz.flymemotion.TargetBuild",false,getTargetContext().getClassLoader());
        String version=(String)runtimeBuild.getField("MODULE_VERSION").get(null);
        try {
            receive.invoke(channel,new Intent().putExtra("token",42L).putExtra("restored",true)
                    .putExtra("frameworkVersion",version).putExtra("statusSequence",1L).putExtra("status","owned test: source retired"),null);
            check(!requested.getBoolean(channel)&&expected.getLong(channel)==-1&&!ready.getBoolean(channel),
                    "server_restored_reply_releases_request_and_cannot_leave_a_dead_scene_active");
            var consume=type.getDeclaredMethod("consumeRevocation");consume.setAccessible(true);
            check((boolean)consume.invoke(channel)&&!(boolean)consume.invoke(channel)&&changed.get()==1,
                    "scene_revocation_is_delivered_exactly_once_to_the_visual_owner");
            expected.setLong(channel,44);requested.setBoolean(channel,true);ready.setBoolean(channel,true);
            receive.invoke(channel,new Intent().putExtra("token",42L).putExtra("restored",true)
                    .putExtra("frameworkVersion",version),null);
            check(requested.getBoolean(channel)&&expected.getLong(channel)==44&&ready.getBoolean(channel),
                    "stale_restore_receipt_cannot_revoke_a_new_scene");
            ((com.zhuanz.flymemotion.core.SceneStatusOrder)orderField.get(channel)).begin(44);
            Intent current=new Intent().putExtra("token",44L).putExtra("statusSequence",20L)
                    .putExtra("frameworkVersion",version).putExtra("bindingEpoch",3L).putExtra("windowIdentity",91L)
                    .putExtra("drawSequence",7L).putExtra("framePresented",true).putExtra("ready",true).putExtra("phase","REVEALABLE");
            receive.invoke(channel,current,null);
            check(ready.getBoolean(channel),"channel_fresh_current_presented_receipt_allows_reveal");
            receive.invoke(channel,new Intent(current).putExtra("statusSequence",19L).putExtra("restored",true),null);
            check(expected.getLong(channel)==44 && ready.getBoolean(channel),"channel_out_of_order_restore_cannot_delete_current_frame");
            receive.invoke(channel,new Intent(current).putExtra("statusSequence",21L).putExtra("framePresented",false),null);
            check(!ready.getBoolean(channel),"channel_missing_presentation_is_never_ready");
            receive.invoke(channel,current,null);
            check(!ready.getBoolean(channel),"channel_late_ready_cannot_override_newer_invalid_frame");
            receive.invoke(channel,new Intent(current).putExtra("statusSequence",22L).putExtra("phase","NATIVE_OWNED"),null);
            var nativeOwned=type.getDeclaredMethod("nativeOwned");nativeOwned.setAccessible(true);
            check((boolean)nativeOwned.invoke(channel),"channel_native_ownership_needs_current_ready_proof");
            Intent takeover=new Intent(current).putExtra("statusSequence",23L).putExtra("phase","HANDOFF")
                    .putExtra("framePresented",false).putExtra("ready",false).putExtra("nativeTakeover",true);
            receive.invoke(channel,takeover,null);
            check((boolean)nativeOwned.invoke(channel)&&!ready.getBoolean(channel),"native_retirement_receipt_does_not_forge_a_preview_frame");
            receive.invoke(channel,new Intent(current).putExtra("statusSequence",22L).putExtra("nativeTakeover",false),null);
            check((boolean)nativeOwned.invoke(channel)&&!ready.getBoolean(channel),"late_preview_cannot_reinstate_overlay_after_native_takeover");

        } finally {
            for(Object owner:new Object[]{channel,reporter}) {
                var worker=owner.getClass().getDeclaredField("worker");worker.setAccessible(true);
                ((android.os.Handler)worker.get(owner)).getLooper().quitSafely();
            }
        }
    }

    /** Own emulator dialog only: exercise the production mirror with a hidden producer. */

    private void wallpaperCoveragePixelRegression() {
        FrameLayout viewport = new FrameLayout(getTargetContext()), window = new FrameLayout(getTargetContext());
        View foreground = new View(getTargetContext());
        viewport.setBackgroundColor(Color.BLUE); foreground.setBackgroundColor(Color.RED);
        window.addView(foreground, new FrameLayout.LayoutParams(400, 1000));
        viewport.addView(window, new FrameLayout.LayoutParams(400, 1000));
        viewport.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, 400, 1000); window.setTranslationY(20);
        var content = new LockscreenPose(); var wallpaper = new WallpaperLayerPose();
        var frame = com.zhuanz.flymemotion.core.MotionFrame.sample(1100, 0, 1000, true, 1)
                .withPresentationProgress(.5f, 1);
        content.apply(frame.contentY(), window);
        frame = frame.coveringContent(window.getTranslationY());
        wallpaper.apply(frame, content, foreground);
        Bitmap pixels = Bitmap.createBitmap(400, 1000, Bitmap.Config.ARGB_8888);
        viewport.draw(new android.graphics.Canvas(pixels));
        check(Color.red(pixels.getPixel(200, 605)) > 100, "spring_and_native_offset_do_not_cut_wallpaper_role_coverage_short");
        check(pixels.getPixel(200, 630) == Color.BLUE, "extended_coverage_leaves_revealed_region_clear");
        wallpaper.restore(); content.restore();

        var closed = com.zhuanz.flymemotion.core.MotionFrame.sample(0, 0, 1000, true, 1);
        content.apply(closed.contentY(), window); wallpaper.apply(closed, content, foreground);
        // Exact OEM order: showing(false), still KEYGUARD, native Folme writes foreground alpha=1.
        foreground.setAlpha(1);
        check(!com.zhuanz.flymemotion.core.VisualHandoff.nativeHidden(false, 1, true), "logical_hide_cannot_retire_depth_pose");
        wallpaper.restore(); content.restore(); content.apply(closed.contentY(), window);
        wallpaper.apply(closed, content, foreground); viewport.draw(new android.graphics.Canvas(pixels));
        check(pixels.getPixel(200, 800) == Color.BLUE, "native_alpha_rewrite_stays_hidden_through_next_draw");
        foreground.setVisibility(View.INVISIBLE);
        check(com.zhuanz.flymemotion.core.VisualHandoff.nativeHidden(false, 0, false), "actual_native_hidden_phase_allows_release");
        wallpaper.restore(); content.restore(); viewport.draw(new android.graphics.Canvas(pixels));
        check(pixels.getPixel(200, 800) == Color.BLUE, "restoring_hidden_foreground_does_not_flash_its_pixels");
        check(foreground.getAlpha() == 1 && window.getTranslationY() == 20, "native_hidden_release_restores_original_values");
        pixels.recycle();
    }
    private void poseRegression() {
        RecordingLayout stable = new RecordingLayout(getTargetContext());
        stable.setTranslationY(20);
        LockscreenPose stablePose = new LockscreenPose(); stablePose.apply(-500, stable);
        stable.writes.clear();
        for (int i=0;i<120;i++) stablePose.apply(-500, stable);
        check(stable.writes.isEmpty() && stable.getTranslationY()==-480,
                "stationary_hold_has_no_intermediate_native_baseline_writes");
        stablePose.apply(-600, stable);
        check(stable.writes.size()==1 && stable.writes.get(0)==-580,
                "progress_change_writes_only_the_next_presented_pose");
        stable.setTranslationY(77);stable.writes.clear();stablePose.apply(-600,stable);
        check(stable.writes.size()==1 && stable.getTranslationY()==-523,
                "new_native_baseline_is_adopted_without_replaying_the_old_pose");
        stablePose.restore();
        check(stable.getTranslationY()==77, "stable_pose_release_preserves_latest_native_baseline");
        FrameLayout root = new FrameLayout(getTargetContext());
        View clock = new View(getTargetContext()), bottom = new View(getTargetContext());
        root.addView(clock); root.addView(bottom);
        FrameLayout notifications = new FrameLayout(getTargetContext());
        Rect nativeClip = new Rect(0, 0, 400, 900); root.setClipBounds(nativeClip);
        root.setTranslationY(20); notifications.setTranslationY(5);
        LockscreenPose pose = new LockscreenPose();
        check(pose.apply(-200, root, clock, notifications, bottom, root), "whole_roots_accept_offset");
        check(root.getTranslationY() == -180 && clock.getTranslationY() == 0 && bottom.getTranslationY() == 0,
                "clock_and_controls_inherit_root_once");
        check(notifications.getTranslationY() == -195, "separate_notifications_follow_same_offset");
        check(nativeClip.equals(root.getClipBounds()), "drag_preserves_native_clip_rectangle");
        pose.apply(-200, root, clock, notifications, bottom);
        check(root.getTranslationY() == -180 && notifications.getTranslationY() == -195, "repeat_callbacks_do_not_accumulate_offset");
        root.setTranslationY(77); pose.restore();
        check(root.getTranslationY() == 77 && notifications.getTranslationY() == 5, "restore_keeps_intervening_native_motion");
        pose.apply(-100, root, notifications); pose.restore();
        check(root.getTranslationY() == 77 && notifications.getTranslationY() == 5, "cancel_returns_to_latest_native_baseline");
        root.setVisibility(View.GONE);
        check(!pose.apply(-100, root) && root.getTranslationY() == 77, "hidden_native_root_is_not_modified");
    }
    private static final class RecordingLayout extends FrameLayout {
        final List<Float> writes = new ArrayList<>();
        final List<Float> alphaWrites = new ArrayList<>();
        final List<Rect> clipWrites = new ArrayList<>();
        RecordingLayout(Context context) { super(context); }
        @Override public void setTranslationY(float y) { if(writes != null)writes.add(y);super.setTranslationY(y); }
        @Override public void setAlpha(float alpha) { if(alphaWrites != null)alphaWrites.add(alpha);super.setAlpha(alpha); }
        @Override public void setClipBounds(Rect clip) { if(clipWrites != null)clipWrites.add(clip==null?null:new Rect(clip));super.setClipBounds(clip); }
    }
    private void processIdentityRegression() throws Exception {
        Context c = getTargetContext();
        var helper = Class.forName("com.zhuanz.flymemotion.hook.SceneReceivers");
        var uid = helper.getDeclaredMethod("uid", Context.class, String.class); uid.setAccessible(true);
        var accepts = helper.getDeclaredMethod("fromPackage", Context.class, BroadcastReceiver.class, String.class);
        accepts.setAccessible(true);
        int actualUid = c.getPackageManager().getPackageUid(c.getPackageName(), 0);
        check(actualUid != android.os.Process.SYSTEM_UID && (int) uid.invoke(null, c, c.getPackageName()) == actualUid,
                "scoped_identity_resolves_actual_package_uid_without_system_uid_assumption");
        String action = c.getPackageName() + ".TEST_IDENTITY";
        CountDownLatch arrived = new CountDownLatch(1); boolean[] result = new boolean[3];
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    result[0] = (boolean) accepts.invoke(null, context, this, c.getPackageName());
                    result[1] = !(boolean) accepts.invoke(null, context, this, "android");
                    result[2] = !(boolean) accepts.invoke(null, context, this, c.getPackageName() + ".missing");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                finally { arrived.countDown(); }
            }
        };
        c.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_EXPORTED);
        try {
            c.sendBroadcast(new Intent(action).setPackage(c.getPackageName()), null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
            check(arrived.await(5, TimeUnit.SECONDS) && result[0], "real_broadcast_identity_accepts_non_system_package_uid");
            check(result[1] && result[2], "real_broadcast_identity_rejects_other_or_missing_package");
        } finally { c.unregisterReceiver(receiver); }
    }
    private void sceneDiagnosticsRegression() {
        try {
            FrameLayout root = new FrameLayout(getTargetContext()); root.setClipChildren(true);
            android.widget.TextView clock = new android.widget.TextView(getTargetContext());
            clock.setText("private-content-must-not-be-reported");
            root.addView(clock); clock.setTranslationY(42);
            var method = Class.forName("com.zhuanz.flymemotion.hook.NativeSceneDiagnostics")
                    .getDeclaredMethod("layers", View[].class); method.setAccessible(true);
            String snapshot = (String) method.invoke(null, (Object) new View[]{root, clock});
            check(snapshot.contains("TextView#no-id") && snapshot.contains("y=42.0")
                    && snapshot.contains("{clip}"), "scene_diagnostics_include_geometry_and_parent_clipping");
            check(!snapshot.contains("private-content"), "scene_diagnostics_exclude_view_text");
            View[] many = new View[120];
            for (int i = 0; i < many.length; i++) { many[i] = new View(getTargetContext()); root.addView(many[i]); }
            check(((String) method.invoke(null, (Object) many)).length() <= 4096, "scene_diagnostics_are_bounded");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void wholeWindowInputRegression() throws Exception {
        Dialog[] dialog = new Dialog[1]; FrameLayout[] content = new FrameLayout[1]; View[] marker = new View[1];
        List<float[]> touches = new ArrayList<>(); LockscreenPose pose = new LockscreenPose();
        runOnMainSync(() -> {
            Dialog d = new Dialog(activity); FrameLayout panel = new FrameLayout(activity); panel.setBackgroundColor(Color.BLACK);
            View blue = new View(activity); blue.setBackgroundColor(Color.BLUE);
            FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(300, 100); size.topMargin = 300;
            panel.addView(blue, size);
            panel.setOnTouchListener((v, e) -> { touches.add(new float[]{e.getActionMasked(), e.getY(), e.getRawY()}); return true; });
            d.setContentView(panel); d.show();
            d.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            dialog[0] = d; content[0] = panel; marker[0] = blue;
        }); idle();
        View decor = dialog[0].getWindow().getDecorView();
        try {
            check(!(decor.getParent() instanceof View), "whole_window_has_viewroot_parent");
            int[] markerAt = new int[2], panelAt = new int[2];
            runOnMainSync(() -> { marker[0].getLocationOnScreen(markerAt); content[0].getLocationOnScreen(panelAt); });
            // The dialog fades in over the manager; wait for its window animation to settle.
            Bitmap before = getUiAutomation().takeScreenshot();
            for (long until = SystemClock.uptimeMillis() + 2000; before != null && SystemClock.uptimeMillis() < until
                    && !near(before.getPixel(markerAt[0] + 100, markerAt[1] + 50), Color.BLUE); ) {
                before.recycle(); SystemClock.sleep(100); before = getUiAutomation().takeScreenshot();
            }
            if (before != null) try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), "whole-window-baseline.png"))) {
                before.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            check(before != null && near(before.getPixel(markerAt[0] + 100, markerAt[1] + 50), Color.BLUE),
                    "whole_window_baseline_pixels_visible"); if (before != null) before.recycle();
            float x = panelAt[0] + content[0].getWidth() / 2f, y = panelAt[1] + content[0].getHeight() * .65f;
            long downTime = SystemClock.uptimeMillis();
            injectTouch(downTime, MotionEvent.ACTION_DOWN, x, y);
            runOnMainSync(() -> pose.apply(-160, decor)); idle();
            Bitmap after = getUiAutomation().takeScreenshot();
            check(after != null && near(after.getPixel(markerAt[0] + 100, markerAt[1] - 110), Color.BLUE)
                            && near(after.getPixel(markerAt[0] + 100, markerAt[1] + 50), Color.BLACK),
                    "whole_window_rendernode_moves_visible_pixels"); if (after != null) after.recycle();
            injectTouch(downTime, MotionEvent.ACTION_MOVE, x, y - 80);
            injectTouch(downTime, MotionEvent.ACTION_UP, x, y - 80); idle();
            check(touches.size() == 3 && touches.get(0)[0] == MotionEvent.ACTION_DOWN
                            && touches.get(1)[0] == MotionEvent.ACTION_MOVE && touches.get(2)[0] == MotionEvent.ACTION_UP,
                    "whole_window_motion_retains_native_receiver");
            check(Math.abs((touches.get(1)[1] - touches.get(0)[1]) + 80) < .01f
                            && Math.abs((touches.get(1)[2] - touches.get(0)[2]) + 80) < .01f,
                    "whole_window_translation_does_not_feed_back_into_touch_coordinates");
        } finally { runOnMainSync(() -> { pose.restore(); dialog[0].dismiss(); }); idle(); }
    }


    private void injectTouch(long downTime, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { if (!getUiAutomation().injectInputEvent(e, true)) throw new AssertionError("Emulator touch rejected"); }
        finally { e.recycle(); }
    }
    private void idle() { waitForIdleSync(); SystemClock.sleep(200); }
    private void windowDispatchRegression() {
        class Window extends FrameLayout {
            final TouchDispatch dispatch = new TouchDispatch(); final GestureSession session = new GestureSession();
            int childCancels, terminalInputs, releases; boolean intercept; boolean cancelWasChild;
            Window() { super(getTargetContext()); }
            @Override public boolean dispatchTouchEvent(MotionEvent e) {
                var frame = dispatch.enter(e.getActionMasked(), e.getDownTime(), e.getEventTime());
                if (frame.outermost() && frame.action() == MotionEvent.ACTION_DOWN) session.beginInput();
                long token = session.generation();
                try { return super.dispatchTouchEvent(e); }
                finally {
                    if (frame.terminal()) { terminalInputs++; session.endInput(token); session.finish(token); }
                    dispatch.exit(frame);
                }
            }
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                return intercept && e.getActionMasked() == MotionEvent.ACTION_MOVE;
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (dispatch.matches(MotionEvent.ACTION_UP, e.getDownTime()) && session.touching()) releases++;
                return true;
            }
        }
        Window window = new Window();
        View child = new View(getTargetContext());
        child.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                window.childCancels++;
                window.cancelWasChild = "child-cancel".equals(window.dispatch.origin(e.getActionMasked(), e.getDownTime()));
                if (window.dispatch.matches(MotionEvent.ACTION_CANCEL, e.getDownTime()))
                    window.session.endInput(window.session.generation());
                window.session.finish(window.session.generation());
            }
            if (window.dispatch.matches(MotionEvent.ACTION_UP, e.getDownTime()) && window.session.touching()) window.releases++;
            return true;
        });
        window.addView(child, new FrameLayout.LayoutParams(300, 300));
        window.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)); window.layout(0, 0, 300, 300);
        java.util.function.BiConsumer<Integer, Long> send = (action, time) -> {
            MotionEvent e = MotionEvent.obtain(time, time + 10, action, 100, 100, 0);
            try { window.dispatchTouchEvent(e); } finally { e.recycle(); }
        };
        send.accept(MotionEvent.ACTION_DOWN, 100L); long old = window.session.generation();
        // A new DOWN clears the previous child target using CANCEL on this very same event.
        send.accept(MotionEvent.ACTION_DOWN, 200L);
        check(window.childCancels == 1 && window.cancelWasChild, "framework_new_down_cancels_old_child");
        check(window.session.touching() && window.terminalInputs == 0 && !window.session.finish(old),
                "old_child_cancel_and_animator_end_keep_new_finger");
        send.accept(MotionEvent.ACTION_UP, 200L);
        check(window.releases == 1 && !window.session.touching(), "actual_up_releases_once_after_child_cancel");
        send.accept(MotionEvent.ACTION_DOWN, 300L); window.intercept = true;
        send.accept(MotionEvent.ACTION_MOVE, 300L);
        check(window.childCancels == 2 && window.cancelWasChild && window.session.touching(),
                "framework_interception_cancel_keeps_global_finger");
        send.accept(MotionEvent.ACTION_UP, 300L);
        check(window.releases == 2 && window.terminalInputs == 2, "actual_up_after_interception_is_current_release");
        window.intercept = false; send.accept(MotionEvent.ACTION_DOWN, 400L);
        send.accept(MotionEvent.ACTION_CANCEL, 400L);
        check(!window.session.touching() && window.terminalInputs == 3 && window.releases == 2,
                "actual_window_cancel_is_native_terminal_without_release");
    }
    /** SurfaceFlinger may shift a channel by one level when it composites over the Compose manager. */
    private static boolean near(int pixel, int color) {
        return Math.abs(Color.red(pixel) - Color.red(color)) <= 2 && Math.abs(Color.green(pixel) - Color.green(color)) <= 2
                && Math.abs(Color.blue(pixel) - Color.blue(color)) <= 2 && Color.alpha(pixel) == Color.alpha(color);
    }
    private void check(boolean value, String name) { if (!value) throw new AssertionError(name); passed.add(name); }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("Screenshot unavailable");
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }
    private void write(String name, byte[] content) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null), name))) { out.write(content); }
    }
    /** Text lookup that waits for a page opened in another activity to finish its transition. */
    private AccessibilityNodeInfo waitNode(String text) {
        long deadline = SystemClock.uptimeMillis() + 4000;
        AccessibilityNodeInfo n;
        while ((n = node(text, true)) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(150);
        return n;
    }
    private AccessibilityNodeInfo node(String text, boolean contains) {
        getUiAutomation().clearCache(); // Compose updates can outrun the client-side node cache.
        for (var root : roots()) { var found = find(root, text, contains); if (found != null) return found; }
        return null;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String text, boolean contains) {
        if (n == null) return null;
        String value = String.valueOf(n.getText());
        String description = String.valueOf(n.getContentDescription());
        if (contains ? description.contains(text) : description.equals(text)) return n;
        if (contains ? value.contains(text) : value.equals(text)) return n;
        for (int i = 0; i < n.getChildCount(); i++) { var found = find(n.getChild(i), text, contains); if (found != null) return found; }
        return null;
    }
    private void click(String text) {
        var n = node(text, false);
        if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Missing dialog action: " + text);
        idle();
    }
    private void tap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN); sendPointerSync(e); e.recycle(); SystemClock.sleep(30);
        }
    }
}
