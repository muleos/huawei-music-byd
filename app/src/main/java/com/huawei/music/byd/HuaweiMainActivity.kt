package com.huawei.music.byd

import com.sonance.player.MainActivity

/**
 * 包名改为 com.huawei.music.byd 后，桌面 activity-alias 的 targetActivity 必须落在
 * 同一个 applicationId 包下，否则部分安装器会把启动入口判为无效。
 * 这里只做一层薄包装，界面与逻辑全部沿用 Sonance 原版 MainActivity。
 */
class HuaweiMainActivity : MainActivity()
