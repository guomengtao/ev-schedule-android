package com.application.watch.classschedule;

/**
 * 零操作激活通道的常量收口。
 *
 * 为什么单独一个文件：Supabase 的地址与 anon key 会出现在 APK 里（可反编译），
 * 集中放一处便于审计「到底有什么随包分发」。**这里只放可公开凭据**：
 *   · anon key 受 RLS 约束，只能读 activation_events（sid/status/ts 三列，无敏感信息）
 *   · activation_codes（18 位码）对 anon 是全部拒绝，实测 HTTP 401
 * 取码必须走服务端 /api/pay-session?pickup=1 一次性接口。
 *
 * ⚠️ 不要把 service_role key、爱发电 token 之类的密钥放进这个类。
 */
final class ZeroCfg {

    private ZeroCfg() {
    }

    /** 服务端（激活码真源仍在这里，Supabase 只承担事件推送） */
    static final String API_BASE = Net.BASE;
    static final String API_DEVICE = API_BASE + "/api/device";
    static final String API_PAY_SESSION = API_BASE + "/api/pay-session";

    /** Supabase 项目（Realtime 直连，不经 Vercel） */
    static final String SUPA_URL = "https://kqzkdpmyivtpxkfmibnd.supabase.co";
    static final String SUPA_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
            + "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImtxemtkcG15aXZ0cHhrZm1pYm5kIiwicm9sZSI6ImFub24i"
            + "LCJpYXQiOjE3ODg3NTI0MjgsImV4cCI6MjEwNDMyODQyOH0."
            + "3T5JF9CXM6C8k2NI9VEygU-vxlPZj96iTqLORNHD63w";
    static final String SUPA_TABLE = "activation_events";

    /**
     * 支付会话最长等待。超过就停 WS、改成「手动检查」——
     * 全程 0 轮询，只有用户主动点一次才发一次请求。
     */
    static final long PAY_WAIT_MS = 5 * 60 * 1000L;

    /** 取码接口超时（毫秒），与 Net 的读超时对齐即可 */
    static final int HTTP_TIMEOUT_MS = 9000;
}
