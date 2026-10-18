import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "com.liufe.spw"
version = "1.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23")
}

spmod {
    PluginClass = "com.liufe.spw.winisland.MainPlugin"
    PluginId = "com.liufe.spw.winisland"
    PluginName = "灵动岛歌词"
    PluginDescription = "为椒盐音乐提供灵动岛歌词插件"
    PluginVersion = version.toString()
    PluginProvider = "liufeii"
    PluginOpenSourceUrl = "https://github.com/stinaCN/SPW-LyricsIsland"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
}
