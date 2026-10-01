package app.powerhub.data

import app.powerhub.protocol.Device

/**
 * Merges the account's station list into the local one. Pure function, unit-tested.
 *
 * Naming rule — the most recent rename wins:
 * - a station never renamed in PowerHub always follows the account name;
 * - a PowerHub rename is kept while the account name stays the same, but when the account
 *   name changes (renamed in the official app, e.g. on a phone) that newer name replaces it.
 *
 * Imported stations missing from the account are removed; manual ones are never touched.
 * Stations in [hidden] (deleted in PowerHub) are not brought back.
 */
fun mergeStations(current: List<Device>, account: List<Device>, hidden: Set<String>): Pair<List<Device>, SyncResult> {
    val cloud = account.filterNot { it.sn in hidden }
    val bySn = cloud.associateBy { it.sn }
    var updated = 0
    var removed = 0
    val kept = current.mapNotNull { d ->
        val c = bySn[d.sn]
        when {
            c == null && d.imported -> { removed++; null }
            c == null -> d
            else -> {
                // cloudName == null: first sync since this field appeared; take it as the baseline.
                val renamedInAccount = d.cloudName != null && d.cloudName != c.name
                val keepLocal = d.customName && !renamedInAccount
                val next = d.copy(
                    name = if (keepLocal) d.name else c.name,
                    customName = keepLocal,
                    cloudName = c.name,
                    model = c.model,
                    imported = true,
                )
                if (next.name != d.name || next.model != d.model || !d.imported) updated++
                next
            }
        }
    }
    val known = current.map { it.sn }.toSet()
    val added = cloud.filter { it.sn !in known }.map { it.copy(cloudName = it.name, imported = true) }
    return (kept + added) to SyncResult(added.size, updated, removed)
}
