/*
 * Copyright (C) 2025-2026 The AviumUI Project
 * Copyright (C) 2026 The ArkUI Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * ArkUI adapts AviumUseful URL and express matching to native clipboard actions.
 * Host validation, sensitive-content filtering and explicit user actions are ArkUI changes.
 */
package com.android.systemui.arkui.intelligence;

import static com.android.settingslib.arkui.IntelligenceSettings.*;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.systemui.res.R;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runs on the clipboard background executor; never opens an app or sends data itself. */
public final class ProactiveClipboardActions {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"<>《》]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAO_TOKEN = Pattern.compile("[￥¥][a-zA-Z0-9]{8,15}[￥¥]");
    private static final Pattern EXPRESS = Pattern.compile(
            "^(?:SF\\d{13}|YT\\d{13}|JT\\d{13}|YD\\d{13}|ST\\d{13}|"
                    + "DP\\d{13}|JD[V\\d]\\d{12,13}|7\\d{13}|77\\d{11}|"
                    + "10\\d{11}|E[A-Z]\\d{9}CN|66\\d{11})$");
    private static final String[][] HOSTS = {
            {"taobao.com", "com.taobao.taobao"}, {"tmall.com", "com.taobao.taobao"},
            {"tb.cn", "com.taobao.taobao"}, {"pan.baidu.com", "com.baidu.netdisk"},
            {"123pan.com", "com.mfcloudcalculate.networkdisk"},
            {"123865.com", "com.mfcloudcalculate.networkdisk"},
            {"bilibili.com", "tv.danmaku.bili"}, {"b23.tv", "tv.danmaku.bili"},
            {"douyin.com", "com.ss.android.ugc.aweme"},
            {"yangkeduo.com", "com.xunmeng.pinduoduo"}, {"jd.com", "com.jingdong.app.mall"},
            {"3.cn", "com.jingdong.app.mall"}, {"xiaohongshu.com", "com.xingin.xhs"},
            {"xhslink.com", "com.xingin.xhs"}, {"weibo.com", "com.sina.weibo"},
            {"weibo.cn", "com.sina.weibo"}, {"zhihu.com", "com.zhihu.android"},
            {"music.163.com", "com.netease.cloudmusic"}, {"y.qq.com", "com.tencent.qqmusic"},
            {"goofish.com", "com.taobao.idlefish"}, {"idlefish.com", "com.taobao.idlefish"},
            {"alipay.com", "com.eg.android.AlipayGphone"},
            {"dingtalk.com", "com.alibaba.android.rimet"}, {"pan.quark.cn", "com.quark.browser"},
            {"feishu.cn", "com.ss.android.lark"}, {"larksuite.com", "com.ss.android.lark"}
    };
    private ProactiveClipboardActions() { }

    public static RemoteAction resolve(Context context, int user, String text, String source) {
        final var resolver = context.getContentResolver();
        if (!ENABLED.enabled(resolver, user) || TextUtils.isEmpty(text) || text.length() > 8192) return null;
        final Context userContext = context.createContextAsUser(UserHandle.of(user), 0);
        if (APP_LINKS.enabled(resolver, user) && TAO_TOKEN.matcher(text).find()) {
            final Intent launch = userContext.getPackageManager().getLaunchIntentForPackage("com.taobao.taobao");
            if (launch != null && !"com.taobao.taobao".equals(source)) {
                return action(userContext, launch, context.getString(R.string.arkui_suggest_open_app,
                        appLabel(userContext, "com.taobao.taobao")), user);
            }
        }
        final Matcher urls = URL.matcher(text);
        if (urls.find()) {
            String value = urls.group().replaceAll("[。！？，、；）)]+$", "");
            final Uri uri = Uri.parse(value);
            final String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return null;
            final Intent open = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            if (APP_LINKS.enabled(resolver, user)) {
                for (String[] rule : HOSTS) {
                    final String name = host.toLowerCase(Locale.ROOT);
                    if (!(name.equals(rule[0]) || name.endsWith("." + rule[0])) || rule[1].equals(source)) continue;
                    final Intent target = new Intent(open).setPackage(rule[1]);
                    if (canOpen(userContext, target)) return action(userContext, target,
                            context.getString(R.string.arkui_suggest_open_app, appLabel(userContext, rule[1])), user);
                    // Some share tokens/URLs are read from the existing clipboard by the app.
                    final Intent launch = userContext.getPackageManager().getLaunchIntentForPackage(rule[1]);
                    if (launch != null) return action(userContext, launch,
                            context.getString(R.string.arkui_suggest_open_app, appLabel(userContext, rule[1])), user);
                }
            }
            if (WEB_LINKS.enabled(resolver, user)) {
                // Constrain the fallback to a browser so app-link preference remains independent.
                final Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                        .addCategory(Intent.CATEGORY_BROWSABLE);
                final var holders = context.getSystemService(RoleManager.class)
                        .getRoleHoldersAsUser(RoleManager.ROLE_BROWSER, UserHandle.of(user));
                String browserPackage = holders.isEmpty() ? null : holders.get(0);
                if (browserPackage == null) {
                    for (ResolveInfo candidate : userContext.getPackageManager().queryIntentActivities(
                            browser, PackageManager.MATCH_DEFAULT_ONLY)) {
                        if (candidate.handleAllWebDataURI) { browserPackage = candidate.activityInfo.packageName; break; }
                    }
                }
                if (browserPackage != null) open.setPackage(browserPackage);
                if (browserPackage != null && canOpen(userContext, open)) return action(userContext, open,
                        context.getString(R.string.arkui_suggest_open_link), user);
            }
        }
        final String number = text.trim().toUpperCase(Locale.ROOT);
        if (DELIVERY.enabled(resolver, user) && EXPRESS.matcher(number).matches()) {
            final Uri uri = new Uri.Builder().scheme("https").authority("m.kuaidi100.com")
                    .path("/result.jsp").appendQueryParameter("nu", number).build();
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
            if (canOpen(userContext, intent)) return action(userContext, intent,
                    context.getString(R.string.arkui_suggest_delivery), user);
        }
        return null;
    }
    private static String appLabel(Context context, String pkg) {
        try { return context.getPackageManager().getApplicationInfo(pkg, 0).loadLabel(context.getPackageManager()).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }
    private static boolean canOpen(Context context, Intent intent) {
        return context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }
    private static RemoteAction action(Context context, Intent intent, String title, int user) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pending = PendingIntent.getActivityAsUser(context, intent.toUri(0).hashCode(),
                intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, null, UserHandle.of(user));
        return new RemoteAction(Icon.createWithResource(context, R.drawable.ic_arrow_forward), title, title, pending);
    }
}
