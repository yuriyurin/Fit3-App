package io.github.yuriyurin.fit3companion

/** Two-column home dashboard. The model is independent of Compose so layouts can be validated. */
enum class HomeTileKind(val title: String) {
    WATCH("Браслет"), WEATHER("Погода"), STEPS("Шаги"), SLEEP("Сон"),
    PULSE("Пульс"), STRESS("Стресс"), SPO2("SpO₂"), DISTANCE("Дистанция"),
}

enum class HomeTileSize(val columns: Int, val rows: Int, val label: String) {
    SMALL(1, 1, "1×1"), WIDE(2, 1, "2×1"), LARGE(2, 2, "2×2"),
}

data class HomeTile(val kind: HomeTileKind, val size: HomeTileSize)

data class HomeTilePlacement(val tile: HomeTile, val column: Int, val row: Int) {
    val columns: Int get() = tile.size.columns
    val rows: Int get() = tile.size.rows
}

object HomeDashboardLayout {
    val default: List<HomeTile> = listOf(
        HomeTile(HomeTileKind.WATCH, HomeTileSize.WIDE),
        HomeTile(HomeTileKind.WEATHER, HomeTileSize.WIDE),
        HomeTile(HomeTileKind.STEPS, HomeTileSize.SMALL),
        HomeTile(HomeTileKind.SLEEP, HomeTileSize.SMALL),
        HomeTile(HomeTileKind.PULSE, HomeTileSize.SMALL),
        HomeTile(HomeTileKind.STRESS, HomeTileSize.SMALL),
        HomeTile(HomeTileKind.SPO2, HomeTileSize.SMALL),
        HomeTile(HomeTileKind.DISTANCE, HomeTileSize.SMALL),
    )

    fun encode(tiles: List<HomeTile>): String = tiles.joinToString(",") {
        "${it.kind.name}:${it.size.name}"
    }

    /** Invalid entries are ignored; newly added tiles appear after a restored older layout. */
    fun decode(raw: String?): List<HomeTile> {
        if (raw.isNullOrBlank() || raw.length > 1_000) return default
        val found = linkedMapOf<HomeTileKind, HomeTile>()
        for (entry in raw.split(',')) {
            val parts = entry.split(':')
            if (parts.size != 2) continue
            val kind = enumValues<HomeTileKind>().firstOrNull { it.name == parts[0] } ?: continue
            val size = enumValues<HomeTileSize>().firstOrNull { it.name == parts[1] } ?: continue
            found.putIfAbsent(kind, HomeTile(kind, size))
        }
        if (found.isEmpty()) return default
        return found.values.toList() + default.filter { it.kind !in found }
    }

    fun resize(tiles: List<HomeTile>, kind: HomeTileKind, size: HomeTileSize): List<HomeTile> =
        tiles.map { if (it.kind == kind) it.copy(size = size) else it }

    fun swap(tiles: List<HomeTile>, first: HomeTileKind, second: HomeTileKind): List<HomeTile> {
        val a = tiles.indexOfFirst { it.kind == first }
        val b = tiles.indexOfFirst { it.kind == second }
        if (a < 0 || b < 0 || a == b) return tiles
        return tiles.toMutableList().apply { val old = this[a]; this[a] = this[b]; this[b] = old }
    }

    fun place(tiles: List<HomeTile>): List<HomeTilePlacement> {
        val occupied = mutableListOf<BooleanArray>()
        fun ensure(row: Int) { while (occupied.size <= row) occupied.add(BooleanArray(2)) }
        val result = ArrayList<HomeTilePlacement>(tiles.size)
        for (tile in tiles) {
            var row = 0
            placement@ while (true) {
                ensure(row + tile.size.rows - 1)
                for (column in 0..(2 - tile.size.columns)) {
                    val fits = (row until row + tile.size.rows).all { r ->
                        (column until column + tile.size.columns).all { c -> !occupied[r][c] }
                    }
                    if (!fits) continue
                    for (r in row until row + tile.size.rows)
                        for (c in column until column + tile.size.columns) occupied[r][c] = true
                    result += HomeTilePlacement(tile, column, row)
                    break@placement
                }
                row++
            }
        }
        return result
    }
}
