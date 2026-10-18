package com.liufe.spw.winisland.bridge

internal data class LyricCell(
    val startTime: Long,
    val endTime: Long,
    val text: String,
)

internal data class LyricLine(
    val startTime: Long,
    val text: String,
    val translated: String?,
    val cells: List<LyricCell>,
)
