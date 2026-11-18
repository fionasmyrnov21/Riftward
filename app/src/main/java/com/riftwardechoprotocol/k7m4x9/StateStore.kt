package com.riftwardechoprotocol.k7m4x9

import android.util.AtomicFile
import com.riftwardechoprotocol.k7m4x9.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.security.MessageDigest

/** JSON contains no type names: only the declared model field types can be instantiated. */
object StateCodec {
    fun encode(session: Session): String { validate(session); return write(session).toString() }
    fun decode(text: String): Session = (read(JSONObject(text), Session::class.java) as Session).also {
        validate(it)
        LegacyEnglish.migrate(it)
    }
    private fun fields(c: Class<*>) = c.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.sortedBy { it.name }
    private fun write(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean -> v
        is Enum<*> -> v.name
        is IntArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(write(it)) } }
        else -> JSONObject().also { o -> fields(v.javaClass).forEach { f -> f.isAccessible = true; o.put(f.name, write(f.get(v))) } }
    }
    private fun read(v: Any, type: Type): Any? {
        if (v === JSONObject.NULL) return null
        if (type is ParameterizedType) {
            val array = v as JSONArray; return MutableList(array.length()) { i -> read(array.get(i), type.actualTypeArguments[0]) }
        }
        val c = type as Class<*>
        if (c == String::class.java) return v.toString()
        if (c == Int::class.javaPrimitiveType || c == Int::class.javaObjectType) return (v as Number).toInt()
        if (c == Float::class.javaPrimitiveType || c == Float::class.javaObjectType) return (v as Number).toFloat()
        if (c == Boolean::class.javaPrimitiveType || c == Boolean::class.javaObjectType) return v as Boolean
        if (c == IntArray::class.java) { val array = v as JSONArray; return IntArray(array.length()) { array.getInt(it) } }
        if (c.isEnum) return c.enumConstants!!.first { (it as Enum<*>).name == v.toString() }
        val result = c.getDeclaredConstructor().newInstance(); val obj = v as JSONObject
        fields(c).forEach { f -> if (obj.has(f.name)) { f.isAccessible = true; f.set(result, read(obj.get(f.name), f.genericType)) } }
        return result
    }
    fun validate(s: Session) {
        require(s.version == 2)
        val p = s.campaign
        require(p.heroes.size == 4 && p.heroes.map { it.id }.toSet().size == 4 && p.ship.size == 4 && p.ship.all { it in 0..3 })
        require(p.materials >= 0 && p.data >= 0 && p.progress >= 0 && p.attempts >= 0 && p.offers.size == 3 && p.selected in 0..2 && p.supply0 in 0..5 && p.supply1 in 0..5)
        require(p.heroes.all { it.id in 0..3 && it.role in 0..3 && it.weapon in Content.weapons.indices && it.armorMod in -1 until Content.mods.size && it.toolMod in -1 until Content.mods.size && it.specialization in -1..1 && it.xp >= 0 })
        require(p.echoes.all { it in Content.echoes.indices } && p.discoveries.all { it in Content.mods.indices })
        val m = s.mission ?: return
        require(m.width in 1..128 && m.height in 1..128 && m.tiles.size == m.width * m.height && m.units.size == 3 && m.supplies.size == 6)
        require(m.round >= 1 && m.oxygen in 0..m.oxygenMax && m.theme in 0..2 && m.objectiveType in 0..3 && m.supplies.all { it >= 0 })
        require(m.units.all { it.id in 0..3 && it.role in 0..3 && it.hp in 0..it.maxHp && it.ap in 0..2 && it.ammo in 0..it.maxAmmo && it.facing in 0..3 && it.move > 0 && m.at(it.cell)?.floor == true })
        require(m.status != Status.ACTIVE || m.units.any { it.alive })
        m.tiles.forEachIndexed { i, t -> require(t.x == i % m.width && t.y == i / m.width && t.hp in 0..t.maxHp)
            require(when(t.kind) { Kind.LOOT -> t.index in Content.artifacts.indices; Kind.EVENT -> t.index in Content.events.indices; Kind.ECHO -> t.index in Content.echoes.indices; else -> true }) }
        require(m.enemies.all { it.type in Content.enemies.indices && it.hp in 0..it.maxHp && m.at(it.cell)?.floor == true })
    }
}

class StateStore(directory: File) {
    private val primary = AtomicFile(File(directory, "riftward-v2.save"))
    private val previous = AtomicFile(File(directory, "riftward-v2.previous"))
    var warning: String? = null; private set
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun wrap(s: Session): ByteArray {
        val payload = StateCodec.encode(s)
        return JSONObject().put("version", 2).put("sha256", hash(payload)).put("payload", payload).toString().toByteArray(Charsets.UTF_8)
    }
    private fun unwrap(bytes: ByteArray): Session {
        val obj = JSONObject(bytes.toString(Charsets.UTF_8)); val payload = obj.getString("payload")
        require(obj.getInt("version") == 2 && obj.getString("sha256") == hash(payload))
        return StateCodec.decode(payload)
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun load(): Session? {
        warning = null
        if (!primary.baseFile.exists() && !previous.baseFile.exists() && !File(primary.baseFile.path + ".bak").exists()) return null
        try { return unwrap(primary.readFully()) } catch (_: Exception) {
            try { val recovered = unwrap(previous.readFully()); warning = "Restored the previous checkpoint."; return recovered }
            catch (_: Exception) { warning = "Save unavailable. You can start a new campaign."; return null }
        }
    }
    fun save(session: Session) {
        val bytes = wrap(session)
        val good = runCatching { primary.readFully().also { unwrap(it) } }.getOrNull()
        if (good != null) write(previous, good)
        write(primary, bytes)
    }
}
