package app.powerhub.data

/**
 * Whether a station is reachable, from PowerHub's point of view.
 *
 * - `false`: the cloud reported the station offline after its last sign of life.
 * - `true`: data (or a cloud "online") arrived within [DeviceSnapshot.OFFLINE_AFTER_MS].
 * - `false`: PowerHub has been connected to the cloud long enough and the station stayed silent.
 * - `null`: unknown. PowerHub itself is offline (or reconnected less than the offline window ago),
 *   so silence says nothing about the station; alerts must not fire on it.
 */
fun stationReachable(lastSeen: Long, offlineReportedAt: Long, now: Long, cloudUpSince: Long?): Boolean? {
    if (offlineReportedAt > lastSeen) return false
    if (lastSeen > 0 && now - lastSeen < DeviceSnapshot.OFFLINE_AFTER_MS) return true
    if (cloudUpSince == null) return null
    // After a reconnect every station gets the same window to report before it counts as offline.
    if (now - cloudUpSince < DeviceSnapshot.OFFLINE_AFTER_MS) return null
    return false
}

/** "45 хв", "2 год 5 хв", "1 д 3 год". */
fun durationText(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(1)
    val days = totalMin / (24 * 60)
    val hours = (totalMin / 60) % 24
    val min = totalMin % 60
    return when {
        days > 0 -> "$days д $hours год"
        hours > 0 -> "$hours год $min хв"
        else -> "$min хв"
    }
}
