package com.houg.tv.net;

import android.util.Base64;

import com.houg.tv.sign.HongguoCrypto;
import com.houg.tv.sign.HostPool;
import com.houg.tv.sign.SignedRequest;
import com.houg.tv.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 红果短剧网络层（分类 / 详情 / 播放 / 搜索）。
 * 分类/详情/播放走 App 版接口（X-Gorgon 签名），搜索走网页版（免签名）。
 * 兼容 Android 4.0（API 14）：OkHttp 3.12.13 + 系统 org.json，避开 StandardCharsets（API 19）。
 */
public final class NetClient {

    /** 网页版（搜索用） */
    private static final String WEB_SITE = "https://hongguoduanju.com";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String WEB_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1";

    private static final Pattern ROUTER_DATA = Pattern.compile(
            "(?s)(?:window\\.)?_ROUTER_DATA\\s*=\\s*");

    /* ---- 榜单（网页版 /rank/&lt;board&gt;，与搜索同站，免签名） ---- */

    /** 榜单形态一：mergeLoaderData 脚本，data-fn-args 用双引号包裹（多数榜单）。 */
    private static final Pattern RANK_FN_ARGS_DQ = Pattern.compile(
            "(?s)data-fn-args=\"(\\[.*?\\])\"");

    /** 榜单形态二：run-router-data-fn 脚本，data-fn-args 用单引号包裹（部分榜单）。 */
    private static final Pattern RANK_FN_ARGS_SQ = Pattern.compile(
            "(?s)data-fn-args='(\\[.*?\\])'");

    /** 榜单每页条数（服务端固定 20，用于校验 rank 区间）。 */
    private static final int RANK_PAGE_SIZE = 20;

    /** 榜单最大页码（服务端分页上限）。 */
    private static final int RANK_MAX_PAGE = 500;

    private static final String TAG = "NetClient";

    /** 单次请求最多尝试的域名数（防止极端情况下 6×15s 超时把播放链拖死）。 */
    private static final int APP_HOST_ATTEMPTS = 3;

    /* ---- 搜索增强（突破网页搜索页固定 10 条的限制） ---- */

    /** 搜索建议接口的 app_id（与网页版一致）。 */
    private static final String SUGGEST_APP_ID = "8662";

    /** 建议接口请求条数（服务端实际上限约 20）。 */
    private static final int SEARCH_SUGGEST_COUNT = 50;

    /** 多季补全最多额外发起的建议请求数（控制 TV 上的搜索耗时）。 */
    private static final int SEARCH_SEASON_REQUESTS = 10;

    /** 单个系列最多额外发起的补季请求数。 */
    private static final int SEARCH_SEASON_PER_GROUP = 4;

    /** 单系列向外延伸时连续空手几次即停。 */
    private static final int SEARCH_SEASON_MISSES = 2;

    /** 合理的最大季/部数（超出视为脏数据）。 */
    private static final int SEARCH_SEASON_MAX = 200;

    /**
     * 季数后缀识别：第N季 / 第N部（支持阿拉伯数字与中文数字），且必须位于片名末尾。
     * 末尾锚点是必要的——否则「离婚第二季特辑」这类特别篇会被误并入「离婚」系列，
     * 补季时污染结果。参考真果鉴用 {@code [\p{P}\s]*$}，这里用 {@code \s*$}
     * （parseSeason 已先 trim，尾部标点由 base 清理逻辑处理）。
     */
    private static final Pattern SEASON_MARK = Pattern.compile(
            "第\\s*([0-9０-９零〇一二两兩三四五六七八九十百]+)\\s*([季部])\\s*$");

    private final OkHttpClient client;

    public NetClient(OkHttpClient client) {
        this.client = client;
        AppLog.INSTANCE.setInterfaceInfo(SignedRequest.BASE_URL,
                SignedRequest.deviceId(), SignedRequest.iid());
    }

    /* ==================== 数据模型 ==================== */

    public static final class Drama {
        public final String id;          // series_id
        public final String title;
        public final String cover;
        public final String intro;
        public final String episodeCount;
        public final String remark;      // 更新状态/共N集
        public final List<String> tags;

        Drama(String id, String title, String cover, String intro,
              String episodeCount, String remark, List<String> tags) {
            this.id = id;
            this.title = title;
            this.cover = cover;
            this.intro = intro;
            this.episodeCount = episodeCount;
            this.remark = remark;
            this.tags = tags;
        }

        /** 公开工厂方法：本地片名索引命中时构造精简 Drama（无简介/标签）。 */
        public static Drama minimal(String id, String title, String cover) {
            return new Drama(id, title, cover, "", "", "", Collections.<String>emptyList());
        }
    }

    public static final class Episode {
        public final String vid;         // 每集视频 ID
        public final int index;          // 集号，从 1 开始
        public final String title;

        Episode(String vid, int index, String title) {
            this.vid = vid;
            this.index = index;
            this.title = title;
        }
    }

    public static final class SeriesDetail {
        public final String seriesId;
        public final String title;
        public final String cover;
        public final String intro;
        public final List<Episode> episodes;

        SeriesDetail(String seriesId, String title, String cover,
                     String intro, List<Episode> episodes) {
            this.seriesId = seriesId;
            this.title = title;
            this.cover = cover;
            this.intro = intro;
            this.episodes = episodes;
        }
    }

    public static final class Variant {
        public final String codec;        // codec_type（bytevc1=HEVC）
        public final int height;          // 清晰度高度
        public final List<String> urls;   // 该变体的多地址（已去重+解码）
        public final byte[] cencKey;      // CENC-AES-128 密钥（未加密时为 null）
        public final boolean encrypted;   // 是否 CENC 加密

        Variant(String codec, int height, List<String> urls, byte[] cencKey, boolean encrypted) {
            this.codec = codec;
            this.height = height;
            this.urls = urls;
            this.cencKey = cencKey;
            this.encrypted = encrypted;
        }
    }

    public static final class VideoModel {
        public final String videoId;        // 请求的 vid
        public final List<String> urls;     // 最优变体的多地址（向后兼容）
        public final int duration;          // 秒
        public final String referer;        // App 版媒体固定 https://novel.snssdk.com/
        public final byte[] cencKey;        // 最优变体的 CENC-AES-128 密钥（未加密时为 null）
        public final boolean encrypted;     // 最优变体是否 CENC 加密
        public final List<Variant> variants; // 全部可选变体（按高度降序）
        public final String codec;          // 最优变体 codec
        public final int height;            // 最优变体高度

        VideoModel(String videoId, List<String> urls, int duration, String referer,
                   byte[] cencKey, boolean encrypted,
                   List<Variant> variants, String codec, int height) {
            this.videoId = videoId;
            this.urls = urls;
            this.duration = duration;
            this.referer = referer;
            this.cencKey = cencKey;
            this.encrypted = encrypted;
            this.variants = variants;
            this.codec = codec;
            this.height = height;
        }
    }

    public static final class CatalogPage {
        public final List<Drama> dramas;
        public final boolean hasMore;
        public final int nextOffset;
        public final String sessionId;

        CatalogPage(List<Drama> dramas, boolean hasMore, int nextOffset, String sessionId) {
            this.dramas = dramas;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
            this.sessionId = sessionId;
        }
    }

    public static final class DanmakuItem {
        public final String id;
        public final String text;
        public final long timeMs;

        DanmakuItem(String id, String text, long timeMs) {
            this.id = id;
            this.text = text;
            this.timeMs = timeMs;
        }
    }

    /** 榜单一条：名次 + 剧集 + 热度/评分等展示文案。 */
    public static final class RankItem {
        public final int rank;
        public final Drama drama;
        /** 热度文案，如「7850万热度」 */
        public final String heat;
        /** 评分文案，如「9.0」 */
        public final String score;
        /** 收藏文案，如「268.7万收藏」 */
        public final String favorite;
        /** 点赞文案，如「470.6万点赞」 */
        public final String like;
        /** 状态标签，如「新剧」「热播中」 */
        public final List<String> statusTags;

        RankItem(int rank, Drama drama, String heat, String score, String favorite, String like,
                 List<String> statusTags) {
            this.rank = rank;
            this.drama = drama;
            this.heat = heat;
            this.score = score;
            this.favorite = favorite;
            this.like = like;
            this.statusTags = statusTags;
        }
    }

    /** 榜单分页结果。 */
    public static final class RankingPage {
        public final String boardId;
        public final int page;
        public final int totalPages;
        public final boolean hasMore;
        /** 榜单更新时间文案，如「10月14日已更新 · 基于红果内观看/互动等综合热度排序」 */
        public final String updatedText;
        public final List<RankItem> items;

        RankingPage(String boardId, int page, int totalPages, boolean hasMore,
                    String updatedText, List<RankItem> items) {
            this.boardId = boardId;
            this.page = page;
            this.totalPages = totalPages;
            this.hasMore = hasMore;
            this.updatedText = updatedText;
            this.items = items;
        }
    }

