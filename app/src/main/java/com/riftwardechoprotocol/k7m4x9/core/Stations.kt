package com.riftwardechoprotocol.k7m4x9.core

import kotlin.math.min

object Stations {
    fun create(p: Campaign, offer: Offer): Mission {
        require(p.heroes.count { it.deployed } == 3) { "Three explorers are required to deploy" }
        repeat(12) { attempt -> build(p, offer, attempt, false).let { if (validate(it)) return it } }
        return build(p, offer, 0, true).also { check(validate(it)) { "Invalid fallback layout" } }
    }
    private fun build(p: Campaign, o: Offer, attempt: Int, fallback: Boolean): Mission {
        val m = Mission().also { it.seed = o.seed; it.rng = o.seed + attempt * 7919 + 1; it.theme = o.theme; it.objectiveType = o.objective
            it.oxygen = 12 + min(2, p.ship[3]); it.id = "${o.seed}-${p.attempts}"; it.tutorial = !p.tutorialSeen; it.isFinal = p.progress == 11; it.reward = o.reward }
        repeat(m.height) { y -> repeat(m.width) { x -> m.tiles.add(Tile(x, y)) } }
        repeat(6) { room ->
            val bx = 2 + room % 3 * 7; val by = 2 + room / 3 * 7
            val template = o.theme * 6 + if (fallback) 0 else m.roll(6); val layout = Content.rooms[template].layout; m.templates[room] = template
            repeat(5) { y -> repeat(5) { x -> m.at(bx + x, by + y)!!.apply { floor = true; this.room = room } } }
            if (room > 0) repeat(4) { k ->
                val place = when (layout) { 0 -> k == 0; 1 -> k < 2; 2 -> k % 2 == 0; 3 -> k != 1; 4 -> k == 3; else -> k == 1 || k == 2 }
                if (place) m.at(bx + if (k % 2 == 0) 1 else 3, by + if (k < 2) 1 else 3)!!.apply {
                    hp = if (layout == 3) 5 else 3; maxHp = hp; height = if ((layout + k) % 3 == 0) 2 else 1
                }
            }
        }
        val branch = if (fallback) 0 else m.roll(2); val shortcut = fallback || m.roll(2) == 0
        repeat(2) { row -> repeat(2) { col ->
            if (row == 0 || col == 0 || shortcut) for (x in 7 + col * 7..9 + col * 7) m.at(x, 4 + row * 7)!!.apply { floor = true; room = row * 3 + col }
        } }
        repeat(3) { col ->
            val connect = col == 2 || col == 0 && branch == 0 || col == 1 && branch == 1 || m.roll(2) == 0
            if (connect) for (y in 7..9) m.at(4 + col * 7, y)!!.apply { floor = true; room = col }
        }
        place(m, 18, 11, Kind.OBJECTIVE); place(m, 18, 4, Kind.OXYGEN)
        place(m, 11, 3, Kind.LOOT, m.roll(8)); place(m, 3, 11, Kind.LOOT, m.roll(8))
        place(m, 10, 11, Kind.ECHO, 12 + m.roll(6)); place(m, 11, 12, Kind.EVENT, m.roll(12))
        if (m.at(4, 8)!!.floor) place(m, 4, 8, Kind.DOOR)
        p.heroes.filter { it.deployed }.forEachIndexed { i, h ->
            val role = Content.roles[h.role]; val w = Content.weapons[h.weapon]
            val u = UnitState(h.id, h.role, 3 + i, 4).apply {
                maxHp = role.hp - if (h.injury > 0) 2 else 0; maxHp += min(3, h.xp / 3)
                damage = w.damage; range = w.range; maxAmmo = w.ammo
                if (p.ship[0] >= 1) maxHp++; if (p.ship[1] >= 1) damage++; if (p.ship[2] >= 1) vision++
            }
            if (h.armorMod >= 0) Content.mods[h.armorMod].let { stat(u, it.stat, it.value) }
            if (h.toolMod >= 0) Content.mods[h.toolMod].let { stat(u, it.stat, it.value) }
            if (h.specialization >= 0) stat(u, if (h.specialization == 0) 0 else 1, if (h.specialization == 0) 2 else 1)
            u.hp = u.maxHp; u.ammo = u.maxAmmo; m.units.add(u)
        }
        m.supplies[p.supply0]++; m.supplies[p.supply1]++
        if (p.ship[3] >= 3) m.supplies[0]++; if (p.ship[0] >= 3) m.supplies[1]++
        when (o.variant) { 0 -> m.supplies[0]++; 1 -> m.data += 2; else -> m.materials += 3 }; m.supplies[0] = min(2, m.supplies[0])
        if (p.ship[2] >= 2) m.tiles.filter { it.kind == Kind.LOOT }.forEach { it.explored = true }
        if (p.ship[2] >= 3) m.tiles.filter { it.floor }.forEach { it.explored = true }
        enemy(m, 0, Cell(11, 5)); enemy(m, when (o.theme) { 0 -> 3; 1 -> 4; else -> 1 }, Cell(18, 12))
        if (o.threat >= 2) enemy(m, if (o.theme == 2) 2 else 5, Cell(11, 10))
        enemy(m, if (o.threat >= 3) 6 else 1, Cell(19, 5)).dormant = true
        if (p.progress % 4 == 3) enemy(m, if (p.progress == 11) 8 else if (p.progress < 4) 6 else 7, Cell(19, 11))
        Navigation.visibility(m); m.note("Docking complete. Oxygen is consumed after each full round.")
        return m
    }
    fun stat(u: UnitState, stat: Int, value: Int) {
        when (stat) { 0 -> u.maxHp += value; 1 -> u.damage += value; 2 -> u.armor += value; 3 -> u.range += value
            4 -> u.move += value; 5 -> u.vision += value; 6 -> u.maxAmmo += value; 7 -> u.shield += value }
    }
    private fun place(m: Mission, x: Int, y: Int, kind: Kind, index: Int = 0) { m.at(x, y)!!.apply { hp = 0; this.kind = kind; this.index = index } }
    fun enemy(m: Mission, type: Int, cell: Cell): Enemy {
        val d = Content.enemies[type]
        return Enemy(m.enemies.size, type, cell.x, cell.y).also { it.hp = d.hp; it.maxHp = d.hp; it.damage = d.damage; it.armor = d.armor; it.range = d.range; it.move = d.move; m.at(cell)!!.hp = 0; m.enemies.add(it) }
    }
    fun validate(m: Mission): Boolean {
        val goal = m.tiles.find { it.kind == Kind.OBJECTIVE } ?: return false
        val path = Navigation.path(m, Cell(m.dockX, m.dockY), goal.cell, ignoreUnits = true)
        if (path.isEmpty() || (path.size * 2 + 7) / 8 > (m.oxygen * .6f).toInt()) return false
        val start = Cell(m.dockX, m.dockY); val queue = ArrayDeque<Cell>(); val reached = hashSetOf(start); queue.add(start)
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            Navigation.directions.forEach { d -> val n = at + d; val t = m.at(n)
                if (t != null && t.floor && t.hp <= 0 && reached.add(n)) queue.add(n)
            }
        }
        if (m.tiles.any { it.floor && it.hp <= 0 && it.cell !in reached }) return false
        val occupied = hashSetOf<Cell>()
        return m.units.all { Navigation.walkable(m, it.cell) && occupied.add(it.cell) } && m.enemies.all { Navigation.walkable(m, it.cell) && occupied.add(it.cell) }
    }
}
