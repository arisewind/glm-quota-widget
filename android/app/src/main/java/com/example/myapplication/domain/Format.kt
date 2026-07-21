package com.example.myapplication.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用量时间格式化（UI + widget 共用）。统一 "MM-dd HH:mm"；ts <= 0 或无值返回 "—"。
 * 每次 new SimpleDateFormat 以保证线程安全（widget 后台线程 / UI 主线程并发调用）。
 */
fun formatTime(ts: Long): String {
    if (ts <= 0) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}
