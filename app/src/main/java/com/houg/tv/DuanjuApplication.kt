package com.houg.tv

import android.app.Application
import com.houg.tv.data.SearchHistory
import com.houg.tv.data.WatchHistory
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.net.NetClient
import com.houg.tv.util.AppLog
import com.houg.tv.util.CrashLogger
import com.houg.tv.util.DeviceInfo

/** 全局单例容器：提供网络层、观看历史/收藏、搜索历史的全局访问。 */
object App {
    lateinit var instance: Application
        private set
    lateinit var netClient: NetClient
        private set
    lateinit var watchHistory: WatchHistory
        private set
    lateinit var searchHistory: SearchHistory
        private set

    fun init(app: Application) {
        instance = app
        netClient = NetClient(HttpClientProvider.get())
        watchHistory = WatchHistory(app)
        searchHistory = SearchHistory(app)
        // 开启日志文件持久化（全量、跨启动保留），必须在第一笔日志之前
        AppLog.attach(app)
        // 全局崩溃捕获：之前用户反馈"点进去闪退回选集界面"却无迹可查，
        // 装上它之后任何未被捕获的异常都会落到日志文件，便于远程定位。
        CrashLogger.install(app)
        // 启动即记录设备配置信息，日志页可直接查看
        AppLog.d("DeviceInfo", "启动完成。\n" + DeviceInfo.describe(app))
    }
}

class DuanjuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}
