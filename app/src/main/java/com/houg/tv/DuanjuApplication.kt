package com.houg.tv

import android.app.Application
import com.houg.tv.data.WatchHistory
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.net.NetClient
import com.houg.tv.util.AppLog
import com.houg.tv.util.DeviceInfo

/** 全局单例容器：提供网络层与追剧历史的全局访问。 */
object App {
    lateinit var instance: Application
        private set
    lateinit var netClient: NetClient
        private set
    lateinit var watchHistory: WatchHistory
        private set

    fun init(app: Application) {
        instance = app
        netClient = NetClient(HttpClientProvider.get(app))
        watchHistory = WatchHistory(app)
        // 开启日志文件持久化（全量、跨启动保留），必须在第一笔日志之前
        AppLog.attach(app)
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
