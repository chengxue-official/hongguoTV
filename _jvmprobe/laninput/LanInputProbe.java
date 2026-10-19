import com.houg.tv.net.LanInputServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * LanInputServer 的 JVM 协议级验证：
 * 把生产源码原封不动编译后在 JVM 上启动，用 HttpURLConnection 模拟手机端请求。
 * 验证：启动/端口、页面可达、/submit 回调触发与中文解码、URL 编码边界、并发、停止后拒连。
 */
public class LanInputProbe {

    static int passed = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  | " + detail));
        } else {
            failures.add(name + "  | " + detail);
            System.out.println("FAIL  " + name + "  | " + detail);
        }
    }

    /** 用 GET 打服务，返回 [状态码, 响应体]。 */
    static String[] get(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            body.append(line).append('\n');
        }
        reader.close();
        conn.disconnect();
        return new String[]{String.valueOf(code), body.toString()};
    }

    static int PORT;

    public static void main(String[] args) throws Exception {
        List<String> received = new ArrayList<>();
        LanInputServer server = new LanInputServer(kw -> {
            received.add(kw);
            return kotlin.Unit.INSTANCE;
        });

        // ---- 1. 启动与端口 ----
        int port = server.start();
        check("启动返回有效端口", port > 0, "port=" + port);
        check("port 属性一致", server.getPort() == port, "get=" + server.getPort());
        check("重复 start 幂等", server.start() == port, "二次 start 返回同端口");

        String addr = server.localAddress();
        check("本机地址探测", addr != null && !addr.isEmpty(), "addr=" + addr);

        PORT = port;
        Thread.sleep(200);

        // ---- 2. 页面 ----
        String[] page = get("/");
        check("首页 200", page[0].equals("200"), "code=" + page[0]);
        check("页面含输入框与提交脚本",
                page[1].contains("id=\"kw\"") && page[1].contains("/submit?kw="),
                "含 kw 输入框+提交 JS=" + page[1].contains("send()"));
        check("页面 charset utf-8", page[1].contains("charset=\"utf-8\"")
                || page[1].contains("remote input") || page[1].contains("远程输入"),
                "标题含红果TV=" + page[1].contains("红果TV"));

        // ---- 3. /submit 回调（含中文与特殊字符）----
        submit("都市", "纯中文");
        submit("庆余年", "剧名·中文");
        submit("a b+c", "空格加号（往返应保持不变）");
        submit("%E5%BA%86%E4%BD%99%E5%B9%B4", "百分号字面量（往返应保持不变）");
        submit("", "空关键词（应不触发回调）");
        // 直接构造原始 query 验证 `+` 号解码为空格（URLEncoder 会把空格编成 +）
        String[] plus = get("/submit?kw=" + "红果+TV");
        check("submit 加号即空格 返回 200/ok", plus[0].equals("200"), "code=" + plus[0]);

        Thread.sleep(300);
        check("回调触发 4 次（空词不计）", received.size() == 5, "received=" + received);
        if (received.size() >= 4) {
            check("中文正确解码", "都市".equals(received.get(0)) && "庆余年".equals(received.get(1)),
                    received.toString());
            check("空格+加号 往返不变", "a b+c".equals(received.get(2)), received.get(2));
            check("百分号字面量 往返不变", "%E5%BA%86%E4%BD%99%E5%B9%B4".equals(received.get(3)), received.get(3));
            check("query 中 + 解码为空格", "红果 TV".equals(received.get(4)), received.get(4));
        }

        // ---- 4. 并发 5 个请求 ----
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    get("/submit?kw=" + URLEncoder.encode("并发" + idx, "UTF-8"));
                } catch (Exception e) {
                    System.out.println("并发请求失败: " + e);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        Thread.sleep(400);
        check("并发 5 请求全部到达", received.size() == 5 + 5, "received=" + received.size());

        // ---- 5. 停止后拒连 ----
        server.stop();
        Thread.sleep(200);
        boolean refused = false;
        try {
            get("/");
        } catch (Exception e) {
            refused = true;
        }
        check("停止后拒绝连接", refused, "stop 后再次请求应抛异常");

        System.out.println();
        System.out.println("==== 结果: " + passed + " 通过, " + failures.size() + " 失败 ====");
        for (String f : failures) {
            System.out.println("  FAILED: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void submit(String kw, String label) throws Exception {
        String q = kw.isEmpty() ? "" : ("?kw=" + URLEncoder.encode(kw, "UTF-8"));
        String[] r = get("/submit" + q);
        check("submit " + label + " 返回 200/ok", r[0].equals("200") && r[1].contains("ok"),
                "code=" + r[0] + " body=" + r[1].trim());
    }
}
