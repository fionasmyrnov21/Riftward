package com.riftwardechoprotocol.k7m4x9.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Navigation {
    val directions = listOf(Cell(1, 0), Cell(0, 1), Cell(-1, 0), Cell(0, -1))
    fun walkable(m: Mission, p: Cell): Boolean {
        val t = m.at(p) ?: return false
        return t.floor && t.hp <= 0 && !(t.kind == Kind.DOOR && !t.used)
    }
    fun path(m: Mission, start: Cell, goal: Cell, actor: Int = -1, known: Boolean = false, ignoreUnits: Boolean = false): List<Cell> {
        if (start == goal || !walkable(m, goal) || known && m.at(goal)?.explored != true) return emptyList()
        val queue = ArrayDeque<Cell>(); val came = hashMapOf(start to start); queue.add(start)
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (d in directions) {
                val n = p + d
                if (n in came || !walkable(m, n) || known && m.at(n)?.explored != true) continue
                if (!ignoreUnits && m.occupied(n, actor)) continue
                came[n] = p
                if (n == goal) {
                    val result = mutableListOf<Cell>(); var c = goal
                    while (c != start) { result.add(c); c = came.getValue(c) }
                    result.reverse(); return result
                }
                queue.add(n)
            }
        }
        return emptyList()
    }
    fun line(m: Mission, a: Cell, b: Cell, smoke: Boolean = true): Boolean {
        var x = a.x; var y = a.y; val dx = abs(b.x - x); val dy = abs(b.y - y)
        val sx = if (x < b.x) 1 else -1; val sy = if (y < b.y) 1 else -1; var err = dx - dy
        while (x != b.x || y != b.y) {
            val ox = x; val oy = y; val e2 = err * 2
            if (e2 > -dy) { err -= dy; x += sx }; if (e2 < dx) { err += dx; y += sy }
            if (ox != x && oy != y && blocked(m, Cell(ox, y), smoke) && blocked(m, Cell(x, oy), smoke)) return false
            if (x == b.x && y == b.y) return true
            if (blocked(m, Cell(x, y), smoke)) return false
        }
        return true
    }
    private fun blocked(m: Mission, p: Cell, smoke: Boolean): Boolean {
        val t = m.at(p) ?: return true
        return !t.floor || t.hp > 0 && t.height == 2 || t.kind == Kind.DOOR && !t.used || smoke && t.smokeUntil > m.round
    }
    fun cover(m: Mission, attacker: Cell, target: Cell): Int {
        val dx = attacker.x - target.x; val dy = attacker.y - target.y
        val p = if (abs(dx) >= abs(dy)) target + Cell(dx.compareTo(0), 0) else target + Cell(0, dy.compareTo(0))
        val t = m.at(p) ?: return 0
        return if (t.hp > 0) if (t.height == 2) 3 else 1 else 0
    }
    fun visibility(m: Mission) {
        m.tiles.forEach { it.visible = false }
        for (u in m.units.filter { it.alive }) {
            val range = u.vision + if (u.scanUntil > m.round) 4 else 0
            m.tiles.filter { it.floor && u.cell.distance(it.cell) <= range && line(m, u.cell, it.cell, u.scanUntil <= m.round) }
                .forEach { it.visible = true; it.explored = true }
        }
    }
    fun dock(m: Mission, p: Cell) = abs(p.x - m.dockX) <= 1 && abs(p.y - m.dockY) <= 1
    fun returnRounds(m: Mission): Int {
        var result = 0
        for (u in m.units.filter { it.alive }) {
            var best = Int.MAX_VALUE
            for (t in m.tiles.filter { it.explored && dock(m, it.cell) }) {
                if (t.cell == u.cell) { best = 0; break }
                val p = path(m, u.cell, t.cell, u.id, true, true)
                if (p.isNotEmpty()) best = min(best, p.size)
            }
            if (best == Int.MAX_VALUE) return -1
            val actions = (best + u.move - 1) / u.move
            result = max(result, max(0, (actions - u.ap + 1) / 2))
        }
        return result
    }
}
