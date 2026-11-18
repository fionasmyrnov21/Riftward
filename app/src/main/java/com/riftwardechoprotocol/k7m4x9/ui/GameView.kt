package com.riftwardechoprotocol.k7m4x9.ui

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import com.riftwardechoprotocol.k7m4x9.GameAudio
import com.riftwardechoprotocol.k7m4x9.StateStore
import com.riftwardechoprotocol.k7m4x9.core.*
import java.io.File
import kotlin.math.*

/** Android Canvas renderer; no game engine, WebView, network service or native binary. */
class GameView(context: Context, profileDirectory: File = context.filesDir) : View(context) {
    companion object {
        val BG = Color.rgb(8, 15, 21); val PANEL = Color.rgb(16, 29, 37); val EDGE = Color.rgb(42, 59, 67)
        val WHITE = Color.rgb(229, 236, 235); val MUTED = Color.rgb(139, 157, 165)
        val TEAL = Color.rgb(141, 206, 190); val AMBER = Color.rgb(233, 175, 104); val RED = Color.rgb(230, 126, 112)
    }
    private data class Hit(val rect: RectF, val text: String, val action: () -> Unit, val slider: Boolean = false)
    private data class Dialog(val title: String, val text: String, val options: List<Pair<String, () -> Unit>>)
    private data class TextKey(val text: String, val width: Int, val size: Int, val color: Int, val bold: Boolean)
    private data class Pick(val rect: RectF, val cell: Cell, val depth: Int)
    private data class Animation(val path: List<Cell>, val started: Long, val duration: Long)
    private data class Floating(val cell: Cell, val text: String, val color: Int, val started: Long)
    private val store = StateStore(profileDirectory)
    var session = store.load() ?: Session().also { it.campaign = Campaigns.new() }; private set
    private var started = store.load() != null
    private val audio = GameAudio(context)
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val font = Typeface.createFromAsset(context.assets, "fonts/NotoSans-Regular.ttf")
    private val boldFont = Typeface.create(font, Typeface.BOLD)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val layouts = object : android.util.LruCache<TextKey, StaticLayout>(150) {}
    private var canvas: Canvas? = null
    private val hits = mutableListOf<Hit>(); private val picks = mutableListOf<Pick>()
    private val animations = mutableMapOf<Int, Animation>(); private val floating = mutableListOf<Floating>()
    var screen = "menu"; private set
    private var dialog: Dialog? = null; private var dialogScroll = 0f; private var dialogScrollMax = 0f; private var dialogTextRect = RectF()
    private var selected = 0; private var mode = Action.MOVE; private var slot = 0; private var inspected: Cell? = null; private var pending: Command? = null
    private var archiveIndex = 0; private var archivePage = 0; private var researchPage = 0
    private var toast = store.warning ?: ""; private var toastUntil = if (toast.isNotEmpty()) SystemClock.uptimeMillis() + 10000 else 0
    private var running = true; private var scale = 1f; private var offsetX = 0f; private var offsetY = 0f
    private var canvasHeight = 1600f
    private var insetLeft = 0; private var insetTop = 0; private var insetRight = 0; private var insetBottom = 0
    private var boardX = 450f; private var boardY = 600f; private var zoom = 1.35f
    private val boardRect = RectF(0f, 350f, 900f, 990f)
    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var lastY = 0f; private var downTime = 0L
    private var dragging = false; private var pinching = false; private var pressed: Hit? = null; private var downInBoard = false; private var downInDialog = false
    private val campaign get() = session.campaign
    private val mission get() = session.mission
    private val now get() = SystemClock.uptimeMillis()