    /**
     * 榜单定义。{@code boardId} 是我们自己的稳定标识（也用于 UI 记忆选中项）；
     * {@code path} 是红果网页 {@code /rank/&lt;path&gt;} 的路径段。
     */
    public static final class RankBoard {
        public final String boardId;
        public final String path;
        public final String label;

        public RankBoard(String boardId, String path, String label) {
            this.boardId = boardId;
            this.path = path;
            this.label = label;
        }
    }

    /** 可用榜单列表（顺序即 UI 展示顺序）。 */
    public static final List<RankBoard> RANK_BOARDS = Collections.unmodifiableList(
            new ArrayList<RankBoard>() {{
                add(new RankBoard("hot-drama", "hot-drama", "热播榜"));
                add(new RankBoard("hot-real-drama", "hot-real-drama", "真人剧榜"));
                add(new RankBoard("hot-comic-drama", "hot-comic-drama", "漫剧榜"));
                add(new RankBoard("hot-ai-drama", "hot-ai-drama", "AI剧榜"));
            }});

    /* ==================== 分类（App 版） ==================== */

    public CatalogPage catalog(String genreKey, String scene, int offset, String sessionId) throws Exception {
        AppLog.INSTANCE.d(TAG, "catalog genre=" + genreKey + " scene=" + scene + " offset=" + offset);

        JSONObject selectItems = new JSONObject();
        selectItems.put("genre", new JSONArray().put(genreKey));
        selectItems.put("sort", new JSONArray().put("online_time"));
        selectItems.put("gender", new JSONArray());
        selectItems.put("category_dim_theme", new JSONArray());
        selectItems.put("category_dim_role", new JSONArray());
        selectItems.put("category_dim_epoch", new JSONArray());
        selectItems.put("online_time", new JSONArray());
        selectItems.put("creation_status", new JSONArray());

        JSONObject body = new JSONObject();
        body.put("req_scene", scene == null ? "" : scene);
        body.put("offset", offset);
        body.put("limit", 18);
        body.put("req_type", "only_content");
        body.put("need_selector_panel", false);
        body.put("client_req_type", offset > 0 ? 2 : 3);
        body.put("session_id", sessionId == null ? "" : sessionId);
        body.put("filter_ids", "");
        body.put("select_items", selectItems);

        JSONObject root = appPost("/reading/distribution/category/landpage/v/", body.toString());
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new Exception("红果分类接口未返回数据");
        }
        JSONArray rows = data.optJSONArray("video_data");
        List<Drama> dramas = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                if (d != null && d.id != null && !d.id.isEmpty()) {
                    dramas.add(d);
                }
            }
        }
        boolean hasMore = data.optBoolean("has_more", false);
        int nextOffset = data.optInt("next_offset", offset + (rows == null ? 0 : rows.length()));
        String nextSession = data.optString("session_id", sessionId == null ? "" : sessionId);
        AppLog.INSTANCE.d(TAG, "catalog 返回 " + dramas.size() + " 条 hasMore=" + hasMore);
        return new CatalogPage(dramas, hasMore, nextOffset, nextSession);
    }

    /**
     * 拉取某个分类的全量片单（翻页到底），用于构建本地片名索引。
     * @param maxPages 翻页上限（防御，避免接口异常导致无限拉取）
     */
    public List<Drama> catalogAll(String genreKey, String scene, int maxPages) throws Exception {
        List<Drama> all = new ArrayList<>();
        int offset = 0;
        String session = "";
        for (int page = 0; page < maxPages; page++) {
            CatalogPage p = catalog(genreKey, scene, offset, session);
            all.addAll(p.dramas);
            if (!p.hasMore || p.dramas.isEmpty()) break;
            offset = p.nextOffset;
            session = p.sessionId;
        }
        AppLog.INSTANCE.d(TAG, "catalogAll genre=" + genreKey + " 共 " + all.size() + " 条");
        return all;
    }

    /* ==================== 榜单（网页版） ==================== */

    /**
     * 拉取红果官方榜单一页：{@code GET /rank/&lt;board.path&gt;}（page&gt;1 时加
     * {@code ?page=N}）。页面是 SSR 直出的 HTML，榜单数据内嵌在
     * {@code data-fn-args} 脚本属性里（两种形态：双引号包裹的 mergeLoaderData、
     * 单引号包裹的 run-router-data-fn），解析出 {@code rankList} 数组。
     * 与搜索同站免签名，复用 {@link #getText(String)}。
     */
    public RankingPage ranking(RankBoard board, int page) throws Exception {
        if (board == null || page < 1 || page > RANK_MAX_PAGE) {
            throw new Exception("榜单页码无效");
        }
        String url = WEB_SITE + "/rank/" + board.path + (page > 1 ? "?page=" + page : "");
        AppLog.INSTANCE.d(TAG, "ranking board=" + board.boardId + " page=" + page);
        String html = getText(url);
        if (html == null || html.isEmpty() || html.length() > 4 << 20) {
            throw new Exception("榜单页面无响应或过大");
        }
        JSONObject content = extractRankContent(html);
        if (content == null || !content.optBoolean("isSuccess", false)) {
            throw new Exception("榜单数据不可用");
        }
        JSONArray rows = content.optJSONArray("rankList");
        List<RankItem> items = new ArrayList<>();
        if (rows != null) {
            int previous = (page - 1) * RANK_PAGE_SIZE;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                RankItem item = parseRankRow(row, previous);
                if (item != null) items.add(item);
            }
        }
        if (items.isEmpty()) {
            throw new Exception("榜单暂无数据");
        }
        JSONObject pagination = content.optJSONObject("pagination");
        int pageNum = pagination == null ? page : pagination.optInt("pageNum", page);
        int totalPages = pagination == null ? page : pagination.optInt("totalPages", page);
        boolean hasMore = pageNum < totalPages && !isEmpty(pagination == null ? null : pagination.optString("nextUrl", ""));
        // updatedText 在 loaderData 的 rank_*/page 页对象里，脚本形态二没有；用页面级解析兜底
        String updatedText = extractRankUpdatedText(html);
        AppLog.INSTANCE.d(TAG, "ranking 返回 " + items.size() + " 条 page=" + pageNum + "/" + totalPages);
        return new RankingPage(board.boardId, pageNum, totalPages, hasMore, updatedText, items);
    }

    /**
     * 从 HTML 提取榜单数据对象（{@code isSuccess}/{@code rankList}/{@code pagination}）。
     * 两种脚本形态统一兼容：先扫单引号属性（形态二 JSON 直接在参数数组里），
     * 再扫双引号属性（形态二嵌套在 routerDataFnArgs[0] 字符串里）。
     */
    private static JSONObject extractRankContent(String html) {
        // 形态二（单引号）：data-fn-args='[key, "content", {isSuccess, rankList...}]'
        Matcher sq = RANK_FN_ARGS_SQ.matcher(html);
        while (sq.find()) {
            Object arr = parseJsonLenient(unescapeHtml(sq.group(1)));
            if (!(arr instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) arr;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.has("rankList")) return o;
            }
        }
        // 形态一（双引号）：mergeLoaderData 的 routerDataFnArgs[0] 是 JSON 字符串
        Matcher dq = RANK_FN_ARGS_DQ.matcher(html);
        while (dq.find()) {
            Object arr = parseJsonLenient(unescapeHtml(dq.group(1)));
            if (!(arr instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) arr;
            for (int i = 0; i < a.length(); i++) {
                JSONObject entry = a.optJSONObject(i);
                if (entry == null) continue;
                JSONArray fnArgs = entry.optJSONArray("routerDataFnArgs");
                if (fnArgs == null || fnArgs.length() == 0) continue;
                Object v = parseJsonLenient(fnArgs.optString(0));
                if (v instanceof JSONObject && ((JSONObject) v).has("rankList")) {
                    return (JSONObject) v;
                }
            }
        }
        return null;
    }

    /** 解析单条榜单行。rank 必须落在当前页区间且不重复出现倒退（防御脏数据）。 */
    private static RankItem parseRankRow(JSONObject row, int previousMax) {
        String id = firstNonEmpty(optText(row, "seriesId"), optText(row, "id"));
        if (id == null || id.isEmpty() || !id.matches("\\d{10,30}")) return null;
        String title = optText(row, "title");
        if (title == null || title.isEmpty()) return null;
        int rank = row.optInt("rank", 0);
        if (rank <= previousMax || rank > previousMax + RANK_PAGE_SIZE) return null;

        String cover = optText(row, "cover");
        String intro = optText(row, "description");
        String heat = optText(row, "heatText");
        String score = optText(row, "scoreText");
        if (score != null) score = score.replaceFirst("^评分", "");
        String favorite = optText(row, "favoriteText");
        String like = optText(row, "likeText");
        // 展示行：热度 + 状态标签（第1名另有金色徽标由 UI 处理）
        StringBuilder remark = new StringBuilder();
        if (heat != null && !heat.isEmpty()) remark.append(heat);
        List<String> statusTags = new ArrayList<>();
        JSONArray st = row.optJSONArray("statusTags");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                String t = st.optString(i);
                if (t != null && !t.isEmpty()) statusTags.add(t);
            }
        }
        List<String> tags = new ArrayList<>();
        JSONArray tg = row.optJSONArray("tags");
        if (tg != null) {
            for (int i = 0; i < tg.length(); i++) {
                addTag(tags, tg.optString(i));
            }
        }
        if (statusTags.size() > 0) {
            addTag(tags, statusTags.get(0));
        }
        Drama drama = new Drama(id, title, cover, intro, "", remark.toString(), tags);
        return new RankItem(rank, drama, heat, score, favorite, like, statusTags);
    }

    /** 从页面 JSON 里抠出 updatedText（形态二没有此字段，找不到就空串）。 */
    private static String extractRankUpdatedText(String html) {
        int i = html.indexOf("\"updatedText\"");
        if (i < 0) i = html.indexOf("updatedText");
        if (i < 0) return "";
        int colon = html.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = html.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = html.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        String raw = html.substring(q1 + 1, q2);
        // 该字段在 _ROUTER_DATA 的 JSON 字符串里，中文是原样、斜杠可能被转义
        return unescapeHtml(raw).replace("\\u002F", "/");
    }

    /** HTML 实体反转义（仅处理 &quot; &amp; &lt; &gt; &#39; 与数字实体）。 */
    private static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** 宽容 JSON 解析：用 JSONTokener 只取前缀合法值，失败返回 null。 */
    private static Object parseJsonLenient(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            JSONTokener t = new JSONTokener(s);
            Object v = t.nextValue();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /* ==================== 详情（App 版） ==================== */

    public SeriesDetail detail(String seriesId) throws Exception {
        AppLog.INSTANCE.d(TAG, "detail series_id=" + seriesId);
        // 先试 App 版详情；实测该接口当前返回空，因此失败/为空时回退网页版详情（返回完整 vid_list）
        try {
            SeriesDetail d = appDetail(seriesId);
            if (!d.episodes.isEmpty()) {
                return d;
            }
            AppLog.INSTANCE.d(TAG, "App 详情为空，回退网页版详情");
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "App 详情失败，回退网页版详情", e);
        }
        try {
            SeriesDetail w = webDetail(seriesId);
            if (w.episodes.isEmpty()) {
                // 两路都拿不到剧集：该 series_id 在服务端无有效内容（已下架/无效条目）。
                // 抛明确异常，让详情页提示"可能已下架"，而不是让用户看到黑屏卡死。
                throw new Exception("该内容可能已下架或暂时无法观看");
            }
            return w;
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "detail 全部失败 series_id=" + seriesId, e);
            throw e;
        }
    }

    /** App 版详情：data.video_data.video_list[]（每项 vid + vid_index）。 */
    private SeriesDetail appDetail(String seriesId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("series_id", seriesId);

        JSONObject root = appPost("/novel/player/video_detail/v1/", body.toString());
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = new JSONObject();
        JSONObject vd = data.optJSONObject("video_data");
        if (vd == null || vd.length() == 0) vd = data;

        String title = firstNonEmpty(optText(vd, "series_title"), optText(vd, "series_name"),
                optText(vd, "title"), seriesId);
        String cover = firstNonEmpty(optText(vd, "series_cover"), optText(vd, "cover"));
        String intro = firstNonEmpty(optText(vd, "series_intro"), optText(vd, "video_desc"));

        List<Episode> episodes = new ArrayList<>();
        JSONArray list = vd.optJSONArray("video_list");
        if (list == null) list = data.optJSONArray("video_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String vid = item.optString("vid");
                int idx = item.optInt("vid_index", 0);
                if (vid.isEmpty() || idx < 1) continue;
                episodes.add(new Episode(vid, idx, "第" + idx + "集"));
            }
        }
        Collections.sort(episodes, new Comparator<Episode>() {
            @Override
            public int compare(Episode a, Episode b) {
                return a.index - b.index;
            }
        });
        AppLog.INSTANCE.d(TAG, "App detail 返回 " + episodes.size() + " 集");
        return new SeriesDetail(seriesId, title, cover, intro, episodes);
    }

    /** 网页版详情：seriesDetail.vid_list 字符串数组，集号按下标 + 1。 */
    private SeriesDetail webDetail(String seriesId) throws Exception {
        String url = WEB_SITE + "/detail?series_id=" + encode(seriesId);
        JSONObject root = fetchRouterData(url);
        JSONObject pageObj = findLoaderPage(root, "detail_page", "detail_");
        if (pageObj == null) {
            throw new Exception("红果详情数据不可用");
        }
        JSONObject detail = pageObj.optJSONObject("seriesDetail");
        if (detail == null || detail.length() == 0) {
            throw new Exception("红果详情为空");
        }

        String title = firstNonEmpty(optText(detail, "series_name"),
                optText(detail, "series_title"), optText(detail, "name"), seriesId);
        String cover = firstNonEmpty(optText(detail, "series_cover"), optText(detail, "cover"));
        String intro = firstNonEmpty(optText(detail, "series_intro"), optText(detail, "video_desc"));

        List<Episode> episodes = new ArrayList<>();
        JSONArray vids = detail.optJSONArray("vid_list");
        if (vids != null) {
            for (int i = 0; i < vids.length(); i++) {
                String vid = vids.optString(i);
                if (vid == null) continue;
                vid = vid.trim();
                if (vid.isEmpty() || "<nil>".equals(vid)) continue;
                int idx = i + 1;
                episodes.add(new Episode(vid, idx, "第" + idx + "集"));
            }
        }
        AppLog.INSTANCE.d(TAG, "web detail 返回 " + episodes.size() + " 集");
        return new SeriesDetail(seriesId, title, cover, intro, episodes);
    }

    /* ==================== 播放（App 版） ==================== */

    /**
     * 取播放模型（video_model），返回其中的 video_model 对象。
     *
     * <p><b>2026-10-15 接口变更（重要）</b>：红果服务端把旧的
     * {@code POST /novel/player/video_model/v1/} 单集接口<b>硬关停</b>了——请求
     * 返回 HTTP 200 + 0 字节空 body（响应头 {@code X-Dsa-Origin-Status: 200}），
     * 源站主动静默拒绝，且 6 个域名全部如此。同签名下 video_detail / catalog /
     * search 均正常（354KB），证明签名与 device_id 有效——是接口级下线，不是风控。
     *
     * <p>替代接口为 {@code POST /novel/player/multi_video_model/preload/v1}，
     * 请求体改为：
     * <pre>
     * {
     *   "biz_param": { ..., "need_all_video_definition": true, "video_platform": 1024 },
     *   "dr_scene": "preload",
     *   "mixed_video_id_map": { "1004": [vid] }
     * }
     * </pre>
     * 响应为 {@code {"code":0,"data":{"<vid>":{"video_model":"<内嵌JSON字符串>"}}}}，
     * 内嵌字符串结构与旧接口的 video_model 完全一致（video_list / encrypt_info /
     * video_meta 均不变），因此后续解析逻辑无需改动。
     *
     * <p>注意：{@code video_platform} 必须是 <b>1024</b>（旧值 3 会被拒：
     * {@code invalid param, debug_info: "video platform invalid, param:3"}）。
     */
    private JSONObject fetchVideoModel(String vid) throws Exception {
        JSONObject bizParam = new JSONObject();
        // 对齐 zhenyong97/hongguo-downloader：完整 biz_param 字段更稳
        bizParam.put("detail_page_version", 0);
        bizParam.put("device_level", 3);
        bizParam.put("disable_digg_stat", false);
        bizParam.put("need_all_video_definition", true);
        bizParam.put("need_mp4_align", false);
        bizParam.put("use_os_player", false);
        bizParam.put("use_server_dns", false);
        bizParam.put("video_platform", 1024);

        JSONObject contentTypes = new JSONObject();
        contentTypes.put("1004", new JSONArray().put(vid));

        JSONObject body = new JSONObject();
        body.put("biz_param", bizParam);
        body.put("dr_scene", "preload");
        body.put("mixed_video_id_map", contentTypes);

        Exception lastError = null;

        // 主路径：multi_video_model/preload（单集也能用，返回 data[vid].video_model）
        try {
            JSONObject root = appPost("/novel/player/multi_video_model/preload/v1/", body.toString());
            JSONObject model = extractVideoModel(root, vid);
            if (model != null) return model;
            lastError = new Exception("红果播放信息为空");
        } catch (Exception e) {
            lastError = e;
            AppLog.INSTANCE.e(TAG, "videoModel 新接口失败，尝试非 preload 路径", e);
        }

        // 兜底：multi_video_model/v1（部分旧环境只放行该路径）
        try {
            JSONObject root = appPost("/novel/player/multi_video_model/v1/", body.toString());
            JSONObject model = extractVideoModel(root, vid);
            if (model != null) return model;
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "videoModel 非 preload 路径也失败", e);
        }

        AppLog.INSTANCE.e(TAG, "videoModel 取流失败 vid=" + vid
                + "（新接口 multi_video_model/preload 与 v1 均无数据）", lastError);
        throw lastError != null ? lastError : new Exception("红果播放信息为空");
    }

    /**
     * 从 multi_video_model 响应里取出 video_model 对象。
     * 响应形如 {@code {"code":0,"data":{"<vid>":{"video_model":"{\"video_list\":[...]}"}}}}；
     * video_model 通常是<b>内嵌 JSON 字符串</b>，也兼容直接是对象的情况。
     *
     * <p><b>必须按 vid 精确匹配</b>：绝不能退化成「取 data 里第一个条目」——
     * 那会在本集取不到时拿到<b>别的集</b>的播放地址，导致用户点第 5 集播出第 1 集。
     * 取不到就老实返回 null，由上层报错。
     */
    private JSONObject extractVideoModel(JSONObject root, String vid) {
        if (root == null) return null;
        JSONObject data = root.optJSONObject("data");
        if (data == null || data.length() == 0) return null;

        Object entry = data.opt(vid);
        if (entry instanceof JSONObject) {
            JSONObject item = (JSONObject) entry;
            JSONObject model = optModelObject(item.opt("video_model"));
            if (model != null) return model;
            // data[vid] 本身就是 video_model（个别形态）
            if (item.optJSONArray("video_list") != null) return item;
            return null;
        }

        // 兼容：响应没有按 vid 分组，data 直接就是 model（或 model 的包装）。
        // 这种情况无法校验归属，仅在最新接口形态下作为兜底。
        JSONObject model = optModelObject(data.opt("video_model"));
        if (model != null) return model;
        if (data.optJSONArray("video_list") != null) return data;

        AppLog.INSTANCE.e(TAG, "videoModel 响应缺少 vid=" + vid + " 条目（data keys 数=" + data.length() + "）", null);
        return null;
    }

    /** video_model 既可能是内嵌 JSON 字符串，也可能是对象；统一转成 JSONObject。 */
    private JSONObject optModelObject(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (!s.isEmpty()) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public VideoModel videoModel(String vid) throws Exception {
        return videoModel(vid, 0);
    }

    /**
     * 取播放信息。
     *
     * @param maxHeight 清晰度上限（像素高）；&lt;=0 表示不限制。低端设备（Android 5.x /
     *                  小内存）由调用方传入 720，把 1080p 变体过滤掉——否则硬解
     *                  1080p HEVC 本就贴着上限，倍速或 seek 时必然掉帧/卡死。
     *                  仅当存在不超过上限的变体时才过滤，保证任何设备都有可播源。
     */
    public VideoModel videoModel(String vid, int maxHeight) throws Exception {
        AppLog.INSTANCE.d(TAG, "videoModel video_id=" + vid);
        JSONObject model = fetchVideoModel(vid);
        if (model == null) throw new Exception("红果播放信息为空");
        // 收集 bytevc1(HEVC) 变体：只跳过 bytevc2（AV1），按高度降序选最优
        List<Variant> variants = new ArrayList<>();
        Exception keyErr = null;
        for (JSONObject v : videoListVariants(model)) {
            JSONObject meta = v.optJSONObject("video_meta");
            String codec = meta == null ? "" : meta.optString("codec_type", "").toLowerCase();
            if (codec.contains("bytevc2")) {
                continue;
            }
            List<String> addrs = extractMediaAddresses(v);
            if (addrs.isEmpty()) continue;

            // 解析 encrypt_info（对齐 Go：spade_a 非空 || encrypt==true || cenc-aes-ctr 即视为加密）
            JSONObject encryption = v.optJSONObject("encrypt_info");
            byte[] cencKey = null;
            boolean encrypted = isEncrypted(encryption);
            if (encrypted) {
                String spade = encryption == null ? "" : encryption.optString("spade_a", "");
                try {
                    cencKey = HongguoCrypto.contentKey(spade);
                } catch (Exception e) {
                    keyErr = e;
                    AppLog.INSTANCE.e(TAG, "videoModel 密钥派生失败，跳过该变体", e);
                    continue;
                }
            }

            int height = definitionHeight(meta);
            variants.add(new Variant(codec, height, addrs, cencKey, encrypted));
        }
        Collections.sort(variants, new Comparator<Variant>() {
            @Override
            public int compare(Variant a, Variant b) {
                return b.height - a.height;
            }
        });

        // ===== 低端设备:限制清晰度上限 =====
        // 1080p HEVC 在 Android 5.x / 小内存设备上硬解余量极小,倍速(≥1.5x)或
        // 拖动 seek 时解码吞吐跟不上 → 掉帧卡顿、音频来不及初始化而无声。
        // 降一档到 720p 给解码器留出余量,观感损失远小于卡顿。
        // 只在「确实存在不超上限的变体」时才过滤,避免某些剧只有 1080p 时反而无源可播。
        int cappedHeight = 0;
        if (maxHeight > 0) {
            List<Variant> lower = new ArrayList<>();
            for (Variant v : variants) {
                if (v.height > 0 && v.height <= maxHeight) lower.add(v);
            }
            if (!lower.isEmpty() && lower.size() < variants.size()) {
                cappedHeight = variants.get(0).height;
                variants = lower;
            }
        }

        if (variants.isEmpty()) {
            if (keyErr != null) {
                throw new Exception("红果媒体密钥不可用");
            }
            throw new Exception("红果未返回兼容的媒体（可能仅剩 bytevc2 编码）");
        }

        int duration = parseDoubleInt(model.opt("video_duration"));
        if (duration <= 0) duration = parseDoubleInt(model.opt("duration"));

        Variant selected = variants.get(0);
        AppLog.INSTANCE.d(TAG, "videoModel 取得 " + variants.size() + " 个变体，选中 codec="
                + selected.codec + " height=" + selected.height + " encrypted=" + selected.encrypted
                + " urls=" + selected.urls.size() + " duration=" + duration
                + (cappedHeight > 0 ? " [低端设备限高 " + maxHeight + "p，已从 " + cappedHeight + "p 降档]" : ""));
        return new VideoModel(vid, selected.urls, duration, "https://novel.snssdk.com/",
                selected.cencKey, selected.encrypted, variants, selected.codec, selected.height);
    }

    /* ==================== 弹幕（App 版） ==================== */

    public List<DanmakuItem> danmaku(String seriesId, String videoId, long startMs, long durationMs) throws Exception {
        AppLog.INSTANCE.d(TAG, "danmaku video=" + videoId + " window=" + startMs);

        JSONArray guide = new JSONArray();
        guide.put(1).put(3).put(4).put(2);
        JSONObject bizParam = new JSONObject();
        bizParam.put("book_id", seriesId == null ? "" : seriesId);
        bizParam.put("start_offset_time", startMs);
        bizParam.put("playlet_item_duration", durationMs);
        bizParam.put("need_danmaku_guide_type", guide);

        JSONObject body = new JSONObject();
        body.put("comment_source", 601);
        body.put("server_channel", 1000);
        body.put("group_id", videoId);
        body.put("group_type", 30);
        body.put("comment_type", 20);
        body.put("sort", 1);
        body.put("count", 90);
        body.put("cursor", "");
        body.put("aid", 8662);
        body.put("compliance_status", 0);
        body.put("business_param", bizParam);

        // 弹幕接口必须走评论专用签名（普通 X-Gorgon 会返回 110001）
        JSONObject root = appPost("/novel/commentapi/comment/list/" + videoId + "/v1/", body.toString(), true);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new Exception("红果弹幕接口未返回数据");
        }

        // 时间窗口推进值（本窗口右边界）。
        // ⚠️ 服务端已改版：老的 extra.next_query_danmaku_list_time 不再下发（恒为 null），
        // 若仍按旧字段解析会得到 -1L → 每窗口都提前判空返回，
        // 结果就是"弹幕开了跟没开一样"。新字段在 common_list_info.cursor 里，
        // 它本身是一个被字符串化的 JSON，内含 end_offset_time。
        JSONObject cli = data.optJSONObject("common_list_info");
        long nextMs = -1L;
        long totalCount = -1L;
        if (cli != null) {
            String cursorRaw = cli.optString("cursor", "");
            if (!cursorRaw.isEmpty()) {
                try {
                    JSONObject cursor = new JSONObject(cursorRaw);
                    nextMs = parseLongStr(cursor.optString("end_offset_time"));
                    if (cursor.has("danmaku_count")) {
                        totalCount = cursor.optLong("danmaku_count", -1L);
                    }
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "danmaku cursor 解析失败: " + cursorRaw, e);
                }
            }
        }
        // 兼容旧版字段（服务端灰度期间可能仍下发）
        if (nextMs <= 0) {
            JSONObject extra = data.optJSONObject("extra");
            long legacy = parseLongStr(extra == null ? null : extra.optString("next_query_danmaku_list_time"));
            if (legacy > 0) nextMs = legacy;
        }
        // 最后兜底：服务端两个窗口字段都没给时，按 startMs + 30s 推进。
        // 不能直接返回空——否则整集弹幕永远为 0（本次 bug 的病灶）。
        if (nextMs <= startMs) {
            nextMs = startMs + WINDOW_MS;
            AppLog.INSTANCE.d(TAG, "danmaku 无窗口字段，兜底推进到 " + nextMs);
        }
        // 对齐参考实现：最后一窗的 next 会超过剧集时长，需截断到 duration 而不是丢弃整窗
        if (durationMs > 0) nextMs = Math.min(nextMs, durationMs);

        List<DanmakuItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JSONArray list = data.optJSONArray("data_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                JSONObject comment = item.optJSONObject("comment");
                if (comment == null) continue;
                JSONObject common = comment.optJSONObject("common");
                JSONObject content = common == null ? null : common.optJSONObject("content");
                String text = content == null ? "" : content.optString("text", "");
                String groupId = common == null ? "" : common.optString("group_id", "");
                String status = common == null ? "" : common.optString("status", "");
                JSONObject expand = comment.optJSONObject("expand");
                long offsetMs = parseLongStr(expand == null ? null : expand.optString("offset_time"));
                String id = comment.optString("comment_id", "");

                if (!"1".equals(status)) continue;
                if (!videoId.equals(groupId)) continue;
                if (offsetMs < startMs || offsetMs >= nextMs) continue;
                if (text == null || text.isEmpty()) continue;
                if (id == null || id.isEmpty() || id.length() > MAX_COMMENT_ID_LEN) continue;
                if (!seen.add(id)) continue;

                text = cleanDanmakuText(expandDanmakuEmoji(text));
                if (text.isEmpty()) continue;
                items.add(new DanmakuItem(id, text, offsetMs));
            }
        }
        Collections.sort(items, new Comparator<DanmakuItem>() {
            @Override
            public int compare(DanmakuItem a, DanmakuItem b) {
                return a.timeMs < b.timeMs ? -1 : (a.timeMs > b.timeMs ? 1 : 0);
            }
        });
        // totalCount 已在上方解析 cursor 时取出，用于日志诊断"数据是否为空"
        AppLog.INSTANCE.d(TAG, "danmaku 返回 " + items.size() + " 条 (窗口 " + startMs
                + "→" + nextMs + "，整集 danmaku_count=" + totalCount + ")");
        return items;
    }

    /* ==================== 搜索（网页版 + 建议接口） ==================== */

    /**
     * 关键词搜索。
     *
     * 用户反馈：搜索只显示 10 条，多季短剧显示不全。根因是网页版搜索页
     * /search/&lt;kw&gt; 的 searchList 固定只返回第一页 10 条（totalCount 却高达上百），
     * 且没有可用的分页参数。
     *
     * 解决：三路合一
     *   1) 网页搜索页 searchList（10 条，含完整 video_data）；
     *   2) 搜索建议接口 /incent_resource/suggestion（最多约 20 条，其中
     *      word_type=="short_play_name" 的记录带完整 video_data，可突破 10 条）；
     *   3) 多季补全——识别「第N季/第N部」系列，对缺失季数再发变体查询补齐。
     * 最后按「与关键词的匹配度 + 同系列季数」排序，保证多季剧按季序排列。
     */
    public List<Drama> search(String keyword) throws Exception {
        AppLog.INSTANCE.d(TAG, "search keyword=" + keyword);
        List<Drama> merged = new ArrayList<>();
        Map<String, Integer> byId = new LinkedHashMap<>();
        Set<String> idSeen = new HashSet<>();

        // ---- 1) 搜索建议接口（先跑，通常能给出更多片名）----
        try {
            List<Drama> sug = fetchSuggestDramas(keyword, SEARCH_SUGGEST_COUNT);
            mergeDramas(merged, byId, idSeen, sug);
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "search 建议接口失败（忽略）", e);
        }

        // ---- 2) 网页搜索页（10 条，字段最全）----
        try {
            for (Drama d : fetchSearchPageDramas(keyword)) {
                mergeDramas(merged, byId, idSeen, Collections.singletonList(d));
            }
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "search 网页页失败（忽略）", e);
        }

        // ---- 3) 多季补全（对识别出的系列补齐缺失季数）----
        if (!merged.isEmpty()) {
            try {
                completeSeasons(keyword, merged, byId, idSeen);
            } catch (Exception e) {
                AppLog.INSTANCE.e(TAG, "search 多季补全失败（忽略）", e);
            }
        }

        sortSearchResult(merged, keyword);
        AppLog.INSTANCE.d(TAG, "search 返回 " + merged.size() + " 条（去重后）");
        return merged;
    }

    /** 网页版 /search/&lt;kw&gt; 解析（原逻辑，固定 10 条）。 */
    private List<Drama> fetchSearchPageDramas(String keyword) throws Exception {
        String url = WEB_SITE + "/search/" + encode(keyword);
        JSONObject root = fetchRouterData(url);
        JSONObject pageObj = findLoaderPage(root, "search_");
        List<Drama> result = new ArrayList<>();
        if (pageObj == null || !pageObj.optBoolean("isSuccess", false)
                || !keyword.equals(optText(pageObj, "query"))) {
            return result;
        }
        JSONArray rows = pageObj.optJSONArray("searchList");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                if (d != null && d.id != null && !d.id.isEmpty()) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    /**
     * 搜索建议接口：{@code GET /incent_resource/suggestion?app_id=8662&query=<kw>&count=N}
     * 返回 {@code suggest_list}；仅取 {@code word_type=="short_play_name"} 且带有效
     * series_id 的记录。{@code video_data} 结构与搜索页一致，直接复用 parseDrama。
     */
    private List<Drama> fetchSuggestDramas(String keyword, int count) throws Exception {
        String url = WEB_SITE + "/incent_resource/suggestion?app_id=" + SUGGEST_APP_ID
                + "&query=" + encode(keyword) + "&count=" + count;
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", WEB_UA)
                .header("Referer", WEB_SITE + "/")
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        String text;
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new Exception("红果建议接口 HTTP " + resp.code());
            }
            byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
            text = new String(bytes, UTF8);
        }
        if (text.isEmpty()) return new ArrayList<>();

        List<Drama> result = new ArrayList<>();
        JSONObject root = new JSONObject(text);
        JSONArray list = root.optJSONArray("suggest_list");
        if (list == null) {
            JSONObject data = root.optJSONObject("data");
            list = data == null ? null : data.optJSONArray("suggest_list");
        }
        if (list == null) return result;

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            // 只认 short_play_name：它是唯一携带完整 video_data（含 series_id/封面/集数）
            // 的类型。title / common_query 类只有数字 keyword，并不对应可播放的剧集
            // （参考真果鉴 fetchHongguoSearchNames 同样只取 short_play_name）。
            if (!"short_play_name".equals(optText(item, "word_type"))) continue;
            JSONObject vd = item.optJSONObject("video_data");
            if (vd == null || vd.length() == 0) continue;

            Drama d = parseDrama(vd, item);
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (!isNumericId(d.id)) continue;
            result.add(d);
        }
        return result;
    }

    /** series_id 必须是纯数字（suggest 里非剧集条目会给出 "1_f8597..." 这类伪 id）。 */
    private static boolean isNumericId(String id) {
        if (id == null || id.isEmpty() || id.length() > 24) return false;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return false;
        }
        return true;
    }

    /** 按 series_id 去重合并，保留字段更全的那条。 */
    private static void mergeDramas(List<Drama> target, Map<String, Integer> byId,
                                    Set<String> idSeen, List<Drama> batch) {
        for (Drama d : batch) {
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (!idSeen.add(d.id)) {
                Integer at = byId.get(d.id);
                if (at != null && at >= 0 && at < target.size()) {
                    Drama old = target.get(at);
                    // 用字段更全的新数据补齐（封面/简介/集数）
                    Drama better = richer(old, d);
                    if (better != old) target.set(at, better);
                }
                continue;
            }
            byId.put(d.id, target.size());
            target.add(d);
        }
    }

    /** 选择字段更完整的一条（封面/简介/集数非空者优先）。 */
    private static Drama richer(Drama a, Drama b) {
        int sa = score(a), sb = score(b);
        return sb > sa ? b : a;
    }

    private static int score(Drama d) {
        if (d == null) return -1;
        int s = 0;
        if (d.cover != null && !d.cover.isEmpty()) s += 3;
        if (d.intro != null && !d.intro.isEmpty()) s += 2;
        if (d.episodeCount != null && !d.episodeCount.isEmpty()) s += 1;
        if (d.remark != null && !d.remark.isEmpty()) s += 1;
        return s;
    }

    /* ---- 多季补全 ---- */

    /**
     * 识别结果里的「同系列多季」剧集，对缺失的季数用变体关键词
     * （如「闪婚第2季」/「闪婚 第二季」）再查一次建议接口补齐。
     * 参考真果鉴 provider_hongguo_search_seasons.go 的策略，做了简化：
     * 请求预算收紧到 {@link #SEARCH_SEASON_REQUESTS}，避免 TV 上搜索过慢。
     */
    private void completeSeasons(String keyword, List<Drama> merged, Map<String, Integer> byId,
                                 Set<String> idSeen) {
        // 关键词本身已带季/部标记时不做补全
        if (seasonOf(keyword) > 0) return;

        Map<String, List<Drama>> groups = new LinkedHashMap<>();
        for (Drama d : merged) {
            String[] ps = parseSeason(d.title);
            int season = Integer.parseInt(ps[1]);
            String base = ps[0];
            String unit = ps[2];
            if (season <= 0) continue;
            String key = normTitle(base) + "#" + unit;
            if (!key.contains(normTitle(keyword))) continue;
            List<Drama> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(d);
        }
        if (groups.isEmpty()) return;

        int budget = SEARCH_SEASON_REQUESTS;
        for (Map.Entry<String, List<Drama>> e : groups.entrySet()) {
            if (budget <= 0) break;
            String unit = e.getKey().substring(e.getKey().lastIndexOf('#') + 1);
            String base = parseSeason(e.getValue().get(0).title)[0];
            Set<Integer> known = new HashSet<>();
            int max = 0;
            for (Drama d : e.getValue()) {
                int s = seasonOf(d.title);
                if (s > 0) {
                    known.add(s);
                    if (s > max) max = s;
                }
            }
            int used = 0;
            int misses = 0;
            // 第 1 季通常不带「第N季」后缀（片名就是裸的基础名），
            // 若组内其它季存在但缺第 1 季，用基础名本身再查一次。
            if (max >= 2 && !known.contains(1) && budget > 0) {
                budget--;
                used++;
                if (querySeasonOne(base, unit, merged, byId, idSeen)) {
                    known.add(1);
                    misses = 0;
                } else {
                    misses++;
                }
            }
            // 先补 2..max 中缺失的季
            for (int s = 2; s <= max && budget > 0 && used < SEARCH_SEASON_PER_GROUP; s++) {
                if (known.contains(s)) continue;
                budget--;
                used++;
                if (querySeason(base, s, unit, merged, byId, idSeen)) {
                    known.add(s);
                    misses = 0;
                } else if (++misses >= SEARCH_SEASON_MISSES) {
                    break;
                }
            }
            // 再向 max+1 方向延伸（多季剧往往不止两季）
            int probeFrom = Math.max(max + 1, 2);
            while (budget > 0 && used < SEARCH_SEASON_PER_GROUP * 2 && misses < SEARCH_SEASON_MISSES) {
                boolean hit = false;
                for (int s = probeFrom; s <= probeFrom + 1 && budget > 0; s++) {
                    budget--;
                    used++;
                    if (querySeason(base, s, unit, merged, byId, idSeen)) {
                        hit = true;
                        known.add(s);
                        max = Math.max(max, s);
                        probeFrom = s + 1;
                        break;
                    }
                }
                if (hit) {
                    misses = 0;
                } else if (++misses >= SEARCH_SEASON_MISSES) {
                    break;
                }
            }
        }
    }

    /** 用基础名本身查一次建议接口，找无后缀的第 1 季。 */
    private boolean querySeasonOne(String base, String unit, List<Drama> merged,
                                   Map<String, Integer> byId, Set<String> idSeen) {
        String baseKey = normTitle(base);
        try {
            List<Drama> hit = fetchSuggestDramas(base, SEARCH_SUGGEST_COUNT);
            boolean any = false;
            for (Drama d : hit) {
                String[] ps = parseSeason(d.title);
                if (Integer.parseInt(ps[1]) != 0) continue;      // 只要无后缀的裸片名
                if (!normTitle(ps[0]).equals(baseKey)) continue;
                if (idSeen.contains(d.id)) continue;
                mergeDramas(merged, byId, idSeen, Collections.singletonList(d));
                any = true;
            }
            return any;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 用「基础名+第N季/部」查一次建议接口，命中则并入结果。 */
    private boolean querySeason(String base, int season, String unit, List<Drama> merged,
                                Map<String, Integer> byId, Set<String> idSeen) {
        String baseKey = normTitle(base);
        // 阿拉伯数字与中文数字两种写法各试一次（服务端对两者召回不同）
        String[] labels = new String[]{String.valueOf(season), chineseNum(season)};
        for (String label : labels) {
            for (String sep : new String[]{"", " "}) {
                String q = base + "第" + sep + label + unit;
                if (normTitle(q).isEmpty()) continue;
                try {
                    List<Drama> hit = fetchSuggestDramas(q, SEARCH_SUGGEST_COUNT);
                    boolean any = false;
                    for (Drama d : hit) {
                        String[] ps = parseSeason(d.title);
                        if (Integer.parseInt(ps[1]) <= 0) continue;
                        if (!normTitle(ps[0]).equals(baseKey)) continue;
                        if (!unit.equals(ps[2])) continue;
                        if (idSeen.contains(d.id)) continue;
                        mergeDramas(merged, byId, idSeen, Collections.singletonList(d));
                        any = true;
                    }
                    if (any) return true;
                } catch (Exception ignored) {
                    // 单个变体失败不影响其它变体
                }
            }
        }
        return false;
    }

    /** 取季/部编号（无标记返回 0）。 */
    private static int seasonOf(String title) {
        try {
            return Integer.parseInt(parseSeason(title)[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析「第N季/第N部」：返回 [基础名, 季数, 单位]；无标记时季数为 0。 */
    static String[] parseSeason(String title) {
        String t = title == null ? "" : title.trim();
        Matcher m = SEASON_MARK.matcher(t);
        if (!m.find()) return new String[]{t, "0", ""};
        String base = t.substring(0, m.start());
        // 去掉尾部的分隔符/空白
        while (base.length() > 0) {
            char c = base.charAt(base.length() - 1);
            if (Character.isWhitespace(c) || c == '-' || c == '：' || c == ':' || c == '《'
                    || c == '·' || c == '_') {
                base = base.substring(0, base.length() - 1);
            } else {
                break;
            }
        }
        int n = chineseOrArabic(m.group(1));
        String unit = m.group(2);
        if (base.isEmpty() || n < 1 || n > SEARCH_SEASON_MAX) {
            return new String[]{t, "0", ""};
        }
        return new String[]{base, String.valueOf(n), unit};
    }

    /** 归一化标题用于比较：全角转半角、去空白与常见标点、转小写。 */
    private static String normTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0); // 全角→半角
            if (c == 0x3000) c = ' ';
            if (Character.isWhitespace(c) || c == '《' || c == '》' || c == '·' || c == '_'
                    || c == '-' || c == '：' || c == ':' || c == '，' || c == ',' || c == '！'
                    || c == '!' || c == '？' || c == '?' || c == '"' || c == '\'' || c == '“'
                    || c == '”' || c == '‘' || c == '’') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 中文数字 / 阿拉伯数字 → int；失败返回 0。
     *
     * 两处坑（都有回归用例覆盖）：
     * 1) 不能用 "零〇一二…".indexOf(c) 取位——「〇」与「零」都表示 0，会让「一」落到
     *    索引 2，季数整体偏移一位（曾把「第一季」解析成第 2 季）。
     * 2) 复合数字必须区分「待定数字 digit」与「累计值 number」：遇到十/百时执行
     *    number += max(digit,1) * 单位 并清空 digit。若写成 value = value*10+d，
     *    则「十四」会算成 104、「第十二季」会算成第 102 季。
     */
    private static int chineseOrArabic(String label) {
        if (label == null || label.isEmpty()) return 0;
        try {
            return Integer.parseInt(label);
        } catch (Exception ignored) {
        }
        int digit = 0, number = 0, previous = 1000;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            int d = digitOf(c);
            if (d >= 0) {
                digit = digit * 10 + d;
                if (digit > SEARCH_SEASON_MAX) return 0;
                continue;
            }
            int unit;
            if (c == '百') unit = 100;
            else if (c == '十') unit = 10;
            else return 0;
            if (unit >= previous || digit > 9) return 0;
            number += Math.max(digit, 1) * unit;
            if (number > SEARCH_SEASON_MAX) return 0;
            digit = 0;
            previous = unit;
        }
        number += digit;
        return number > SEARCH_SEASON_MAX ? 0 : number;
    }

    /** 单个中文数字字符 → 数值（含全角数字）；非数字返回 -1。 */
    private static int digitOf(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': case '兩': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: break;
        }
        if (c >= '０' && c <= '９') return c - '０';
        return -1;
    }

    /** 1~200 的阿拉伯数字 → 中文数字（用于变体查询）。 */
    private static String chineseNum(int n) {
        if (n < 1 || n > SEARCH_SEASON_MAX) return String.valueOf(n);
        final char[] d = "零一二三四五六七八九".toCharArray();
        if (n < 10) return String.valueOf(d[n]);
        if (n < 20) return "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        if (n < 100) {
            return String.valueOf(d[n / 10]) + "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(d[n / 100]).append('百');
        int rest = n % 100;
        if (rest == 0) return sb.toString();
        if (rest < 10) sb.append('零');
        else if (rest < 20) sb.append('一');
        sb.append(chineseNum(rest));
        return sb.toString();
    }

    /**
     * 结果排序：先按与关键词的匹配度（完全相等 &gt; 前缀 &gt; 包含 &gt; 同系列 &gt; 其它），
     * 再按（系列基础名 → 单位 → 季数 → series_id）的词典序排列，
     * 保证多季剧聚簇且按季序展示。
     *
     * 注意：比较器必须是严格全序（词典序键保证这一点）。不能写成「同系列按季数比、
     * 跨系列按 id 比」——两种键可能冲突破坏传递性（例：A系列s1(id=9) &lt; A系列s2(id=5)，
     * 但 s2(id=5) &lt; 其它(id=7) &lt; s1(id=9)），元素多于 32 个时 TimSort 可能抛
     * IllegalArgumentException（被 catch 后静默放弃排序）。
     */
    private static void sortSearchResult(List<Drama> list, String keyword) {
        final String q = normTitle(keyword);
        try {
            Collections.sort(list, new Comparator<Drama>() {
                @Override
                public int compare(Drama a, Drama b) {
                    int ra = matchRank(a.title, q), rb = matchRank(b.title, q);
                    if (ra != rb) return ra < rb ? -1 : 1;

                    String[] pa = parseSeason(a.title), pb = parseSeason(b.title);
                    int baseCmp = normTitle(pa[0]).compareTo(normTitle(pb[0]));
                    if (baseCmp != 0) return baseCmp;
                    int unitCmp = pa[2].compareTo(pb[2]);
                    if (unitCmp != 0) return unitCmp;
                    int sa = Integer.parseInt(pa[1]), sb = Integer.parseInt(pb[1]);
                    if (sa != sb) return sa < sb ? -1 : 1;
                    String ia = a.id == null ? "" : a.id;
                    String ib = b.id == null ? "" : b.id;
                    return ia.compareTo(ib);
                }
            });
        } catch (Exception ignored) {
            // 排序失败保持原序
        }
    }

    private static int matchRank(String title, String q) {
        String t = normTitle(title);
        if (q.isEmpty()) return 4;
        if (t.equals(q)) return 0;
        if (t.startsWith(q)) return 1;
        if (t.contains(q)) return 2;
        String[] ps = parseSeason(title);
        if (ps != null && !normTitle(ps[0]).isEmpty() && normTitle(ps[0]).contains(q)) return 3;
        return 4;
    }


    /* ==================== App 版请求执行 ==================== */

    private JSONObject appPost(String path, String jsonBody) throws Exception {
        return appPost(path, jsonBody, false);
    }

    /** @param comment 评论（弹幕）接口使用专用评论签名 */
    private JSONObject appPost(String path, String jsonBody, boolean comment) throws Exception {
        byte[] body = jsonBody.getBytes(UTF8);
        int attemptCount = Math.min(APP_HOST_ATTEMPTS, HostPool.size());
        int start = HostPool.currentIndex();
        Exception lastError = null;
        for (int i = 0; i < attemptCount; i++) {
            String host = HostPool.at(start + i);
            SignedRequest req = new SignedRequest(path, body, comment, host);
            AppLog.INSTANCE.setLastGorgon(req.headers.get("X-Gorgon"));
            AppLog.INSTANCE.d(TAG, "POST " + req.url);

            Request.Builder rb = new Request.Builder().url(req.url);
            for (Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            rb.post(RequestBody.create(JSON_TYPE, body));

            try (Response resp = client.newCall(rb.build()).execute()) {
                int code = resp.code();
                byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                String text = new String(bytes, UTF8);
                AppLog.INSTANCE.d(TAG, "POST " + path + " -> HTTP " + code
                        + " host=" + req.baseUrl
                        + " body=" + (text.length() > 400 ? text.substring(0, 400) : text));
                if (code != 200) {
                    HostPool.reportFailure(host);
                    lastError = new Exception("红果 App 接口 HTTP " + code);
                    continue;
                }
                if (text.isEmpty()) {
                    // 200 但空体：可能是该域名的临时异常，换域名再试
                    HostPool.reportFailure(host);
                    lastError = new Exception("红果 App 接口未返回有效数据");
                    continue;
                }
                JSONObject json = new JSONObject(text);
                int bizCode = json.optInt("code", 0);
                if (bizCode != 0) {
                    // 业务码非 0（限流/后端异常）：换域名再试，视为该域名不可用
                    HostPool.reportFailure(host);
                    lastError = new Exception("红果 App 接口业务码 " + bizCode);
                    continue;
                }
                HostPool.reportSuccess(host);
                return json;
            } catch (Exception e) {
                AppLog.INSTANCE.e(TAG, "POST " + path + " 失败 host=" + host, e);
                HostPool.reportFailure(host);
                lastError = e;
            }
        }
        AppLog.INSTANCE.e(TAG, "POST " + path + " 全部 " + attemptCount + " 个域名均失败", lastError);
        throw lastError != null ? lastError : new Exception("红果 App 接口不可用");
    }

    /* ==================== 网页版请求执行（搜索） ==================== */

    private JSONObject fetchRouterData(String url) throws Exception {
        String html = getText(url);
        if (html == null || html.isEmpty()) {
            throw new Exception("红果接口无响应");
        }
        String json = extractRouterData(html);
        if (json == null) {
            throw new Exception("红果页面结构异常");
        }
        return new JSONObject(json);
    }

    private String getText(String url) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", WEB_UA)
                .header("Referer", WEB_SITE + "/")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new Exception("红果接口 HTTP " + resp.code());
            }
            byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
            return new String(bytes, UTF8);
        }
    }

    /* ==================== 解析辅助 ==================== */

    private Drama parseDrama(JSONObject vd, JSONObject m) {
        if (vd == null || vd.length() == 0) vd = m;
        if (vd == null) return null;

        String id = firstNonEmpty(
                optText(vd, "series_id_str"), optText(vd, "series_id"), optText(vd, "keyword"),
                optText(m, "series_id_str"), optText(m, "series_id"), optText(m, "keyword"));
        if (id.isEmpty()) return null;

        String title = firstNonEmpty(
                optText(vd, "series_title"), optText(vd, "series_name"), optText(vd, "title"),
                optText(m, "series_name"), optText(m, "name"), id);
        String cover = firstNonEmpty(optText(vd, "series_cover"), optText(vd, "cover"),
                optText(m, "series_cover"));
        String intro = firstNonEmpty(optText(vd, "series_intro"), optText(vd, "video_desc"),
                optText(m, "series_intro"));
        String count = firstNonEmpty(optText(vd, "episode_cnt"), optText(m, "episode_cnt"));
        String remark = firstNonEmpty(optText(vd, "episode_right_text"),
                optText(m, "episode_right_text"));
        if (remark.isEmpty() && !count.isEmpty()) {
            remark = "共" + count + "集";
        }

        List<String> tags = new ArrayList<>();
        JSONArray tagArr = vd.optJSONArray("tags");
        if (tagArr != null) {
            for (int i = 0; i < tagArr.length(); i++) {
                addTag(tags, tagArr.optString(i));
            }
        }
        JSONArray catList = vd.optJSONArray("category_list");
        if (catList != null) {
            for (int i = 0; i < catList.length(); i++) {
                addTag(tags, optText(catList.optJSONObject(i), "name"));
            }
        }
        String schema = optText(vd, "category_schema");
        if (!schema.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(schema);
                for (int i = 0; i < arr.length(); i++) {
                    addTag(tags, optText(arr.optJSONObject(i), "name"));
                }
            } catch (Exception ignored) {
            }
        }
        addTag(tags, firstNonEmpty(optText(vd, "category_name"),
                optText(vd, "categoryName"), optText(vd, "category")));

        return new Drama(id, title, cover, intro, count, remark, tags);
    }

    private static void addTag(List<String> tags, String tag) {
        if (tag == null) return;
        tag = tag.trim();
        if (tag.isEmpty()) return;
        for (String t : tags) {
            if (t.equals(tag)) return;
        }
        tags.add(tag);
    }

    /** video_model.video_list 可能是对象（按清晰度 key）或数组，统一转成变体列表。 */
    private static List<JSONObject> videoListVariants(JSONObject model) {
        List<JSONObject> variants = new ArrayList<>();
        Object vl = model == null ? null : model.opt("video_list");
        if (vl instanceof JSONArray) {
            JSONArray arr = (JSONArray) vl;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) variants.add(o);
            }
        } else if (vl instanceof JSONObject) {
            JSONObject map = (JSONObject) vl;
            JSONArray names = map.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject o = map.optJSONObject(names.optString(i));
                    if (o != null) variants.add(o);
                }
            }
        }
        return variants;
    }

    /** 取清晰度高度：优先 definition 内数字，其次 vheight。 */
    private static int definitionHeight(JSONObject meta) {
        if (meta == null) return 0;
        String def = optText(meta, "definition");
        Matcher mm = Pattern.compile("\\d+").matcher(def);
        if (mm.find()) {
            try {
                int h = Integer.parseInt(mm.group());
                if (h > 0) return h;
            } catch (NumberFormatException ignored) {
            }
        }
        return meta.optInt("vheight", 0);
    }

    /** 判断变体是否 CENC 加密：对齐 Go 的 spade_a 非空 || encrypt==true || cenc-aes-ctr。 */
    private static boolean isEncrypted(JSONObject encryption) {
        if (encryption == null || encryption.length() == 0) return false;
        String spade = encryption.optString("spade_a", "");
        boolean encrypt = encryption.optBoolean("encrypt", false);
        String method = encryption.optString("encryption_method", "");
        return !spade.isEmpty() || encrypt || "cenc-aes-ctr".equals(method);
    }

    /** 从变体对象按顺序扫描多地址字段，扁平化 + 去重 + Base64 兜底。 */
    private static List<String> extractMediaAddresses(JSONObject info) {
        Set<String> seen = new LinkedHashSet<>();
        String[] keys = {"main_url", "backup_url", "backup_url_1",
                "backup_url_2", "backup_urls", "url_list"};
        for (String key : keys) {
            collectAddresses(info == null ? null : info.opt(key), seen);
        }
        return new ArrayList<>(seen);
    }

    private static void collectAddresses(Object value, Set<String> out) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                collectAddresses(arr.opt(i), out);
            }
            return;
        }
        String s = valueToString(value).trim();
        if (s.isEmpty() || s.length() > 8192) return;
        if (!isHttpMediaUrl(s)) {
            String decoded = decodeBase64(s).trim();
            if (decoded.isEmpty()) return;
            s = decoded;
        }
        if (isHttpMediaUrl(s)) out.add(s);
    }

    private static boolean isHttpMediaUrl(String s) {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false;
        try {
            URL u = new URL(s);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            String userInfo = u.getUserInfo();
            return userInfo == null || userInfo.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String decodeBase64(String s) {
        try {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            if (b != null && b.length > 0) return new String(b, UTF8);
        } catch (Exception ignored) {
        }
        try {
            String padded = s.trim();
            int rem = padded.length() % 4;
            if (rem != 0) {
                StringBuilder sb = new StringBuilder(padded);
                for (int i = 0; i < 4 - rem; i++) sb.append('=');
                padded = sb.toString();
            }
            byte[] b = Base64.decode(padded, Base64.NO_WRAP);
            if (b != null && b.length > 0) return new String(b, UTF8);
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 从 HTML 中提取 _ROUTER_DATA 的 JSON 子串 */
    private static String extractRouterData(String html) {
        Matcher m = ROUTER_DATA.matcher(html);
        if (!m.find()) return null;
        int start = m.end();
        int brace = html.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = brace; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return html.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    /** 定位 loaderData 下第一个非空 page 对象。name 末尾的 $ 是字面量 key，前缀匹配时去掉 $。 */
    private static JSONObject findLoaderPage(JSONObject root, String... names) {
        JSONObject loader = root == null ? null : root.optJSONObject("loaderData");
        if (loader == null) return null;
        for (String n : names) {
            JSONObject page = loader.optJSONObject(n);
            if (page != null && page.length() > 0) return page;
        }
        JSONArray keys = loader.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i);
                if (key == null) continue;
                for (String n : names) {
                    String prefix = n.endsWith("$") ? n.substring(0, n.length() - 1) : n;
                    if (!prefix.isEmpty() && key.startsWith(prefix)) {
                        JSONObject page = loader.optJSONObject(key);
                        if (page != null && page.length() > 0) return page;
                    }
                }
            }
        }
        return null;
    }

    /* ==================== 弹幕解析辅助 ==================== */

    private static final int MAX_COMMENT_ID_LEN = 120;

    /** 弹幕时间窗口长度，需与 PlayerActivity.DANMAKU_WINDOW_MS 保持一致。 */
    private static final long WINDOW_MS = 30000L;

    private static final Pattern DANMAKU_EMOJI_PATTERN = Pattern.compile("\\[[^\\[\\]\\s]{1,10}\\]");

    private static final Map<String, String> DANMAKU_EMOJI = danmakuEmojiMap();

    private static Map<String, String> danmakuEmojiMap() {
        Map<String, String> m = new HashMap<>();
        m.put("[笑哭]", "😂");   m.put("[大笑]", "😆");   m.put("[微笑]", "🙂");
        m.put("[呲牙]", "😁");   m.put("[偷笑]", "🤭");   m.put("[捂脸]", "🤦");
        m.put("[害羞]", "😊");   m.put("[调皮]", "😜");   m.put("[爱慕]", "😍");
        m.put("[色]", "😍");     m.put("[酷]", "😎");     m.put("[白眼]", "🙄");
        m.put("[抠鼻]", "🤨");   m.put("[鄙视]", "😒");   m.put("[思考]", "🤔");
        m.put("[什么]", "🤔");   m.put("[惊讶]", "😲");   m.put("[尴尬]", "😅");
        m.put("[汗]", "😅");     m.put("[闭嘴]", "🤐");   m.put("[嘘]", "🤫");
        m.put("[困]", "😴");     m.put("[睡]", "😴");     m.put("[哈欠]", "🥱");
        m.put("[流泪]", "😢");   m.put("[大哭]", "😭");   m.put("[委屈]", "🥺");
        m.put("[可怜]", "🥺");   m.put("[没看够]", "🥺"); m.put("[抓狂]", "😫");
        m.put("[发怒]", "😡");   m.put("[吐]", "🤮");     m.put("[打脸]", "🤦");
        m.put("[灵光一闪]", "💡"); m.put("[赞]", "👍");  m.put("[强]", "👍");
        m.put("[给力]", "👍");   m.put("[弱]", "👎");     m.put("[鼓掌]", "👏");
        m.put("[ok]", "👌");     m.put("[胜利]", "✌️");  m.put("[耶]", "✌️");
        m.put("[奋斗]", "💪");   m.put("[加油]", "💪");   m.put("[威武]", "💪");
        m.put("[抱抱]", "🤗");   m.put("[比心]", "🫰");   m.put("[心]", "❤️");
        m.put("[爱心]", "❤️");   m.put("[送花]", "🌹");   m.put("[玫瑰]", "🌹");
        m.put("[鲜花]", "💐");   m.put("[钱]", "💰");     m.put("[狗头]", "🐶");
        m.put("[doge]", "🐶");
        return m;
    }

    private static String expandDanmakuEmoji(String text) {
        if (text == null || text.indexOf('[') < 0) return text;
        Matcher mm = DANMAKU_EMOJI_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (mm.find()) {
            String key = mm.group();
            String rep = DANMAKU_EMOJI.get(key);
            mm.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : key));
        }
        mm.appendTail(sb);
        return sb.toString();
    }

    private static String cleanDanmakuText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) sb.append(' ');
            else sb.append(c);
        }
        String t = sb.toString().trim();
        if (t.length() > 100) t = t.substring(0, 100) + "…";
        return t;
    }

    private static long parseLongStr(String s) {
        if (s == null) return -1L;
        s = s.trim();
        if (s.isEmpty()) return -1L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /* ==================== 工具 ==================== */

    private static String optText(JSONObject o, String key) {
        if (o == null) return "";
        return valueToString(o.opt(key));
    }

    private static String valueToString(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof String) return (String) v;
        // 整型（尤其 series_id 这类 19 位长整型）必须直接 toString，严禁经 double：
        // 超过 2^53 的 long 转 double 会丢失末几位精度（实例：7683457453203147801
        // → 7683457453203147776）。此前建议接口返回的数字型 series_id 全被改坏，
        // 与网页搜索页的字符串型 series_id_str 去重时互不相等 → 同一部剧出现
        // 两张卡片，坏 ID 那张点进去必然 404（用户看到的「重复的第十/九季，
        // 一个连不上一个能看」即此因）。
        if (v instanceof Long || v instanceof Integer) return String.valueOf(v);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static int parseDoubleInt(Object v) {
        try {
            return (int) Double.parseDouble(valueToString(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A");
        } catch (Exception e) {
            return s;
        }
    }
}
