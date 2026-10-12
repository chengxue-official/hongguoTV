package com.houg.tv.sign;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 红果 App 接口域名池（容灾）。
 *
 * <p>字节系 fqnovel.com 接口存在多个等价域名，X-Gorgon 签名只绑定 path+query+body，
 * <b>不绑定 host</b>，因此任一域名返回的签名对全部域名通用（2026-10-11 实测确认）。
 * 当主域名不可用（DNS 失败 / 连接失败 / HTTP 非 200）时，自动降级到下一个域名。
 *
 * <p>实测结论（JVM + okhttp，弹幕接口 + 分类接口双测 code=0）：
 * <ul>
 *   <li>可用：sinfonlineb（主）、sinfonlinea、sinfonlinec、lf、lq、hl</li>
 *   <li>不可用（NXDOMAIN）：sinfonline1、sinfonline2、sinfonlinea1、sinfonlineb1、sinfonline</li>
 * </ul>
 *
 * <p>线程安全：{@link #currentIndex} 为 AtomicInteger，失败时轮转到下一域名。
 */
public final class HostPool {

    private HostPool() {}

    /** 已验证可用的域名，第一个为主域名（保持与原 BASE_URL 一致）。 */
    private static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "https://api5-normal-sinfonlineb.fqnovel.com",
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-sinfonlinec.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
            "https://api5-normal-lq.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com"
    ));

    /** 当前首选域名下标；失败时前移，成功时归位。 */
    private static final AtomicInteger currentIndex = new AtomicInteger(0);

    public static List<String> hosts() {
        return HOSTS;
    }

    public static int size() {
        return HOSTS.size();
    }

    /** 当前首选域名（带 https:// 前缀）。 */
    public static String current() {
        return HOSTS.get(currentIndex.get() % HOSTS.size());
    }

    /** 当前首选域名下标。 */
    public static int currentIndex() {
        return currentIndex.get() % HOSTS.size();
    }

    /** 按池顺序取第 i 个域名作为尝试起点（i 从 0 递增，越界自动回绕）。 */
    public static String at(int i) {
        int n = HOSTS.size();
        return HOSTS.get(((i % n) + n) % n);
    }

    /** 某域名请求成功：把它提升为首选，后续请求优先复用它。 */
    public static void reportSuccess(String host) {
        int idx = HOSTS.indexOf(host);
        if (idx >= 0) currentIndex.set(idx);
    }

    /** 某域名请求失败：轮转到下一个域名。 */
    public static void reportFailure(String host) {
        int idx = HOSTS.indexOf(host);
        if (idx >= 0 && idx == currentIndex.get() % HOSTS.size()) {
            currentIndex.compareAndSet(idx, (idx + 1) % HOSTS.size());
        }
    }

    /** 当前首选域名的简称（仅主机名，用于日志）。 */
    public static String currentHostOnly() {
        String c = current();
        return c.startsWith("https://") ? c.substring("https://".length()) : c;
    }
}