    private val gesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (screen != "mission" || dialog != null || !downInBoard) return false
            val x = (detector.focusX - offsetX) / scale; val y = (detector.focusY - offsetY) / scale
            val wx = (x - boardX) / zoom; val wy = (y - boardY) / zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(.7f, 2.2f); boardX = x - wx * zoom; boardY = y - wy * zoom
            pinching = true; invalidate(); return true
        }
    })
    init {
        isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
            insetLeft = safe.left; insetTop = safe.top; insetRight = safe.right; insetBottom = safe.bottom; invalidate(); insets
        }
        listOf("menu-station", "crew-portraits", "field-atlas", "crew-directions", "station-atlas", "artifact-atlas").forEach { name ->
            context.assets.open("$name.png").use { bitmaps[name] = BitmapFactory.decodeStream(it) }
        }
        audio.configure(session.settings); audio.scene(false)
    }
    fun startNewGame() {
        val settings = session.settings; session = Session().also { it.campaign = Campaigns.new(); it.settings = settings }
        started = true; save(); show("bridge")
    }
    fun save() {
        if (!started) return
        try { store.save(session) } catch (e: Exception) { Log.e("Riftward", "Saving failed", e); notify("Could not save progress. Check available storage.", 8) }
    }
    fun pauseGame() { save(); running = false; audio.pause() }
    fun resumeGame() { running = true; audio.resume(); invalidate() }
    fun closeGame() { audio.close(); bitmaps.values.forEach { it.recycle() }; bitmaps.clear() }
    fun back() {
        if (dialog != null) { dialog = null; invalidate(); return }
        when (screen) {
            "menu" -> Unit
            "bridge" -> show("menu")
            "mission" -> confirm("Return to the menu?", "Your mission will be saved. Oxygen is not consumed in menus.") { show("menu") }
            "debrief" -> finishDebrief()
            "research" -> show("workshop")
            else -> if (mission?.status == Status.ACTIVE) enterMission() else show(if(started) "bridge" else "menu")
        }
    }
    private fun notify(text: String, seconds: Int = 4) { toast = text; toastUntil = now + seconds * 1000; invalidate() }
    private fun show(next: String) {
        if (mission?.status == Status.ACTIVE && next !in listOf("menu", "mission", "settings", "debrief")) { notify("Finish the active mission, or resume it from the main menu."); return }
        save(); screen = next; dialog = null; pressed = null
        audio.scene(next == "mission" || next == "settings" && mission?.status == Status.ACTIVE, mission?.theme ?: 0)
        contentDescription = "Riftward. " + when(next) { "menu" -> "Main menu"; "mission" -> "Mission. Oxygen ${mission?.oxygen}"; else -> next }
        invalidate()
    }
    private fun deploy() {
        if (campaign.heroes.count { it.deployed } != 3) { notify("Select exactly three explorers to deploy"); return }
        session.mission = Stations.create(campaign, campaign.offers[campaign.selected]); selected = mission!!.units.first().id
        save(); enterMission()
    }
    private fun enterMission() {
        val m = mission ?: return
        if (m.status != Status.ACTIVE) { Campaigns.settle(campaign, m); save(); show("debrief"); return }
        mode = Action.MOVE; pending = null; inspected = null; animations.clear(); floating.clear(); zoom = 1.35f; focus(selected); show("mission")
        if (m.tutorial) {
            m.tutorial = false; save()
            popup("First Docking", "You have 12 oxygen. Each full round uses one.\n\nEach explorer has 2 action points (AP). Tap a tile or target, review the preview, then confirm.\n\nComplete the objective at ◆ and bring every active squad member back to the highlighted airlock zone.\n\nSOS rescues your squad but leaves all new loot behind. Drag to pan the camera; pinch to zoom.", listOf("OPEN AIRLOCK" to { dialog = null }))
        }
    }
    private fun focus(id: Int) { mission?.unit(id)?.let { boardX = boardRect.centerX() - (it.x - it.y) * 32f * zoom; boardY = boardRect.centerY() - (it.x + it.y) * 16f * zoom } }
    private fun finishDebrief() { session.mission = null; save(); show("bridge") }
    private fun prepare(cmd: Command) { pending = cmd; invalidate() }
    private fun execute(cmd: Command) {
        val m = mission ?: return; val preview = Rules.preview(m, cmd)
        if (!preview.valid) { notify(preview.text); return }
        val old = m.unit(cmd.actor)?.cell
        val effects = Rules.execute(m, cmd)
        if (old != null && preview.path.isNotEmpty()) animations[cmd.actor] = Animation(listOf(old) + preview.path, now, (preview.path.size * 55L).coerceIn(120, 650))
        effects.forEach { e -> audio.play(e.kind); if (e.value > 0) floating.add(Floating(e.cell, "${if (e.kind == "hit") "−" else ""}${e.value}", if (e.kind == "hit") RED else AMBER, now)) }
        if (effects.any { it.kind == "hit" } && session.settings.vibration) (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(25, 60))
        if (cmd.action == Action.END && m.oxygen <= 4) audio.play("warning")
        pending = null; inspected = null; mode = Action.MOVE; slot = 0
        if (m.status != Status.ACTIVE) { Campaigns.settle(campaign, m); save(); show("debrief") } else { save(); invalidate() }
    }
    private fun popup(title: String, text: String, options: List<Pair<String, () -> Unit>>) { dialog = Dialog(title, text, options); dialogScroll = 0f; invalidate() }
    private fun confirm(title: String, text: String, action: () -> Unit) = popup(title, text, listOf("CONFIRM" to { dialog = null; action() }, "CANCEL" to { dialog = null }))

    override fun onDraw(c: Canvas) {
        super.onDraw(c); canvas = c; hits.clear(); picks.clear(); c.drawColor(BG)
        val safeWidth = max(1, width - insetLeft - insetRight).toFloat()
        val safeHeight = max(1, height - insetTop - insetBottom).toFloat()
        scale = min(safeWidth / 900f, safeHeight / 1600f)
        canvasHeight = safeHeight / scale
        offsetX = insetLeft + (safeWidth - 900 * scale) / 2; offsetY = insetTop.toFloat()
        val oldCenter = boardRect.centerY()
        boardRect.set(0f, 350f, 900f, canvasHeight - 610f)
        boardY += boardRect.centerY() - oldCenter
        c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale)
        if (screen != "mission") background(if (screen == "menu") 255 else 65)
        when (screen) {
            "menu" -> menu(); "bridge" -> { frame(); bridge() }; "routes" -> { frame(); routes() }; "crew" -> { frame(); crew() }
            "workshop" -> { frame(); workshop() }; "research" -> { frame(); research() }; "archive" -> { frame(); archive() }
            "settings" -> { frame(); settings() }; "mission" -> { drawBoard(); missionHud() }; "debrief" -> debrief()
        }
        dialog?.let { drawDialog(it) }
        if (toast.isNotEmpty() && now < toastUntil) {
            box(24, 18, 852, 124, Color.rgb(36, 59, 65)); label(toast, 44, 34, 812, 96, 28)
        }
        c.restore(); canvas = null
        if (running) postInvalidateDelayed(33)
    }
    private fun r(x: Number, y: Number, w: Number, h: Number) = RectF(x.toFloat(), y.toFloat(), x.toFloat() + w.toFloat(), y.toFloat() + h.toFloat())
    private fun box(x: Number, y: Number, w: Number, h: Number, color: Int, alpha: Int = 255, radius: Float = 0f) {
        p.style = Paint.Style.FILL; p.color = color; p.alpha = alpha
        canvas!!.drawRoundRect(r(x, y, w, h), radius, radius, p); p.alpha = 255
    }
    private fun line(x: Number, y: Number, w: Number, h: Number = 1, color: Int = EDGE) = box(x, y, w, h, color)
    private fun layout(text: String, width: Float, size: Int, color: Int, bold: Boolean): StaticLayout {
        val key = TextKey(text, max(1, width.toInt()), size, color, bold)
        return layouts.get(key) ?: StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); this.color = color; typeface = if (bold) boldFont else font }, key.width)
            .setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setLineSpacing(3f, 1f).build().also { layouts.put(key, it) }
    }
    private fun label(text: String, x: Number, y: Number, w: Number, h: Number, size: Int = 20, color: Int = WHITE, bold: Boolean = false, centered: Boolean = false) {
        if (text.isEmpty()) return
        tp.textSize = size.toFloat(); tp.typeface = if (bold) boldFont else font; tp.color = color
        val width = w.toFloat(); val height = h.toFloat()
        canvas!!.save(); canvas!!.clipRect(r(x, y, w, h))
        if (!text.contains('\n') && tp.measureText(text) <= width) {
            val dx = x.toFloat() + if (centered) (width - tp.measureText(text)) / 2 else 0f
            val dy = y.toFloat() + if (centered) (height - tp.fontMetrics.descent - tp.fontMetrics.ascent) / 2 else -tp.fontMetrics.ascent
            canvas!!.drawText(text, dx, dy, tp)
        } else { val l = layout(text, width, size, color, bold); canvas!!.translate(x.toFloat(), y.toFloat()); l.draw(canvas!!) }
        canvas!!.restore()
    }
    private fun button(text: String, x: Number, y: Number, w: Number, h: Number, accent: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
        val rect = r(x, y, w, h); val active = pressed?.text == text
        box(x, y, w, h, if (accent) TEAL else if (active) Color.rgb(40, 65, 71) else PANEL, if (enabled) 255 else 105)
        line(x, rect.bottom - 1, w, 1, if (accent) TEAL else EDGE)
        label(text, rect.left + 12, rect.top + 2, rect.width() - 24, rect.height() - 4, if (text.length > 26) 26 else 30, if (accent) BG else if (enabled) WHITE else MUTED, true, true)
        if (enabled) hits.add(Hit(rect, text, action))
    }
    private fun image(name: String, dest: RectF, source: Rect? = null, alpha: Int = 255) {
        val bmp = bitmaps[name] ?: return; p.color = Color.WHITE; p.alpha = alpha; p.style = Paint.Style.FILL
        canvas!!.drawBitmap(bmp, source, dest, p); p.alpha = 255
    }
    private fun atlas(name: String, index: Int, columns: Int, rows: Int, dest: RectF, alpha: Int = 255) {
        val b = bitmaps[name] ?: return
        image(name, dest, Rect(index % columns * b.width / columns, index / columns * b.height / rows, (index % columns + 1) * b.width / columns, (index / columns + 1) * b.height / rows), alpha)
    }
    private fun portrait(role: Int, rect: RectF, alpha: Int = 255) {
        val b = bitmaps.getValue("crew-portraits"); val w = b.width / 4f; val h = min(b.height.toFloat(), w / (rect.width() / rect.height()))
        val y = (.28f * b.height - h / 2).coerceIn(0f, b.height - h)
        image("crew-portraits", rect, Rect((role * w).toInt(), y.toInt(), ((role + 1) * w).toInt(), (y + h).toInt()), alpha)
    }
    private fun background(alpha: Int) {
        val t = if (session.settings.reducedMotion) 0f else now / 1000f
        val bitmap = bitmaps.getValue("menu-station")
        val cropHeight = min(bitmap.height.toFloat(), bitmap.width * canvasHeight / 900f)
        val cropWidth = min(bitmap.width.toFloat(), cropHeight * 900f / canvasHeight)
        val center = bitmap.width * .50f + if (session.settings.reducedMotion) 0f else sin(t * .075f) * cropWidth * .02f
        val left = (center - cropWidth / 2).coerceIn(0f, bitmap.width - cropWidth)
        val top = (bitmap.height - cropHeight) / 2
        image("menu-station", r(0, 0, 900, canvasHeight), Rect(left.toInt(), top.toInt(), (left + cropWidth).toInt(), (top + cropHeight).toInt()), alpha)
        p.shader = LinearGradient(0f, 0f, 0f, canvasHeight, intArrayOf(Color.argb(155,8,15,21),Color.argb(20,8,15,21),Color.argb(245,8,15,21)),floatArrayOf(0f,.40f,1f),Shader.TileMode.CLAMP)
        canvas!!.drawRect(0f,0f,900f,canvasHeight,p);p.shader=null
        if (screen == "menu") repeat(22) { i ->
            val x = (i * 317.3f + if (session.settings.reducedMotion) 0f else t * .8f) % 900
            val y = (i * 223.7f + 80) % canvasHeight
            box(x,y,2,2,TEAL,65)
        }
    }
    private fun menu() {
        label("◇  EXPEDITION AUTHORITY",48,66,804,46,27,TEAL)
        label("RIFTWARD",42,180,820,136,108,WHITE,true)
        label("E C H O   P R O T O C O L",48,325,804,52,31,TEAL)
        line(48,canvasHeight-752,92,4,AMBER)
        label("WHAT REMAINS\nAFTER SILENCE",48,canvasHeight-708,804,156,54,WHITE,true)
        label("Abandoned stations. Missing crews.\nReturn with answers.",48,canvasHeight-526,804,124,34,MUTED)
        button(if(started)"CONTINUE EXPEDITION" else "BEGIN EXPEDITION",48,canvasHeight-370,804,116,true) {
            if(!started)startNewGame() else if(mission!=null)enterMission() else show("bridge")
        }
        if(started)button("NEW CAMPAIGN",48,canvasHeight-230,390,104) {
            confirm("Start over?","This will replace your current campaign. The previous checkpoint will remain in the backup."){startNewGame()}
        }
        button("SETTINGS",if(started)462 else 48,canvasHeight-230,if(started)390 else 804,104){show("settings")}
        label("OFFLINE  /  TURN-BASED TACTICS",48,canvasHeight-76,804,38,23,MUTED)
    }
    private fun frame() {
        label("RIFTWARD",24,22,600,64,45,WHITE,true)
        label("MATERIALS ${campaign.materials}   /   DATA ${campaign.data}",24,106,650,42,27,TEAL)
        button("MENU",696,24,180,96){show("menu")}
        line(24,166,852)
        val labels=listOf("BRIDGE","ROUTE","SQUAD","WORKSHOP","ARCHIVE","SETTINGS")
        val screens=listOf("bridge","routes","crew","workshop","archive","settings")
        box(0,canvasHeight-240,900,240,BG)
        labels.forEachIndexed{i,text->button(text,24+i%3*288,canvasHeight-228+i/3*112,276,100,
            screen==screens[i]||screen=="research"&&i==3,
            started&&mission?.status!=Status.ACTIVE||screens[i]=="settings"){show(screens[i])}}
    }
    private fun title(kicker:String,title:String,desc:String) {
        label(kicker,24,192,852,40,24,TEAL)
        label(title,24,244,852,72,48,WHITE,true)
        label(desc,24,326,852,100,30,MUTED)
    }
    private fun bridge() {
        title("COMMAND BRIDGE / SECTOR ${min(2,campaign.progress/4)+1}",if(campaign.progress>=12)"Voices on the channel"else"Silence is never empty","Assemble your squad. Choose a signal. Return with answers.")
        box(24,452,852,500,PANEL,245)
        label("CURRENT INVESTIGATION",48,480,804,42,25,TEAL)
        val e=if(campaign.progress>0)Content.echoes[min(11,campaign.progress-1)]else null
        label(e?.title?:"The Last Signal from Pallas",48,546,804,114,42,WHITE,true)
        label(e?.text?:"Three stations fell silent during a single shift. Automated dispatchers still accept cargo. The crew manifests are empty.\n\nFind the archive and return to the airlock.",48,666,804,260,31,MUTED)
        label("INVESTIGATION",24,984,610,42,25,MUTED)
        label("${min(12,campaign.progress).toString().padStart(2,'0')} / 12",640,974,236,76,47,TEAL,true)
        repeat(12){line(24+it*72,1064,60,7,if(it<campaign.progress)TEAL else EDGE)}
        label("DAYBREAK  /  3 EXPLORERS  /  SHARED OXYGEN",24,1110,852,80,27,MUTED)
        button("PREPARE SQUAD",24,canvasHeight-360,330,108){show("crew")}
        button("CHOOSE SIGNAL →",372,canvasHeight-360,504,108,true){show("routes")}
    }
    private fun routes() {
        title("NAVIGATION / SIGNALS","Where to next?","Choose a station. Its layout is revealed after docking.")
        campaign.offers.forEachIndexed{i,o->
            val y=440+i*256;val chosen=campaign.selected==i
            box(24,y,852,240,PANEL,248);line(24,y,852,4,if(chosen)TEAL else EDGE)
            val bitmap=bitmaps.getValue("station-atlas");val w=bitmap.width/3
            image("station-atlas",r(40,y+20,184,200),Rect(o.theme*w,0,(o.theme+1)*w,bitmap.height))
            label(o.name,244,y+14,608,51,36,WHITE,true)
            label(Content.themes[o.theme],244,y+71,608,34,23,TEAL)
            label(Content.objectives[o.objective],244,y+110,608,42,28)
            label("THREAT ${"▮".repeat(o.threat)}  /  ${o.reward} MATERIALS",244,y+158,608,40,24,AMBER)
            label(if(chosen)"SELECTED ✓"else"TAP TO SELECT",244,y+202,608,34,23,if(chosen)TEAL else MUTED)
            hits.add(Hit(r(24,y,852,240),"Signal ${i+1}",{campaign.selected=i;save();invalidate()}))
        }
        button("INTEL",24,canvasHeight-360,228,108){popup("Signal Intelligence",if(campaign.ship[2]>=2)campaign.offers[campaign.selected].modifier else "Scanner II reveals more intelligence about each signal.",emptyList())}
        button("PREPARE TO DEPLOY →",270,canvasHeight-360,606,108,true){show("crew")}
    }
    private fun crew() {
        title("PERSONNEL / ${campaign.heroes.count{it.deployed}} OF 3","Those who return home","Select three explorers. Injuries last for one mission.")
        campaign.heroes.forEachIndexed{i,h->
            val x=24+i%2*438;val y=430+i/2*342
            box(x,y,414,326,PANEL,248);portrait(h.role,r(x+14,y+14,126,176));line(x,y,414,4,if(h.deployed)TEAL else EDGE)
            label(h.name,x+156,y+20,242,80,31,WHITE,true)
            label("${Content.roles[h.role].name}\nLV. ${1+min(3,h.xp/3)}",x+156,y+108,242,78,24,TEAL)
            label(if(h.injury>0)"INJURED · −2 health"else"READY TO DEPLOY",x+16,y+190,382,32,23,if(h.injury>0)AMBER else MUTED)
            button(if(h.deployed)"ACTIVE ✓"else"RESERVE",x+12,y+230,192,88,h.deployed){h.deployed=!h.deployed;save();invalidate()}
            button("GEAR",x+214,y+230,188,88){equipment(h)}
        }
        button(Content.supplies[campaign.supply0].name,24,canvasHeight-484,414,100){campaign.supply0=(campaign.supply0+1)%6;save();invalidate()}
        button(Content.supplies[campaign.supply1].name,462,canvasHeight-484,414,100){campaign.supply1=(campaign.supply1+1)%6;save();invalidate()}
        button("DEPLOY / ${campaign.offers[campaign.selected].name}",24,canvasHeight-360,852,108,true,campaign.heroes.count{it.deployed}==3){deploy()}
    }
    private fun equipment(h: Hero) {
        val role=Content.roles[h.role];val weapon=Content.weapons[h.weapon]
        val options=mutableListOf<Pair<String,()->Unit>>()
        options.add("WEAPON: ${weapon.name}" to { val list=Content.weapons.indices.filter{Content.weapons[it].unlock<=campaign.ship[1]};h.weapon=list[(list.indexOf(h.weapon)+1)%list.size];save();equipment(h) })
        options.add("ARMOR: ${Content.mods.getOrNull(h.armorMod)?.name?:"STANDARD"}" to {h.armorMod=nextMod(h.armorMod,0);save();equipment(h)})
        options.add("TOOL: ${Content.mods.getOrNull(h.toolMod)?.name?:"STANDARD"}" to {h.toolMod=nextMod(h.toolMod,1);save();equipment(h)})
        if(h.specialization<0&&h.xp>=3){options.add(role.passive0 to {h.specialization=0;save();equipment(h)});options.add(role.passive1 to {h.specialization=1;save();equipment(h)})}
        popup(h.name,"${role.name} · XP ${h.xp}\n\n${weapon.description}\nDamage ${weapon.damage} / range ${weapon.range} / magazine ${weapon.ammo}\n\n${role.ability0}\n${role.description0}\n\n${role.ability1}\n${role.description1}\n\n"+(if(h.specialization>=0)"Specialization: "+if(h.specialization==0)role.passive0 else role.passive1 else "Specialization unlocks at 3 XP."),options)
    }
    private fun nextMod(current:Int,slot:Int):Int{val list=listOf(-1)+campaign.discoveries.filter{Content.mods[it].slot==slot};return list[(list.indexOf(current)+1)%list.size]}
    private fun workshop() {
        title("SHIP SYSTEMS / UPGRADES","Our way back","Upgrades survive failed missions. Basic expeditions are always free.")
        repeat(4){i->
            val x=24+i%2*438;val y=440+i/2*394
            box(x,y,414,378,PANEL,248);line(x,y,414,4,TEAL)
            label(Content.shipNames[i],x+20,y+24,374,88,34,WHITE,true)
            label("LEVEL ${campaign.ship[i]} / 3",x+20,y+100,374,36,25,TEAL)
            label(Content.shipDescriptions[i],x+20,y+144,374,126,26,MUTED)
            val cost=Campaigns.cost(campaign,i)
            button(if(campaign.ship[i]>=3)"MAX LEVEL"else"UPGRADE / $cost MAT.",x+16,y+282,382,88,false,campaign.ship[i]<3&&campaign.materials>=cost){Campaigns.upgrade(campaign,i);save();audio.play("upgrade");invalidate()}
        }
        button("RESEARCH MODIFICATIONS →",24,canvasHeight-360,852,108,true){show("research")}
    }
    private fun research() {
        title("LABORATORY / ${researchPage+1} OF 2","What we brought back","Research costs data. Equip unlocked modules for free in Gear.")
        repeat(6){j->
            val index=researchPage*6+j;val d=Content.mods[index];val x=24+j%2*438;val y=440+j/2*250
            box(x,y,414,232,PANEL)
            label(d.name,x+16,y+14,382,76,30,WHITE,true)
            label(d.description,x+16,y+96,382,40,26,MUTED)
            val owned=index in campaign.discoveries;val unlocked=campaign.ship[1]>=d.unlock;val cost=4+d.unlock*3
            button(if(owned)"UNLOCKED"else if(!unlocked)"WORKSHOP ${d.unlock}"else"UNLOCK / $cost DATA",x+12,y+142,390,82,owned,!owned&&unlocked&&campaign.data>=cost){Campaigns.research(campaign,index);save();invalidate()}
        }
        button("← UPGRADES",24,canvasHeight-360,330,108){show("workshop")}
        button(if(researchPage==0)"NEXT MODULES →"else"← PREVIOUS MODULES",372,canvasHeight-360,504,108){researchPage=1-researchPage;invalidate()}
    }
    private fun archive() {
        title("ECHO PROTOCOL / ${campaign.echoes.size} RECORDS","Voices beyond the wall","Core records unlock in order. Find more traces aboard stations.")
        if(campaign.echoes.isEmpty()){box(24,452,852,400,PANEL);label("The archive is empty.\nFind your first memory node.",48,520,804,240,40,MUTED);return}
        archiveIndex=archiveIndex.coerceIn(0,campaign.echoes.lastIndex);archivePage=archivePage.coerceIn(0,campaign.echoes.lastIndex/6)
        repeat(6){i->val idx=archivePage*6+i
            if(idx<campaign.echoes.size){val e=Content.echoes[campaign.echoes[idx]]
                button("${(campaign.echoes[idx]+1).toString().padStart(2,'0')} / ${e.title}",24+i%2*438,440+i/2*110,414,98,archiveIndex==idx){archiveIndex=idx;invalidate()}
            }
        }
        val e=Content.echoes[campaign.echoes[archiveIndex]]
        box(24,794,852,410,PANEL,248)
        label(e.title,48,818,804,110,40,WHITE,true)
        label(e.text,48,940,804,240,30)
        hits.add(Hit(r(24,794,852,410),"Read recovered fragment",{popup(e.title,e.text,emptyList())}))
        button("← PREVIOUS",24,canvasHeight-360,414,108){archivePage=max(0,archivePage-1);invalidate()}
        button("NEXT →",462,canvasHeight-360,414,108){archivePage=min(campaign.echoes.lastIndex/6,archivePage+1);invalidate()}
    }
    private fun slider(name:String,value:Float,x:Float,y:Float,w:Float,change:(Float)->Unit){
        label(name,x,y,w,48,30,MUTED);box(x,y+83,w,10,EDGE);box(x,y+83,w*value,10,TEAL);box(x+w*value-12,y+66,24,44,TEAL)
        hits.add(Hit(r(x,y+45,w,100),name,{change(((lastX-x)/w).coerceIn(0f,1f));audio.configure(session.settings)},true))
    }
    private fun settings() {
        title("SETTINGS / LOCAL PROFILE","Silence under control","Settings and progress stay on this device.")
        val s=session.settings
        slider("MUSIC",s.music,24f,448f,852f){s.music=it}
        slider("EFFECTS & AMBIENCE",s.effects,24f,626f,852f){s.effects=it}
        button("REDUCED MOTION: ${if(s.reducedMotion)"ON"else"OFF"}",24,818,852,108){s.reducedMotion=!s.reducedMotion;save();invalidate()}
        button("VIBRATION: ${if(s.vibration)"ON"else"OFF"}",24,944,852,108){s.vibration=!s.vibration;save();invalidate()}
        label("OFFLINE · LANGUAGE: ENGLISH\nSaved after every action.\nPlanning and reading do not consume oxygen.",24,1090,852,146,29,MUTED)
        if(mission?.status==Status.ACTIVE)button("RESUME MISSION",24,canvasHeight-360,852,108,true){enterMission()}
    }
    private fun debrief() {
        val m=mission?:return;val safe=m.status==Status.EXTRACTED;val success=safe&&m.objectiveComplete
        val color=if(safe)TEAL else RED
        label(if(success)"◇  MISSION COMPLETE"else if(safe)"◇  EARLY EXTRACTION"else"◇  MISSION FAILED",48,76,804,48,28,color)
        label(if(success)"You returned\nwith answers"else if(safe)"Lives before\nobjectives"else"Rescue protocol\ncomplete",48,192,804,224,64,WHITE,true)
        label(if(success)"Objective complete. Archive secured. The next signal is available."else if(safe)"Loot secured. Complete the objective on a later expedition."else"Loot was left behind. Your crew survived; the ship is ready to try again.",48,460,804,172,34,MUTED)
        val names=listOf("MATERIALS","DATA","OXYGEN ON RETURN")
        val values=listOf(if(safe)"+${m.materials+if(success)m.reward else 0}"else"0",if(safe)"+${m.data+if(success)4 else 0}"else"0",m.oxygen.toString().padStart(2,'0'))
        repeat(3){i->val y=680+i*148;box(48,y,804,128,PANEL,248);line(48,y,4,128,color)
            label(names[i],76,y+43,470,42,27,MUTED);label(values[i],596,y+20,228,92,62,if(i==1)color else WHITE,true)
        }
        label(if(success&&campaign.progress==12)Content.echoes[11].text else "Your ship and permanent gear are safe. Injuries last for one subsequent mission.",48,1156,804,200,30,MUTED)
        button("RETURN TO BRIDGE →",48,canvasHeight-180,804,116,true){finishDebrief()}
    }
    private fun point(cell:Cell):PointF=PointF(boardX+(cell.x-cell.y)*32*zoom,boardY+(cell.x+cell.y)*16*zoom)
    private fun logical(x:Float,y:Float):Cell {val a=(x-boardX)/(32*zoom);val b=(y-boardY)/(16*zoom);return Cell(((a+b)/2).roundToInt(),((b-a)/2).roundToInt())}
    private fun polygon(points:List<PointF>,color:Int,alpha:Int=255,stroke:Float=0f){
        path.rewind();points.forEachIndexed{i,v->if(i==0)path.moveTo(v.x,v.y)else path.lineTo(v.x,v.y)};path.close()
        p.color=color;p.alpha=alpha;p.style=if(stroke>0)Paint.Style.STROKE else Paint.Style.FILL;p.strokeWidth=stroke;canvas!!.drawPath(path,p);p.style=Paint.Style.FILL;p.alpha=255
    }
    private fun diamond(center:PointF,color:Int,alpha:Int=255,stroke:Float=0f,ratio:Float=1f){
        val w=32*zoom*ratio;val h=16*zoom*ratio;polygon(listOf(PointF(center.x-w,center.y),PointF(center.x,center.y-h),PointF(center.x+w,center.y),PointF(center.x,center.y+h)),color,alpha,stroke)
    }
    private fun cube(center:PointF,tall:Boolean=false,alpha:Int=255,tint:Int=0){
        val hw=24*zoom;val hh=12*zoom;val height=(if(tall)57 else 29)*zoom;val x=center.x;val y=center.y;val top=y-height
        polygon(listOf(PointF(x-hw,top),PointF(x,top+hh),PointF(x,y+hh),PointF(x-hw,y)),Color.rgb(50,72,82),alpha)
        polygon(listOf(PointF(x,top+hh),PointF(x+hw,top),PointF(x+hw,y),PointF(x,y+hh)),Color.rgb(31,51,63),alpha)
        polygon(listOf(PointF(x,top-hh),PointF(x+hw,top),PointF(x,top+hh),PointF(x-hw,top)),Color.rgb(101,124,129),alpha)
        polygon(listOf(PointF(x,top-hh),PointF(x+hw,top),PointF(x,top+hh),PointF(x-hw,top)),Color.rgb(148,166,168),alpha,1.1f)
        p.color=if(tint!=0)tint else if(tall)TEAL else AMBER;p.alpha=alpha;p.strokeWidth=2*zoom
        canvas!!.drawLine(x+4*zoom,top+hh+8*zoom,x+hw-5*zoom,top+8*zoom,p);p.alpha=255
    }
    private fun drawBoard(){
        val m=mission?:return;val c=canvas!!;c.save();c.clipRect(boardRect);box(boardRect.left,boardRect.top,boardRect.width(),boardRect.height(),BG)
        repeat(48){i->box((i*237)%900,boardRect.top+(i*113)%boardRect.height(),1,1,TEAL,35)}
        val theme=listOf(Color.rgb(38,57,70),Color.rgb(37,65,62),Color.rgb(43,53,78))[m.theme]
        m.tiles.filter{it.floor&&it.explored}.forEach { t->
            val pos=point(t.cell);diamond(PointF(pos.x,pos.y+5*zoom),Color.rgb(5,11,18));diamond(pos,theme,if(t.visible)255 else 80)
            diamond(pos,if(t.visible)Color.rgb(74,96,106) else EDGE,if(t.visible)130 else 60,1f)
            diamond(pos,if(t.visible)Color.rgb(55,80,90) else EDGE,if(t.visible)70 else 30,.65f,.85f)
            if(Navigation.dock(m,t.cell))diamond(pos,TEAL,if(m.oxygen<=4)230 else 160,2*zoom,.88f)
            if(t.smokeUntil>m.round)diamond(pos,Color.rgb(140,170,170),120)
        }
        for(e in m.enemies.filter{it.alive&&it.aiming&&!it.dormant&&it.disabledUntil<=m.round}){
            Rules.area(m,Cell(e.aimX,e.aimY),if(e.type==4||e.type==8)1 else 0).filter{m.at(it)?.visible==true}.forEach{diamond(point(it),RED,90);diamond(point(it),RED,230,2f,.92f)}
        }
        pending?.let{val preview=Rules.preview(m,it);if(preview.valid){preview.path.forEach{cell->diamond(point(cell),TEAL,110,0f,.74f)};preview.area.filter{cell->m.at(cell)?.visible==true}.forEach{cell->diamond(point(cell),AMBER,220,2f,.92f)}}}
        inspected?.let{m.at(it)?.takeIf{t->t.explored}?.let{diamond(point(it.cell),AMBER,230,2f)}}
        val pieces=mutableListOf<Pair<Float,()->Unit>>()
        val active=m.unit(selected)
        m.tiles.filter{it.floor&&it.explored}.forEach { t->
            val pos=point(t.cell)
            if(t.hp>0)pieces.add(pos.y to {cube(pos,t.height==2,if(t.height==2&&active!=null&&active.cell.distance(t.cell)<=2)95 else if(t.visible)255 else 90);if(t.hp<t.maxHp)label("${t.hp}",pos.x-16,pos.y-58*zoom,32,25,16,AMBER,true,true)})
            if(t.kind!=Kind.NONE&&(!t.used||t.kind in listOf(Kind.OXYGEN,Kind.OBJECTIVE,Kind.ECHO)))pieces.add((pos.y-1)to{drawObject(t,pos)})
        }
        for(e in m.enemies.filter{it.alive&&m.at(it.cell)?.visible==true})pieces.add(point(e.cell).y to {drawEnemy(e)})
        for(u in m.units.filter{it.alive})pieces.add(point(u.cell).y+.5f to {drawUnit(u)})
        pieces.sortedBy{it.first}.forEach{it.second()}
        floating.removeAll{now-it.started>850}
        floating.forEach{v->val pos=point(v.cell);val elapsed=(now-v.started)/850f;label(v.text,pos.x-40,pos.y-105*zoom-elapsed*35,80,45,30,v.color,true,true)}
        c.restore()
    }
    private fun drawObject(t:Tile,pos:PointF){
        val color=when(t.kind){Kind.OXYGEN->TEAL;Kind.OBJECTIVE->AMBER;Kind.ECHO->Color.rgb(188,165,213);else->MUTED}
        val alpha=if(t.used)70 else if(t.visible)255 else 95
        diamond(pos,color,alpha,1.5f,.7f)
        if(t.kind==Kind.LOOT&&!t.used){atlas("artifact-atlas",t.index,4,2,r(pos.x-25*zoom,pos.y-44*zoom,50*zoom,50*zoom),alpha);return}
        if(t.kind!=Kind.DOOR)cube(pos,false,alpha,color)
        val mark=when(t.kind){Kind.OXYGEN->"O₂";Kind.OBJECTIVE->"◆";Kind.ECHO->"E";Kind.EVENT->"?";Kind.DOOR->"Ⅱ";else->""}
        label(if(t.used)"✓"else mark,pos.x-30*zoom,pos.y-65*zoom,60*zoom,36*zoom,(24*zoom).toInt(),color,true,true)
    }
    private fun drawUnit(u:UnitState){
        var pos=point(u.cell);var direction=u.facing
        val anim=animations[u.id]
        if(anim!=null&&now-anim.started<anim.duration&&!session.settings.reducedMotion){
            val progress=(now-anim.started).toFloat()/anim.duration*(anim.path.size-1);val i=floor(progress).toInt().coerceIn(0,anim.path.size-2)
            val a=point(anim.path[i]);val b=point(anim.path[i+1]);val f=progress-i;pos=PointF(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f-sin(progress*PI).toFloat()*3*zoom);direction=Rules.facing(anim.path[i],anim.path[i+1])
        }
        val color=listOf(AMBER,TEAL,Color.rgb(178,196,147),Color.rgb(176,200,223))[u.role]
        diamond(point(u.cell),color,if(u.id==selected)240 else 150,if(u.id==selected)2.5f else 1.2f,.76f)
        val bob=if(session.settings.reducedMotion)0f else sin(now/1000f*1.7f+u.id)*.6f
        val rect=r(pos.x-49*zoom,pos.y-88*zoom+bob,98*zoom,98*zoom)
        atlas("crew-directions",u.role*4+direction,4,4,rect)
        picks.add(Pick(RectF(rect.left+rect.width()*.16f,rect.top+rect.height()*.04f,rect.right-rect.width()*.16f,rect.bottom),u.cell,(pos.y*10+5).toInt()))
        health(pos,u.hp,u.maxHp,color)
        label(if(u.ap==2)"● ●"else if(u.ap==1)"● ○"else"○ ○",pos.x-35*zoom,pos.y-116*zoom,70*zoom,24*zoom,(15*zoom).toInt(),color,true,true)
        if(u.shield>0)label("+${u.shield}",pos.x+26*zoom,pos.y-82*zoom,47*zoom,28*zoom,(17*zoom).toInt(),TEAL,true)
    }
    private fun drawEnemy(e:Enemy){
        val pos=point(e.cell);val color=if(e.dormant)MUTED else if(e.disabledUntil>mission!!.round)TEAL else RED
        diamond(pos,color,210,1.6f,.73f)
        val index=when(e.type){0->4;1->5;2->6;3->7;4->8;5->9;8->11;else->10};val size=(if(e.type==8)125 else 98)*zoom
        val rect=r(pos.x-size/2,pos.y-size+10*zoom,size,size);atlas("field-atlas",index,4,3,rect,if(e.dormant)125 else 255)
        picks.add(Pick(RectF(rect.left+size*.1f,rect.top,rect.right-size*.1f,rect.bottom),e.cell,(pos.y*10+4).toInt()))
        health(pos,e.hp,e.maxHp,color)
        val mark=if(e.dormant)"OFF"else if(e.disabledUntil>mission!!.round)"EMP"else if(e.aiming)"!"else""
        label(mark,pos.x-40*zoom,pos.y-121*zoom,80*zoom,29*zoom,(if(e.aiming)26*zoom else 15*zoom).toInt(),color,true,true)
    }
    private fun health(pos:PointF,hp:Int,max:Int,color:Int){box(pos.x-29*zoom,pos.y-93*zoom,58*zoom,5*zoom,BG);box(pos.x-28*zoom,pos.y-92*zoom,56*zoom*hp/max,3*zoom,color)}
    private fun inspection(): Pair<String,String> {
        val m=mission?:return "TACTICAL CHANNEL" to "Select an explorer and a tile. Drag to pan. Pinch to zoom."
        val t=inspected?.let{m.at(it)};val enemy=inspected?.let{m.enemy(it)}
        if(enemy!=null&&t?.visible==true)return Content.enemies[enemy.type].name to
            "HP ${enemy.hp}/${enemy.maxHp} · Damage ${enemy.damage}\n\n"+when {
                enemy.dormant->"Activates with the oxygen station. No immediate attack."
                enemy.disabledUntil>m.round->"Systems disabled by EMP."
                enemy.aiming->"Attacks the marked area next enemy phase."
                else->Content.enemies[enemy.type].description
            }
        if(t!=null)return when {
            t.kind==Kind.LOOT->Content.artifacts[t.index].name to Content.artifacts[t.index].description
            t.kind==Kind.OXYGEN->"OXYGEN STATION" to "Restores 3 oxygen. Activates the nearby guardian, with no immediate attack. Single use."
            t.kind==Kind.ECHO->Content.echoes[t.index].title to "Recover this Echo record to gain data and open a service passage."
            t.kind==Kind.OBJECTIVE->Content.objectives[m.objectiveType] to if(m.objectiveComplete)"Objective complete. Bring every active explorer back to the airlock to secure your rewards."else"Complete the objective, then return every active explorer to the airlock."
            t.kind==Kind.EVENT->Content.events[t.index].name to Content.events[t.index].text
            t.hp>0->(if(t.height==2)"HIGH PARTITION"else"LOW COVER") to "Durability ${t.hp}/${t.maxHp}. Can be destroyed by gunfire or grenades."
            else->"EXPLORED TILE" to "Select an action below. Review the preview before confirming."
        }
        return "TACTICAL CHANNEL" to "Tap a tile or target. Hold a tile to inspect it. Drag to pan the camera; pinch to zoom. Unknown threats are not included in the return estimate."
    }
    private fun missionHud() {
        val m=mission?:return
        if(m.unit(selected)?.alive!=true)selected=m.units.firstOrNull{it.alive}?.id?:0
        val u=m.unit(selected)?:return
        box(0,0,900,350,BG)
        label(Content.themes[m.theme],24,22,656,42,26,TEAL)
        label("ROUND ${m.round.toString().padStart(2,'0')} · LOG ›",24,80,500,46,30,MUTED)
        label("O₂",546,74,88,70,42,if(m.oxygen<=4)RED else TEAL)
        label(m.oxygen.toString().padStart(2,'0'),640,58,130,92,65,if(m.oxygen<=4)RED else WHITE,true)
        button("PAUSE",746,20,130,96){show("settings")}
        label(Content.objectives[m.objectiveType]+if(m.objectiveComplete)" / COMPLETE"else" / IN PROGRESS",24,148,852,68,29,if(m.objectiveComplete)TEAL else MUTED)
        m.units.forEachIndexed{i,ally->
            val x=24+i*288;val active=ally.id==selected
            box(x,224,276,112,if(active)Color.rgb(29,57,63)else PANEL)
            portrait(ally.role,r(x+10,236,72,76),if(ally.alive)255 else 80)
            label(Content.roles[ally.role].name,x+96,234,170,32,22,if(active)TEAL else MUTED)
            label("${ally.hp}/${ally.maxHp}",x+96,269,170,34,28,if(ally.alive)WHITE else RED,true)
            label(if(ally.alive)"AP ${ally.ap}/2"else"DOWN",x+96,307,170,28,21,if(ally.alive)MUTED else RED)
            if(ally.alive)hits.add(Hit(r(x,224,276,112),"Select ${Content.roles[ally.role].name}",{selected=ally.id;mode=Action.MOVE;pending=null;inspected=null;focus(selected);invalidate()}))
        }
        val rounds=Navigation.returnRounds(m)
        box(0,canvasHeight-610,900,610,BG)
        label("AIRLOCK: ${if(rounds<0)"UNKNOWN"else"$rounds ROUNDS"}",24,canvasHeight-603,510,42,26,if(m.oxygen<=rounds+2)RED else TEAL)
        label("MAT ${m.materials} / DATA ${m.data}",556,canvasHeight-603,320,42,24,MUTED)
        pending?.let{cmd->
            val preview=Rules.preview(m,cmd)
            box(24,canvasHeight-548,852,142,Color.rgb(18,39,46))
            val text=preview.text.replace('\n',' ').let{if(it.length>115)it.take(112)+"…"else it}
            label(text,42,canvasHeight-534,560,96,28,if(preview.valid)WHITE else AMBER)
            label("TAP PREVIEW FOR DETAILS",42,canvasHeight-436,560,30,20,MUTED)
            hits.add(Hit(r(24,canvasHeight-548,580,142),"Action preview",{popup("Action Preview",preview.text,emptyList())}))
            button("CONFIRM →",620,canvasHeight-530,238,106,true,preview.valid){execute(cmd)}
        }?:run {
            val info=inspection()
            label(info.first,24,canvasHeight-538,630,66,30,WHITE,true)
            label("MAG ${u.ammo}/${u.maxAmmo} · AP ${u.ap}/2",24,canvasHeight-462,630,42,26,MUTED)
            button("DETAILS",684,canvasHeight-536,192,106){popup(info.first,info.second,emptyList())}
        }
        val actions=listOf("MOVE","FIRE","INTERACT",Content.roles[u.role].ability0,Content.roles[u.role].ability1,"RELOAD")
        actions.forEachIndexed{i,text->
            val active=when(i){0->mode==Action.MOVE;1->mode==Action.ATTACK;2->mode==Action.INTERACT;3->mode==Action.ABILITY&&slot==0;4->mode==Action.ABILITY&&slot==1;else->false}
            button(text,24+i%3*288,canvasHeight-390+i/3*120,276,106,active){chooseAction(i)}
        }
        button("SUPPLIES",24,canvasHeight-140,204,112){supplies()}
        button("EXTRACT",240,canvasHeight-140,204,112){prepare(Command(Action.EXTRACT))}
        button("SOS",456,canvasHeight-140,156,112){prepare(Command(Action.SOS))}
        button("END ROUND",624,canvasHeight-140,252,112,true){prepare(Command(Action.END))}
        // The log remains accessible during planning and never spends oxygen.
        hits.add(Hit(r(24,64,400,80),"Field log",{popup("Field Log",m.log.takeLast(16).joinToString("\n\n"),emptyList())}))
    }
    private fun chooseAction(index:Int){
        val u=mission?.unit(selected)?:return;pending=null;slot=0
        mode=when(index){0->Action.MOVE;1->Action.ATTACK;2->Action.INTERACT;3,4->Action.ABILITY;else->Action.RELOAD}
        if(index==3||index==4)slot=index-3
        if(index==5){prepare(Command(Action.RELOAD,selected));return}
        if(mode==Action.ABILITY&&(u.role==0&&slot==0||u.role==2&&slot==0||u.role==3&&slot==1)){prepare(Command(mode,selected,u.cell,slot));return}
        invalidate();notify("Choose a target: "+if(mode==Action.ABILITY)if(slot==0)Content.roles[u.role].ability0 else Content.roles[u.role].ability1 else if(mode==Action.MOVE)"movement"else if(mode==Action.ATTACK)"shot"else"interaction")
    }
    private fun worldTap(x:Float,y:Float,inspect:Boolean){
        val m=mission?:return;val cell=picks.filter{it.rect.contains(x,y)}.maxByOrNull{it.depth}?.cell?:logical(x,y);val t=m.at(cell)?:return
        if(!t.explored)return;inspected=cell
        val ally=m.units.find{it.alive&&it.cell==cell}
        if(ally!=null&&mode==Action.MOVE&&!inspect){selected=ally.id;pending=null;invalidate();return}
        if(inspect){pending=null;val info=inspection();popup(info.first,info.second,emptyList());return}
        val action=if(mode==Action.MOVE&&(m.enemy(cell)!=null&&t.visible||t.hp>0))Action.ATTACK else if(mode==Action.MOVE&&t.kind!=Kind.NONE&&!t.used)Action.INTERACT else mode
        if(t.kind==Kind.EVENT&&!t.used&&(action==Action.INTERACT||action==Action.ABILITY&&m.unit(selected)?.role==1&&slot==1)){
            val e=Content.events[t.index];val a=Command(action,selected,cell,slot,0);val b=a.copy(choice=1)
            popup(e.name,"${e.text}\n\n${Rules.preview(m,a).text}\n\n${Rules.preview(m,b).text}",listOf(e.optionA to {dialog=null;prepare(a)},e.optionB to {dialog=null;prepare(b)}));return
        }
        prepare(Command(action,selected,cell,slot))
    }
    private fun supplies(){
        val m=mission?:return
        val options=Content.supplies.indices.filter{m.supplies[it]>0}.map{i->"${Content.supplies[i].name} ×${m.supplies[i]}" to {
            dialog=null;mode=Action.SUPPLY;slot=i
            if(i==0||i==5)prepare(Command(Action.SUPPLY,selected,m.unit(selected)!!.cell,i))else{pending=null;notify("Choose a target: ${Content.supplies[i].name}")}
        }}
        popup("Squad Supplies",Content.supplies.mapIndexed{i,s->"${s.name}: ${s.description}  [${m.supplies[i]}]"}.joinToString("\n\n"),options)
    }
    private fun drawDialog(d:Dialog) {
        hits.clear();box(0,0,900,canvasHeight,BG,240)
        val optionHeight=d.options.size*112f
        val textMax=min(650f,max(180f,canvasHeight-300-optionHeight))
        val content=layout(d.text,756f,30,MUTED,false)
        val textHeight=min(textMax,content.height.toFloat()+16)
        val height=(200+textHeight+optionHeight).coerceIn(420f,canvasHeight-80)
        val top=(canvasHeight-height)/2
        box(24,top,852,height,PANEL);line(24,top,852,4,TEAL)
        label(d.title,48,top+28,684,96,42,WHITE,true)
        button("×",756,top+18,96,96){dialog=null;invalidate()}
        dialogTextRect=r(48,top+142,756,textHeight)
        dialogScrollMax=max(0f,content.height-textHeight);dialogScroll=dialogScroll.coerceIn(0f,dialogScrollMax)
        canvas!!.save();canvas!!.clipRect(dialogTextRect)
        canvas!!.translate(dialogTextRect.left,dialogTextRect.top-dialogScroll);content.draw(canvas!!);canvas!!.restore()
        if(dialogScrollMax>0){val thumb=max(36f,textHeight*textHeight/content.height);box(846,dialogTextRect.top+(textHeight-thumb)*dialogScroll/dialogScrollMax,5,thumb,TEAL)}
        d.options.forEachIndexed{i,option->button(option.first,48,top+168+textHeight+i*112,804,100,i==d.options.lastIndex){option.second();invalidate()}}
    }
    override fun onTouchEvent(event:MotionEvent):Boolean{
        gesture.onTouchEvent(event)
        val x=(event.x-offsetX)/scale;val y=(event.y-offsetY)/scale
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{downX=x;downY=y;lastX=x;lastY=y;downTime=now;dragging=false;pinching=false;pressed=hits.lastOrNull{it.rect.contains(x,y)};downInBoard=screen=="mission"&&dialog==null&&pressed==null&&boardRect.contains(x,y);downInDialog=dialog!=null&&dialogTextRect.contains(x,y)&&pressed==null;invalidate()}
            MotionEvent.ACTION_MOVE->{
                if(pressed?.slider==true){lastX=x;lastY=y;pressed?.action?.invoke();dragging=true;invalidate()}
                else if(downInDialog){dialogScroll=(dialogScroll+lastY-y).coerceIn(0f,dialogScrollMax);dragging=true;invalidate()}
                else if(downInBoard&&!pinching&&hypot(x-downX,y-downY)>10){boardX+=x-lastX;boardY+=y-lastY;dragging=true;invalidate()}
                lastX=x;lastY=y
            }
            MotionEvent.ACTION_POINTER_DOWN->{pinching=true}
            MotionEvent.ACTION_UP->{
                lastX=x;lastY=y;val hit=pressed
                if(hit!=null&&hit.rect.contains(x,y)&&(!dragging||hit.slider)&&!pinching){audio.play("click");hit.action();if(hit.slider)save()}
                else if(downInBoard&&!dragging&&!pinching&&dialog==null&&boardRect.contains(x,y)&&hits.none{it.rect.contains(x,y)})worldTap(x,y,now-downTime>550)
                pressed=null;downInBoard=false;downInDialog=false;performClick();invalidate()
            }
            MotionEvent.ACTION_CANCEL->{pressed=null;downInBoard=false;downInDialog=false;invalidate()}
        }
        return true
    }
    override fun performClick():Boolean{super.performClick();return true}
}
