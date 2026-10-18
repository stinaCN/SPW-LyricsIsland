@file:OptIn(UnstableSpwWorkshopApi::class)

package com.liufe.spw.winisland

import com.liufe.spw.winisland.bridge.HostVersion
import com.liufe.spw.winisland.bridge.IslandBridge
import com.liufe.spw.winisland.bridge.IslandProcess
import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi
import com.xuncorp.spw.workshop.api.WorkshopApi

class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    override fun start() {
        Log.info("启动，SPW ${pluginContext.spwVersion}")

        if (!HostVersion.isSupported(pluginContext.spwVersion)) {
            rejectUnsupportedVersion()
            return
        }
        try {
            val manager = WorkshopApi.manager.createConfigManager()
            val helper = manager.getConfig()
            helper.reload()
            val config = PluginConfig(helper)
            manager.addConfigChangeListener { IslandBridge.onConfigChanged() }
            IslandBridge.start(config, pluginContext.spwVersion)
        } catch (e: Throwable) {
            warn("灵动岛歌词桥启动失败: ${e.message}")
        }
    }

    private fun rejectUnsupportedVersion() {
        val version = pluginContext.spwVersion.ifBlank { "未知" }
        val text = "版本不支持：需要椒盐音乐 ${HostVersion.MIN_VERSION} 或更高版本（当前 $version），插件已自动禁用"
        Log.warn(text)
        runCatching { WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Error) }

        autoDisable()
    }

    private fun autoDisable() {
        val pluginId = pluginContext.pluginId
        for (methodName in listOf("disablePlugin", "stopPlugin")) {
            val done = runCatching {
                val manager = WorkshopApi.manager
                val method = manager.javaClass.methods.firstOrNull {
                    it.name == methodName && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                } ?: return@runCatching false
                method.invoke(manager, pluginId)
                Log.info("已通过 $methodName 停用插件")
                true
            }.getOrDefault(false)
            if (done) return
        }

        Log.warn("未能调用宿主停用接口，插件保持不工作状态，请手动禁用")
    }

    override fun stop() {
        runCatching { IslandBridge.stop() }
    }

    override fun delete() {
        runCatching { IslandBridge.stop() }
    }

    override fun update() {
        Log.info("插件已更新")
    }

    companion object {
        @JvmStatic
        @JvmName("onOpenIslandSettingsClick")
        fun onOpenIslandSettingsClick() {
            if (!IslandProcess.isRunning) {
                warn("插件未启用：请先启用本插件以启动灵动岛")
                return
            }
            val error = IslandProcess.openSettings()
            if (error != null) warn(error)
        }

        @JvmStatic
        @JvmName("onShowStatusClick")
        fun onShowStatusClick() {
            WorkshopApi.ui.toast(IslandBridge.statusText(), WorkshopApi.Ui.ToastType.Success)
        }

        private fun warn(text: String) {
            Log.warn(text)
            runCatching { WorkshopApi.ui.toast(text, WorkshopApi.Ui.ToastType.Warning) }
        }
    }
}
