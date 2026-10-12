package com.houg.tv.sign;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * 红果 App 接口请求装配：固定 query 参数 + 随机 device_id/iid + _rticket + X-Gorgon 签名。
 * 对齐 Go provider_hongguo_app.go 的 hongguoAppRequest。
 */
public final class SignedRequest {

    /** 主域名；实际使用时优先走 {@link HostPool}（多域名容灾）。 */
    public static final String BASE_URL = "https://api5-normal-sinfonlineb.fqnovel.com";

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 每次启动生成一次并缓存（19 位数字） */
    private static final String DEVICE_ID = random19();
    private static final String IID = random19();

    public final HttpUrl url;
    public final Map<String, String> headers;
    public final byte[] body;
    /** 本次请求使用的域名（带 https:// 前缀），供调用方做成功/失败上报。 */
    public final String baseUrl;

    public SignedRequest(String path, byte[] body) {
        this(path, body, false, null);
    }

    /**
     * @param comment 评论（弹幕）接口走专用签名：X-SS-STUB 置空 + X-Argus/X-Ladon +
     *                Comment-Source/Server-Channel 头（对齐 Go signHongguoCommentRequest）。
     */
    public SignedRequest(String path, byte[] body, boolean comment) {
        this(path, body, comment, null);
    }

    /**
     * @param baseUrl 指定接口域名（如 https://api5-normal-lf.fqnovel.com）；传 null 用 HostPool 当前首选。
     */
    public SignedRequest(String path, byte[] body, boolean comment, String baseUrl) {
        this.body = body == null ? new byte[0] : body;
        String base = (baseUrl == null || baseUrl.isEmpty()) ? HostPool.current() : baseUrl;
        this.baseUrl = base;
        long now = System.currentTimeMillis();
        long unixSec = now / 1000L;

        HttpUrl.Builder b = HttpUrl.parse(base + path).newBuilder();
        b.addQueryParameter("aid", "8662");
        b.addQueryParameter("app_name", "novelread");
        b.addQueryParameter("version_code", "73532");
        b.addQueryParameter("version_name", "7.3.5.32");
        b.addQueryParameter("manifest_version_code", "73532");
        b.addQueryParameter("update_version_code", "73532");
        b.addQueryParameter("channel", "update_64");
        b.addQueryParameter("device_platform", "android");
        b.addQueryParameter("os", "android");
        b.addQueryParameter("ssmix", "a");
        b.addQueryParameter("device_type", "25053RT47C");
        b.addQueryParameter("device_brand", "Redmi");
        b.addQueryParameter("language", "zh");
        b.addQueryParameter("os_api", "36");
        b.addQueryParameter("os_version", "16");
        b.addQueryParameter("resolution", "1280*2772");
        b.addQueryParameter("dpi", "520");
        b.addQueryParameter("ac", "wifi");
        b.addQueryParameter("device_id", DEVICE_ID);
        b.addQueryParameter("iid", IID);
        b.addQueryParameter("_rticket", String.valueOf(now));

        HttpUrl built = b.build();
        String rawQuery = built.encodedQuery();

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", XGorgon.USER_AGENT);
        h.put("Accept", "application/json");
        h.put("X-XS-From-Web", "0");
        h.put("Sdk-Version", "2");
        h.put("Content-Type", "application/json; charset=utf-8");

        if (comment) {
            HongguoCommentSign.Result sign = HongguoCommentSign.sign(rawQuery, now);
            h.put("Comment-Source", "601");
            h.put("Server-Channel", "1000");
            h.put("X-Khronos", sign.xKhronos);
            h.put("X-SS-STUB", "");
            h.put("X-Gorgon", sign.xGorgon);
            h.put("X-SS-Req-Ticket", sign.xReqTicket);
            h.put("X-Argus", sign.xArgus);
            h.put("X-Ladon", sign.xLadon);
        } else {
            h.put("X-Khronos", String.valueOf(unixSec));
            h.put("X-SS-STUB", XGorgon.ssStub(this.body));
            h.put("X-Gorgon", XGorgon.sign(rawQuery, this.body, unixSec));
            h.put("X-SS-Req-Ticket", String.valueOf(now));
        }

        this.url = built;
        this.headers = h;
    }

    public static String deviceId() { return DEVICE_ID; }
    public static String iid() { return IID; }

    /** 19 位数字：1000000000000000000 + random(8000000000000000000) */
    private static String random19() {
        byte[] r = new byte[8];
        RANDOM.nextBytes(r);
        BigInteger v = new BigInteger(1, r);
        BigInteger span = BigInteger.valueOf(8000000000000000000L);
        BigInteger base = BigInteger.valueOf(1000000000000000000L);
        return base.add(v.mod(span)).toString();
    }
}
