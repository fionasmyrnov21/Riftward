package com.riftwardechoprotocol.k7m4x9.core

import kotlin.math.abs

data class Cell(val x: Int = 0, val y: Int = 0) {
    fun distance(p: Cell) = abs(x - p.x) + abs(y - p.y)
    operator fun plus(p: Cell) = Cell(x + p.x, y + p.y)
}
enum class Kind { NONE, LOOT, OXYGEN, OBJECTIVE, ECHO, EVENT, DOOR }
enum class Action { MOVE, ATTACK, RELOAD, INTERACT, ABILITY, SUPPLY, END, EXTRACT, SOS }
enum class Status { ACTIVE, EXTRACTED, EMERGENCY }

class Tile(var x: Int = 0, var y: Int = 0) {
    var room = -1; var hp = 0; var maxHp = 0; var height = 0; var smokeUntil = 0
    var floor = false; var explored = false; var visible = false
    var kind = Kind.NONE; var index = 0; var used = false
    val cell get() = Cell(x, y)
}
class Hero(var id: Int = 0, var role: Int = 0, var name: String = "") {
    var xp = 0; var injury = 0; var weapon = 0; var armorMod = -1; var toolMod = -1
    var specialization = -1; var deployed = true
}
class UnitState(var id: Int = 0, var role: Int = 0, var x: Int = 0, var y: Int = 0) {
    var hp = 0; var maxHp = 0; var ap = 2; var ammo = 0; var maxAmmo = 0; var shield = 0
    var damage = 0; var armor = 0; var range = 0; var facing = 0; var move = 4; var vision = 6
    var scanUntil = 0; var ability0Ready = 0; var ability1Ready = 0
    val cell get() = Cell(x, y)
    val alive get() = hp > 0
}
class Enemy(var id: Int = 0, var type: Int = 0, var x: Int = 0, var y: Int = 0) {
    var hp = 0; var maxHp = 0; var damage = 0; var armor = 0; var range = 0; var move = 0
    var disabledUntil = 0; var aimX = 0; var aimY = 0; var lastX = -1; var lastY = -1
    var dormant = false; var aiming = false; var bossPhase = false
    val cell get() = Cell(x, y)
    val alive get() = hp > 0
}
class Offer {
    var seed = 0; var theme = 0; var objective = 0; var threat = 1; var reward = 10; var variant = 0
    var name = ""; var modifier = ""
}
class Mission {
    var id = ""; var rng = 1; var seed = 0; var theme = 0; var objectiveType = 0
    var width = 23; var height = 16; var round = 1; var oxygen = 12; var oxygenMax = 16
    var materials = 0; var data = 0; var dockX = 4; var dockY = 4; var reward = 10; var steps = 0
    var objectiveComplete = false; var settled = false; var tutorial = true; var isFinal = false
    var status = Status.ACTIVE
    var tiles: MutableList<Tile> = mutableListOf()
    var units: MutableList<UnitState> = mutableListOf()
    var enemies: MutableList<Enemy> = mutableListOf()
    var artifacts: MutableList<Int> = mutableListOf()
    var echoes: MutableList<Int> = mutableListOf()
    var log: MutableList<String> = mutableListOf()
    var supplies = IntArray(6); var templates = IntArray(6)
    fun at(x: Int, y: Int): Tile? = if (x in 0 until width && y in 0 until height) tiles[y * width + x] else null
    fun at(p: Cell) = at(p.x, p.y)
    fun unit(id: Int) = units.firstOrNull { it.id == id }
    fun enemy(p: Cell) = enemies.firstOrNull { it.alive && it.cell == p }
    fun occupied(p: Cell, except: Int = -1) = units.any { it.alive && it.id != except && it.cell == p } || enemy(p) != null
    fun note(s: String) { log.add(s); if (log.size > 60) log.removeAt(0) }
    fun roll(count: Int): Int { val r = Rng(rng); val n = r.range(count); rng = r.state; return n }
}
class Campaign {
    var materials = 16; var data = 0; var progress = 0; var attempts = 0; var selected = 0
    var supply0 = 0; var supply1 = 1; var ship = IntArray(4); var seed = 702914
    var tutorialSeen = false; var lastSettled = ""
    var heroes: MutableList<Hero> = mutableListOf()
    var echoes: MutableList<Int> = mutableListOf()
    var discoveries: MutableList<Int> = mutableListOf()
    var offers: MutableList<Offer> = mutableListOf()
}
class Settings { var music = .35f; var effects = .65f; var reducedMotion = false; var vibration = true }
class Session { var version = 2; var campaign = Campaign(); var mission: Mission? = null; var settings = Settings() }
data class Command(val action: Action, val actor: Int = 0, val target: Cell = Cell(), val slot: Int = 0, val choice: Int = 0)
data class Preview(val valid: Boolean, val text: String, val ap: Int = 0, val damage: Int = 0, val oxygen: Int = 0,
                   val path: List<Cell> = emptyList(), val area: List<Cell> = emptyList())
data class Effect(val kind: String, val cell: Cell = Cell(), val value: Int = 0)
class Rng(var state: Int) {
    fun next(): Int {
        if (state == 0) state = 2463534242L.toInt()
        var n = state; n = n xor (n shl 13); n = n xor (n ushr 17); n = n xor (n shl 5)
        state = n; return n
    }
    fun range(count: Int): Int = ((next().toLong() and 0xffffffffL) % count).toInt()
}

data class Weapon(val id: String, val name: String, val description: String, val damage: Int, val range: Int, val ammo: Int, val unlock: Int)
data class Mod(val id: String, val name: String, val description: String, val slot: Int, val stat: Int, val value: Int, val unlock: Int)
data class EnemyDef(val id: String, val name: String, val description: String, val hp: Int, val damage: Int, val armor: Int, val range: Int, val move: Int)
data class Artifact(val id: String, val name: String, val description: String, val stat: Int, val value: Int, val data: Int)
data class EventDef(val id: String, val name: String, val text: String, val optionA: String, val optionB: String, val oxygenA: Int, val oxygenB: Int, val materialsA: Int, val materialsB: Int, val dataA: Int, val dataB: Int)
data class Echo(val id: String, val title: String, val text: String, val sector: Int)
data class Room(val id: String, val name: String, val theme: Int, val layout: Int)
data class Role(val id: String, val name: String, val ability0: String, val ability1: String, val description0: String, val description1: String, val passive0: String, val passive1: String, val hp: Int, val weapon: Int)
data class Supply(val id: String, val name: String, val description: String)
