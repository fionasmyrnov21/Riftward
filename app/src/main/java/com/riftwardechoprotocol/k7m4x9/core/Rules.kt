package com.riftwardechoprotocol.k7m4x9.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object Rules {
    private fun bad(s: String) = Preview(false, s)
    private fun cost(u: UnitState, p: Preview) = if (p.valid && p.ap > u.ap) bad("Requires ${p.ap} AP; ${u.ap} remaining") else p
    fun area(m: Mission, center: Cell, radius: Int) = m.tiles.filter { it.floor && it.cell.distance(center) <= radius }.map { it.cell }
    fun facing(a: Cell, b: Cell): Int { val dx = b.x - a.x; val dy = b.y - a.y; return if (dx + dy >= 0) if (dx - dy < 0) 0 else 1 else if (dx - dy < 0) 2 else 3 }
    fun preview(m: Mission, cmd: Command): Preview {
        if (m.status != Status.ACTIVE) return bad("Mission complete")
        when (cmd.action) {
            Action.SOS -> return Preview(true, "Emergency return: new loot will be lost and the squad will suffer injuries.")
            Action.EXTRACT -> return if (m.units.any { it.alive && !Navigation.dock(m, it.cell) }) bad("Bring every active explorer back to the airlock zone")
                else Preview(true, if (m.objectiveComplete) "Return with loot and complete the mission" else "Return with your loot. The objective will remain incomplete")
            Action.END -> return Preview(true, if (m.oxygen <= 1) "Oxygen will run out: SOS will activate and new loot will be lost!" else "Enemies execute marked attacks. Oxygen −1; squad AP resets to 2", oxygen = -1)
            else -> Unit
        }
        val u = m.unit(cmd.actor) ?: return bad("Select an explorer")
        if (!u.alive) return bad("Explorer incapacitated")
        val t = m.at(cmd.target); val e = m.enemy(cmd.target)
        var ap = 1; var damage = 0; var oxygen = 0; var text = ""; var path = emptyList<Cell>(); var area = emptyList<Cell>()
        when (cmd.action) {
            Action.MOVE -> {
                if (t == null || !t.floor || !t.explored) return bad("Explore this area first")
                path = Navigation.path(m, u.cell, cmd.target, u.id, true)
                if (path.isEmpty()) return bad("No clear path")
                ap = (path.size + u.move - 1) / u.move; text = "Move: ${path.size} tiles · $ap AP"
            }
            Action.ATTACK -> {
                if (t?.visible != true || e == null && t.hp <= 0) return bad("Select a visible robot or cover")
                if (e?.dormant == true) return bad("Guardian offline. The oxygen station will activate it")
                if (u.ammo <= 0) return bad("Reload required")
                if (u.cell.distance(cmd.target) > u.range || !Navigation.line(m, u.cell, cmd.target)) return bad("No line of fire, or target out of range")
                val cover = if (e != null) Navigation.cover(m, u.cell, cmd.target) else 0
                damage = max(1, u.damage - (e?.armor ?: 0) - cover); area = listOf(cmd.target)
                text = "Shot: $damage damage · cover −$cover · 1 AP"
            }
            Action.RELOAD -> {
                if (u.ammo >= u.maxAmmo) return bad("Magazine already full")
                text = "Refill magazine · 1 AP"
            }
            Action.INTERACT -> return cost(u, interaction(m, u, cmd, 1))
            Action.ABILITY -> {
                if (cmd.slot !in 0..1) return bad("Unknown ability")
                val ready = if (cmd.slot == 0) u.ability0Ready else u.ability1Ready
                if (ready > m.round) return bad("Cooldown: ${ready - m.round} rounds")
                text = if (cmd.slot == 0) Content.roles[u.role].ability0 else Content.roles[u.role].ability1
                when (u.role * 2 + cmd.slot) {
                    0 -> { area = area(m, u.cell, 1); text += " · +3 shield to nearby squad members · 1 AP" }
                    1 -> {
                        if (e == null || t?.visible != true || e.dormant || u.cell.distance(e.cell) > 2 || !Navigation.line(m, u.cell, e.cell)) return bad("Requires a visible robot within 2 tiles")
                        ap = 2; damage = max(1, 4 - e.armor); area = listOf(e.cell); text += " · $damage damage and knockback · 2 AP"
                    }
                    2 -> {
                        if (e == null || t?.visible != true || e.dormant || u.cell.distance(e.cell) > 5 || !Navigation.line(m, u.cell, e.cell)) return bad("Select a visible robot within 5 tiles")
                        area = listOf(e.cell); text += " · disable for two enemy phases · 1 AP"
                    }
                    3 -> return cost(u, interaction(m, u, cmd, 6).let { it.copy(text = "Remote: ${it.text}") })
                    4 -> { area = area(m, u.cell, u.vision + 4); text += " · vision +4 for two rounds · 1 AP" }
                    5 -> {
                        path = Navigation.path(m, u.cell, cmd.target, u.id, true)
                        if (path.isEmpty() || path.size > 8) return bad("Select a clear path up to 8 tiles")
                        text += " · ${path.size} tiles · 1 AP"
                    }
                    6 -> {
                        val ally = m.units.find { it.alive && it.cell == cmd.target }
                        if (ally == null || u.cell.distance(cmd.target) > 4 || !Navigation.line(m, u.cell, cmd.target)) return bad("Select an ally within 4 tiles")
                        if (ally.hp == ally.maxHp) return bad("Ally is not injured")
                        damage = min(4, ally.maxHp - ally.hp); area = listOf(cmd.target); text += " · +$damage health · 1 AP"
                    }
                    7 -> { area = area(m, u.cell, 2); text += " · +3 shield to allies within 2 tiles · 1 AP" }
                }
            }
            Action.SUPPLY -> {
                if (cmd.slot !in 0..5 || m.supplies[cmd.slot] <= 0) return bad("This supply is depleted")
                text = Content.supplies[cmd.slot].description + " · 1 AP"
                when (cmd.slot) {
                    0 -> { if (m.oxygen >= m.oxygenMax) return bad("Oxygen already full"); oxygen = min(2, m.oxygenMax - m.oxygen) }
                    1 -> {
                        val ally = m.units.find { it.alive && it.cell == cmd.target }
                        if (ally == null || u.cell.distance(cmd.target) > 1 || ally.hp >= ally.maxHp) return bad("Select an adjacent injured ally")
                        damage = min(4, ally.maxHp - ally.hp); area = listOf(cmd.target)
                    }
                    5 -> { area = area(m, u.cell, 10) }
                    else -> {
                        if (t?.visible != true || u.cell.distance(cmd.target) > 5 || !Navigation.line(m, u.cell, cmd.target)) return bad("Select a visible tile within 5 tiles")
                        area = area(m, cmd.target, 1); damage = if (cmd.slot == 2) 4 else 0
                        if (cmd.slot == 2) m.enemies.filter { it.alive && !it.dormant && m.at(it.cell)?.visible == true && it.cell in area }.forEach { text += "\n${Content.enemies[it.type].name}: ${max(1, 4 - it.armor)} damage" }
                    }
                }
            }
            else -> return bad("Unknown command")
        }
        return cost(u, Preview(true, text, ap, damage, oxygen, path, area))
    }
    private fun interaction(m: Mission, u: UnitState, cmd: Command, range: Int): Preview {
        val t = m.at(cmd.target) ?: return bad("No equipment here")
        if (!t.visible || t.kind == Kind.NONE || t.used) return bad("Object already used or not visible")
        if (u.cell.distance(cmd.target) > range) return bad("Move closer to the object")
        if (!Navigation.line(m, u.cell, cmd.target)) return bad("A partition blocks the object")
        if (m.enemy(cmd.target) != null) return bad("Clear the guardian from the object first")
        var ap = 1; var oxygen = 0
        val text = when (t.kind) {
            Kind.OXYGEN -> {
                if (m.oxygen >= m.oxygenMax) return bad("Oxygen already full")
                oxygen = min(3, m.oxygenMax - m.oxygen)
                "Oxygen +$oxygen. Activates the nearby guardian, with no immediate attack · 1 AP"
            }
            Kind.LOOT -> "Collect container and ${Content.artifacts[t.index].name} · 1 AP"
            Kind.OBJECTIVE -> {
                if (m.isFinal && m.enemies.any { it.type == 8 && it.alive }) return bad("Archive locked. Disable the Warden")
                ap = if (m.objectiveType == 2) 2 else 1
                Content.objectives[m.objectiveType] + when (m.objectiveType) { 0 -> " · stage ${m.steps + 1} of 2"; 1 -> " · squad movement −1"; 3 -> " · disables security for two enemy phases"; else -> "" } + " · $ap AP"
            }
            Kind.ECHO -> "Recover ${Content.echoes[t.index].title}. +2 data and a service passage · 1 AP"
            Kind.DOOR -> "Open service door · 1 AP"
            Kind.EVENT -> {
                if (cmd.choice !in 0..1) return bad("Choose an event option")
                val e = Content.events[t.index]; val a = cmd.choice == 0
                oxygen = (m.oxygen + if (a) e.oxygenA else e.oxygenB).coerceIn(0, m.oxygenMax) - m.oxygen
                "${if (a) e.optionA else e.optionB} · O₂ ${signed(oxygen)} · materials ${signed(if (a) e.materialsA else e.materialsB)} · data ${signed(if (a) e.dataA else e.dataB)} · 1 AP"
            }
            else -> return bad("No interaction available")
        }
        return Preview(true, text + if (m.oxygen + oxygen <= 0) " · WARNING: emergency return!" else "", ap, oxygen = oxygen)
    }
    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
    fun execute(m: Mission, cmd: Command): List<Effect> {
        val p = preview(m, cmd); if (!p.valid) return listOf(Effect("invalid"))
        val effects = mutableListOf<Effect>(); val u = m.unit(cmd.actor); val t = m.at(cmd.target); val e = m.enemy(cmd.target)
        when (cmd.action) {
            Action.SOS -> { m.status = Status.EMERGENCY; m.note("Rescue drone returning the squad. New loot lost."); effects.add(Effect("emergency")) }
            Action.EXTRACT -> { m.status = Status.EXTRACTED; m.note("Airlock sealed. Squad and loot aboard."); effects.add(Effect("extract")) }
            Action.END -> endRound(m, effects)
            else -> {
                u!!; u.ap -= p.ap
                if (cmd.action in listOf(Action.MOVE, Action.ATTACK, Action.ABILITY) && u.cell != cmd.target) u.facing = facing(if (p.path.size > 1) p.path[p.path.lastIndex - 1] else u.cell, cmd.target)
                when (cmd.action) {
                    Action.MOVE -> { u.x = cmd.target.x; u.y = cmd.target.y; effects.add(Effect("move", u.cell)) }
                    Action.ATTACK -> { u.ammo--; if (e != null) e.hp = max(0, e.hp - p.damage) else t!!.hp = max(0, t.hp - p.damage); effects.add(Effect("shot", cmd.target, p.damage)) }
                    Action.RELOAD -> { u.ammo = u.maxAmmo; effects.add(Effect("reload")) }
                    Action.INTERACT -> { interact(m, t!!, cmd.choice); effects.add(Effect("interact", cmd.target)) }
                    Action.ABILITY -> { ability(m, u, cmd, p); if (cmd.slot == 0) u.ability0Ready = m.round + 3 else u.ability1Ready = m.round + 4; effects.add(Effect("ability", cmd.target, p.damage)) }
                    Action.SUPPLY -> { supply(m, cmd, p); m.supplies[cmd.slot]--; effects.add(Effect("supply", cmd.target, p.damage)) }
                    else -> Unit
                }
                m.note(p.text)
            }
        }
        if (m.status == Status.ACTIVE && (m.oxygen <= 0 || m.units.none { it.alive })) {
            m.oxygen = max(0, m.oxygen); m.status = Status.EMERGENCY; m.note("Squad unable to continue. Emergency return activated."); effects.add(Effect("emergency"))
        }
        Navigation.visibility(m); return effects
    }
    private fun interact(m: Mission, t: Tile, choice: Int) {
        t.used = true
        when (t.kind) {
            Kind.OXYGEN -> { m.oxygen = min(m.oxygenMax, m.oxygen + 3); m.enemies.filter { it.dormant }.forEach { it.dormant = false; it.aiming = false } }
            Kind.OBJECTIVE -> {
                m.steps++; m.objectiveComplete = m.objectiveType != 0 || m.steps >= 2; t.used = m.objectiveComplete
                if (m.objectiveComplete) { m.data += 3
                    if (m.objectiveType == 1) m.units.forEach { it.move = max(2, it.move - 1) }
                    if (m.objectiveType == 3) m.enemies.filter { it.alive }.forEach { it.disabledUntil = m.round + 2; it.aiming = false }
                }
            }
            Kind.LOOT -> {
                m.materials += 5 + m.roll(4); val a = Content.artifacts[t.index]; m.data += a.data; m.artifacts.add(t.index)
                m.units.forEach { val old = it.maxHp; val living = it.alive; Stations.stat(it, a.stat, a.value); if (a.stat == 0 && living) it.hp += it.maxHp - old }
            }
            Kind.ECHO -> { m.echoes.add(t.index); m.data += 2; m.tiles.filter { it.kind == Kind.DOOR }.forEach { it.used = true }; m.note(Content.echoes[t.index].text) }
            Kind.EVENT -> { val e = Content.events[t.index]; val a = choice == 0; m.oxygen = min(m.oxygenMax, m.oxygen + if (a) e.oxygenA else e.oxygenB); m.materials += if (a) e.materialsA else e.materialsB; m.data += if (a) e.dataA else e.dataB }
            else -> Unit
        }
    }
    private fun ability(m: Mission, u: UnitState, cmd: Command, p: Preview) {
        val e = m.enemy(cmd.target)
        when (u.role * 2 + cmd.slot) {
            0 -> m.units.filter { it.alive && u.cell.distance(it.cell) <= 1 }.forEach { it.shield = max(it.shield, 3) }
            1 -> {
                e!!; e.hp = max(0, e.hp - p.damage); val dx = e.x - u.x; val dy = e.y - u.y
                val pushed = e.cell + if (abs(dx) >= abs(dy)) Cell(dx.compareTo(0), 0) else Cell(0, dy.compareTo(0))
                if (Navigation.walkable(m, pushed) && !m.occupied(pushed)) { e.x = pushed.x; e.y = pushed.y }; e.aiming = false
            }
            2 -> { e!!.disabledUntil = m.round + 2; e.aiming = false }
            3 -> interact(m, m.at(cmd.target)!!, cmd.choice)
            4 -> u.scanUntil = m.round + 2
            5 -> { u.x = cmd.target.x; u.y = cmd.target.y }
            6 -> m.units.find { it.alive && it.cell == cmd.target }!!.let { it.hp = min(it.maxHp, it.hp + 4) }
            7 -> m.units.filter { it.alive && u.cell.distance(it.cell) <= 2 }.forEach { it.shield = max(it.shield, 3) }
        }
    }
    private fun supply(m: Mission, cmd: Command, p: Preview) {
        when (cmd.slot) {
            0 -> m.oxygen = min(m.oxygenMax, m.oxygen + 2)
            1 -> m.units.find { it.alive && it.cell == cmd.target }!!.let { it.hp = min(it.maxHp, it.hp + 4) }
            2 -> { m.enemies.filter { it.alive && !it.dormant && it.cell in p.area }.forEach { it.hp = max(0, it.hp - max(1, 4 - it.armor)) }; p.area.forEach { m.at(it)!!.let { t -> t.hp = max(0, t.hp - 4) } } }
            3 -> p.area.forEach { m.at(it)!!.smokeUntil = m.round + 2 }
            4 -> m.enemies.filter { it.alive && it.cell in p.area }.forEach { it.disabledUntil = m.round + 2; it.aiming = false }
            5 -> m.units.filter { it.alive }.forEach { it.scanUntil = m.round + 2 }
        }
    }
    private fun closest(m: Mission, e: Enemy) = m.units.filter { it.alive && e.cell.distance(it.cell) <= 7 && Navigation.line(m, e.cell, it.cell) }.minByOrNull { e.cell.distance(it.cell) }
    private fun seen(m: Mission, e: Enemy) = m.units.any { it.alive && it.cell.distance(e.cell) <= it.vision && Navigation.line(m, it.cell, e.cell) }
    private fun endRound(m: Mission, effects: MutableList<Effect>) {
        for (e in m.enemies) {
            if (!e.alive || e.dormant || e.disabledUntil > m.round) continue
            if (e.type == 5) {
                val patient = m.enemies.find { it !== e && it.alive && !it.dormant && it.hp < it.maxHp && it.cell.distance(e.cell) <= 3 }
                if (patient != null) { e.aiming = false; patient.hp = min(patient.maxHp, patient.hp + 2); continue }
            }
            if (e.aiming) {
                val center = Cell(e.aimX, e.aimY); val radius = if (e.type == 4 || e.type == 8) 1 else 0
                m.units.filter { it.alive && it.cell.distance(center) <= radius && Navigation.line(m, e.cell, it.cell) }.forEach { u ->
                    val damage = max(1, e.damage - u.armor - Navigation.cover(m, e.cell, u.cell)); val absorbed = min(u.shield, damage)
                    u.shield -= absorbed; u.hp = max(0, u.hp - damage + absorbed); effects.add(Effect("hit", u.cell, damage - absorbed))
                    m.note("${Content.enemies[e.type].name}: ${damage - absorbed} damage")
                }; e.aiming = false
            }
            var target = closest(m, e)
            if (target != null) { e.lastX = target.x; e.lastY = target.y }
            if (target != null && e.cell.distance(target.cell) <= e.range && seen(m, e)) { e.aimX = target.x; e.aimY = target.y; e.aiming = true }
            else if (e.lastX >= 0 && e.move > 0) {
                val goal = Cell(e.lastX, e.lastY)
                val path = Navigation.directions.map { Navigation.path(m, e.cell, goal + it) }.filter { it.isNotEmpty() }.minByOrNull { it.size }
                if (path != null) {
                    val step = path[min(e.move, path.size) - 1]; e.x = step.x; e.y = step.y; target = closest(m, e)
                    if (target != null && e.cell.distance(target.cell) <= e.range && seen(m, e)) { e.aimX = target.x; e.aimY = target.y; e.aiming = true }
                }
            }
            if (e.type == 8 && !e.bossPhase && e.hp <= e.maxHp / 2) { e.bossPhase = true; e.damage++; m.note("Warden emergency mode: damage +1.") }
        }
        m.oxygen = max(0, m.oxygen - 1); m.round++; m.units.forEach { it.ap = if (it.alive) 2 else 0 }
        m.note("Round ${m.round}. Oxygen: ${m.oxygen}."); effects.add(Effect("round"))
    }
}
