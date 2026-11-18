package com.riftwardechoprotocol.k7m4x9.core

import kotlin.math.min

object Campaigns {
    fun new(): Campaign = Campaign().also { p ->
        listOf("MARA VOSS", "ILYA KEST", "NOVA RAY", "ELIAN ZHAO").forEachIndexed { i, name ->
            p.heroes.add(Hero(i, i, name).also { it.weapon = Content.roles[i].weapon; it.deployed = i < 3 })
        }; offers(p)
    }
    fun offers(p: Campaign) {
        p.offers.clear(); val rng = Rng(p.seed)
        val names = listOf("PALLAS", "ORPHEUS", "NYX", "LAZARUS", "HELIOS", "ARK", "CHARON", "SELENE", "EREBUS")
        val modifiers = listOf("Spare oxygen cartridges", "Navigation archive fragments", "Secured cargo container")
        repeat(3) { i -> p.offers.add(Offer().also {
            it.seed = rng.next() and Int.MAX_VALUE; it.theme = if (p.progress >= 12) i else min(2, p.progress / 4)
            it.objective = (p.progress + i) % 4; it.threat = 1 + min(2, p.progress / 4) + if (i == 2) 1 else 0
            it.reward = 10 + i * 3; it.variant = i; it.name = names[(p.progress * 2 + i) % names.size] + " / " + (p.attempts + 1).toString().padStart(2, '0')
            it.modifier = modifiers[i]
        }) }; p.seed = rng.state; p.selected = 0
    }
    fun cost(p: Campaign, module: Int) = 12 + p.ship[module] * 10
    fun upgrade(p: Campaign, module: Int): Boolean {
        if (module !in 0..3 || p.ship[module] >= 3 || p.materials < cost(p, module)) return false
        p.materials -= cost(p, module); p.ship[module]++; return true
    }
    fun research(p: Campaign, index: Int): Boolean {
        val mod = Content.mods.getOrNull(index) ?: return false
        val cost = 4 + mod.unlock * 3
        if (index in p.discoveries || p.ship[1] < mod.unlock || p.data < cost) return false
        p.data -= cost; p.discoveries.add(index); return true
    }
    fun settle(p: Campaign, m: Mission): Boolean {
        if (m.status == Status.ACTIVE || m.settled || p.lastSettled == m.id) return false
        p.heroes.forEach { it.injury = 0 }
        if (m.status == Status.EXTRACTED) {
            p.materials += m.materials; p.data += m.data
            m.echoes.forEach { if (it !in p.echoes) p.echoes.add(it) }
            if (m.objectiveComplete) {
                val chapter = min(Content.echoes.lastIndex, p.progress)
                if (chapter !in p.echoes) p.echoes.add(chapter)
                p.progress++; p.materials += m.reward; p.data += 4
            }
        }
        m.units.forEach { u -> p.heroes.find { it.id == u.id }?.let { h ->
            if (m.status == Status.EXTRACTED) h.xp += if (m.objectiveComplete) 2 else 1
            if (m.status == Status.EMERGENCY || !u.alive) h.injury = if (p.ship[0] >= 2) 0 else 1
        } }
        m.settled = true; p.lastSettled = m.id; p.attempts++; p.tutorialSeen = true; offers(p); return true
    }
}
