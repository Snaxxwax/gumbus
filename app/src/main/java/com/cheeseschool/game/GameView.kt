package com.cheeseschool.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

class GameView(context: Context, private val host: Host) : View(context), GameEngine.Events {
    interface Host {
        fun requestMath(problem: MathProblem)
        fun speakCheese()
        fun onPlayerEscaped(escapeTimeSeconds: Float)
    }

    private data class Sprite(
        val position: Vec2,
        val bitmap: Bitmap,
        val scale: Float,
        val floorLift: Float = 0f
    )

    val engine = GameEngine(this)
    val hapticManager = HapticManager(context)

    var shortestEscapeTime: Float? = null
    var isNewRecord: Boolean = false

    private enum class ParticleShape {
        RIBBON, CIRCLE, STAR, DIAMOND
    }

    private class ConfettiParticle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        var width: Float,
        var height: Float,
        val color: Int,
        val shape: ParticleShape,
        var flutterOffset: Float,
        var flutterSpeed: Float,
        var life: Float = 0f,
        var maxLife: Float = 4.5f
    )

    private val confettiList = mutableListOf<ConfettiParticle>()
    private var confettiSpawnTimer = 0f
    private val starPath = Path()
    private val diamondPath = Path()
    private val confettiRandom = java.util.Random()
    private val confettiColors = intArrayOf(
        Color.rgb(255, 215, 0),   // Gold
        Color.rgb(255, 238, 88),  // Lemon
        Color.rgb(255, 112, 67),  // Coral Orange
        Color.rgb(236, 64, 122),  // Pink
        Color.rgb(171, 71, 188),  // Purple
        Color.rgb(41, 182, 246),  // Sky Blue
        Color.rgb(0, 229, 255),   // Cyan
        Color.rgb(102, 187, 106), // Emerald
        Color.rgb(255, 255, 255)  // Sparkle White
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val depth = FloatArray(1)
    private var depthBuffer = depth
    private var lastFrameAt = SystemClock.elapsedRealtimeNanos()
    private var appPaused = false

    // Screen Shake & Tension State
    private var shakeTime = 0f
    private var shakeTrauma = 0f
    private var currentShakeX = 0f
    private var currentShakeY = 0f
    private var currentShakeRot = 0f
    private var wrongMathFlashAlpha = 0f

    private val catBitmap = bitmap(R.drawable.cat_background_removed)
    private val notebookBitmap = bitmap(R.drawable.notebook)
    private val impossibleBitmap = bitmap(R.drawable.notebook_impossible)
    private val vendingBitmap = bitmap(R.drawable.vending_machine)
    private val exitLockedBitmap = bitmap(R.drawable.exit_locked)
    private val exitOpenBitmap = bitmap(R.drawable.exit_open)
    private val itemBitmaps = ItemType.entries.associateWith { bitmap(it.drawableId) }

    private var joystickPointer = -1
    private var lookPointer = -1
    private var runPointer = -1
    private var lookLastX = 0f
    private var joystickCenterX = 0f
    private var joystickCenterY = 0f
    private var joystickRadius = 0f

    private val startButton = RectF()
    private val restartButton = RectF()
    private val buyButton = RectF()
    private val inventoryRects = Array(3) { RectF() }
    private var runX = 0f
    private var runY = 0f
    private var runRadius = 0f

    init {
        isFocusable = true
        keepScreenOn = true
        contentDescription = "Cheese School game"
    }

    private fun bitmap(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = (now - lastFrameAt) / 1_000_000_000f
        lastFrameAt = now
        if (!appPaused) engine.update(dt)

        shakeTime += dt

        // 1. Proximity Shake when Cheese is close
        val proximityShakeX: Float
        val proximityShakeY: Float
        val proximityRotation: Float
        if (engine.phase == GamePhase.PLAYING && engine.danger > 0.03f) {
            val danger = engine.danger.coerceIn(0f, 1f)
            val dangerCurve = danger * danger
            val freq = 26f + danger * 24f
            val t = shakeTime * freq
            val maxProximityOffset = 16f * density
            proximityShakeX = (sin(t * 1.3f) * 0.7f + sin(t * 2.1f) * 0.3f) * dangerCurve * maxProximityOffset
            proximityShakeY = (cos(t * 1.1f) * 0.7f + cos(t * 2.7f) * 0.3f) * dangerCurve * (maxProximityOffset * 0.8f)
            proximityRotation = sin(t * 0.95f) * dangerCurve * 1.8f
        } else {
            proximityShakeX = 0f
            proximityShakeY = 0f
            proximityRotation = 0f
        }

        // 2. Trauma Shake (from wrong math answers or jumpscares)
        val traumaShakeX: Float
        val traumaShakeY: Float
        val traumaRotation: Float
        if (shakeTrauma > 0f) {
            val traumaCurve = shakeTrauma * shakeTrauma
            val maxTraumaOffset = 26f * density
            val t = shakeTime * 48f
            traumaShakeX = (sin(t * 1.7f) * 0.6f + (confettiRandom.nextFloat() - 0.5f) * 0.8f) * traumaCurve * maxTraumaOffset
            traumaShakeY = (cos(t * 1.9f) * 0.6f + (confettiRandom.nextFloat() - 0.5f) * 0.8f) * traumaCurve * (maxTraumaOffset * 0.85f)
            traumaRotation = (sin(t * 1.1f) * 0.6f + (confettiRandom.nextFloat() - 0.5f) * 0.4f) * traumaCurve * 3.5f

            shakeTrauma = (shakeTrauma - dt * 1.4f).coerceAtLeast(0f)
        } else {
            traumaShakeX = 0f
            traumaShakeY = 0f
            traumaRotation = 0f
        }

        if (wrongMathFlashAlpha > 0f) {
            wrongMathFlashAlpha = (wrongMathFlashAlpha - dt * 1.6f).coerceAtLeast(0f)
        }

        currentShakeX = proximityShakeX + traumaShakeX
        currentShakeY = proximityShakeY + traumaShakeY
        currentShakeRot = proximityRotation + traumaRotation

        drawWorld(canvas)
        drawHud(canvas)
        when (engine.phase) {
            GamePhase.INTRO -> drawIntro(canvas)
            GamePhase.CAUGHT -> drawEnd(canvas, "CHEESE CAUGHT YOU", false, dt)
            GamePhase.WON -> drawEnd(canvas, "YOU ESCAPED!", true, dt)
            else -> Unit
        }
        if (!appPaused) postInvalidateOnAnimation()
    }

    private fun drawWorld(canvas: Canvas) {
        val width = canvas.width
        val height = canvas.height
        val horizon = height * 0.50f

        val hasShake = currentShakeX != 0f || currentShakeY != 0f || currentShakeRot != 0f
        if (hasShake) {
            canvas.save()
            canvas.translate(currentShakeX, currentShakeY)
            canvas.rotate(currentShakeRot, width * 0.5f, height * 0.5f)
        }

        val pad = 36f * density
        paint.shader = null
        paint.color = Color.rgb(245, 233, 208)
        canvas.drawRect(-pad, -pad, width.toFloat() + pad, horizon, paint)
        paint.color = Color.rgb(190, 170, 125)
        canvas.drawRect(-pad, horizon, width.toFloat() + pad, height.toFloat() + pad, paint)

        if (depthBuffer.size != width) depthBuffer = FloatArray(width) { Float.POSITIVE_INFINITY }
        val columnWidth = max(2, (2f * density).toInt())
        var screenX = 0
        while (screenX < width) {
            val cameraX = 2f * (screenX + columnWidth * 0.5f) / width - 1f
            val rayAngle = engine.yaw + atan(cameraX * tan(GameEngine.FOV / 2f))
            val hit = castRay(rayAngle)
            val corrected = max(0.02f, hit.first * cos(rayAngle - engine.yaw))
            val wallHeight = min(height * 1.8f, height * 0.90f / corrected)
            val top = horizon - wallHeight * 0.5f
            val bottom = horizon + wallHeight * 0.5f
            val fog = (1f - corrected / 10f).coerceIn(0.22f, 1f)
            val base = if (hit.second) Color.rgb(174, 182, 194) else Color.rgb(197, 203, 212)
            paint.color = shade(base, fog)
            canvas.drawRect(screenX.toFloat(), top, min(width, screenX + columnWidth).toFloat(), bottom, paint)
            for (x in screenX until min(width, screenX + columnWidth)) depthBuffer[x] = corrected
            screenX += columnWidth
        }
        drawSprites(canvas, horizon)
        drawDanger(canvas)

        if (wrongMathFlashAlpha > 0f) {
            paint.color = Color.argb((wrongMathFlashAlpha * 170).toInt(), 235, 25, 25)
            canvas.drawRect(-pad, -pad, width.toFloat() + pad, height.toFloat() + pad, paint)
        }

        if (hasShake) {
            canvas.restore()
        }
    }

    /** Returns distance and whether the ray hit a north/south side. */
    private fun castRay(angle: Float): Pair<Float, Boolean> {
        val rayX = cos(angle)
        val rayY = sin(angle)
        var mapX = floor(engine.player.x).toInt()
        var mapY = floor(engine.player.y).toInt()
        val deltaX = if (abs(rayX) < 0.0001f) 1e6f else abs(1f / rayX)
        val deltaY = if (abs(rayY) < 0.0001f) 1e6f else abs(1f / rayY)
        val stepX: Int
        val stepY: Int
        var sideX: Float
        var sideY: Float
        if (rayX < 0f) {
            stepX = -1
            sideX = (engine.player.x - mapX) * deltaX
        } else {
            stepX = 1
            sideX = (mapX + 1f - engine.player.x) * deltaX
        }
        if (rayY < 0f) {
            stepY = -1
            sideY = (engine.player.y - mapY) * deltaY
        } else {
            stepY = 1
            sideY = (mapY + 1f - engine.player.y) * deltaY
        }
        var northSouth = false
        var distance = 12f
        repeat(64) {
            if (sideX < sideY) {
                mapX += stepX
                distance = sideX
                sideX += deltaX
                northSouth = false
            } else {
                mapY += stepY
                distance = sideY
                sideY += deltaY
                northSouth = true
            }
            if (mapY !in 0 until SchoolMap.ROWS || mapX !in 0 until SchoolMap.COLS ||
                engine.grid.getOrNull(mapY)?.getOrNull(mapX) == 1
            ) return Pair(distance, northSouth)
        }
        return Pair(distance, northSouth)
    }

    private fun drawSprites(canvas: Canvas, horizon: Float) {
        val sprites = mutableListOf<Sprite>()
        sprites += Sprite(engine.cheese, catBitmap, 0.85f)
        sprites += Sprite(engine.vending, vendingBitmap, 1.25f)
        engine.notebooks.filter { !it.collected }.forEach {
            sprites += Sprite(it.position, if (it.impossible) impossibleBitmap else notebookBitmap, 0.48f, 0.08f)
        }
        engine.pickups.filter { !it.collected }.forEach {
            sprites += Sprite(it.position, itemBitmaps.getValue(it.type), 0.48f, 0.08f)
        }
        engine.exits.forEach {
            sprites += Sprite(it, if (engine.escapeMode) exitOpenBitmap else exitLockedBitmap, 1.15f)
        }
        sprites.sortByDescending { it.position.distanceTo(engine.player) }
        sprites.forEach { drawSprite(canvas, horizon, it) }
    }

    private fun drawSprite(canvas: Canvas, horizon: Float, sprite: Sprite) {
        val dx = sprite.position.x - engine.player.x
        val dy = sprite.position.y - engine.player.y
        val distance = hypot(dx, dy)
        var angle = atan2(dy, dx) - engine.yaw
        val pi = PI.toFloat()
        while (angle > pi) angle -= 2f * pi
        while (angle < -pi) angle += 2f * pi
        if (abs(angle) > GameEngine.FOV * 0.68f || distance < 0.03f) return

        val corrected = distance * cos(angle)
        val centerX = width * 0.5f + tan(angle) * (width * 0.5f) / tan(GameEngine.FOV * 0.5f)
        if (centerX < -width * 0.5f || centerX > width * 1.5f) return
        val depthIndex = centerX.toInt().coerceIn(0, max(0, depthBuffer.lastIndex))
        if (corrected >= depthBuffer[depthIndex] + 0.08f) return

        val spriteHeight = min(height * 1.35f, height * 0.78f / max(0.08f, corrected) * sprite.scale)
        val aspect = sprite.bitmap.width.toFloat() / max(1, sprite.bitmap.height)
        val spriteWidth = spriteHeight * aspect
        val bottom = horizon + spriteHeight * (0.52f - sprite.floorLift)
        val destination = RectF(
            centerX - spriteWidth * 0.5f,
            bottom - spriteHeight,
            centerX + spriteWidth * 0.5f,
            bottom
        )
        paint.alpha = ((1f - distance / 15f).coerceIn(0.40f, 1f) * 255).toInt()
        canvas.drawBitmap(sprite.bitmap, null, destination, paint)
        paint.alpha = 255
    }

    private fun formatTime(seconds: Float): String {
        val totalSec = seconds.toInt()
        val mins = totalSec / 60
        val remSec = seconds - (mins * 60)
        return if (mins > 0) {
            String.format(java.util.Locale.US, "%d:%04.1fs", mins, remSec)
        } else {
            String.format(java.util.Locale.US, "%.1fs", seconds)
        }
    }

    private fun drawDanger(canvas: Canvas) {
        if (engine.danger <= 0f) return
        val radius = hypot(width.toFloat(), height.toFloat()) * 0.55f
        paint.shader = RadialGradient(
            width * 0.5f, height * 0.5f, radius,
            intArrayOf(Color.TRANSPARENT, Color.argb((100 * engine.danger).toInt(), 190, 0, 0)),
            floatArrayOf(0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawHud(canvas: Canvas) {
        val pad = 14f * density
        val boxWidth = min(width * 0.34f, 270f * density)
        val boxHeight = 122f * density
        paint.color = Color.argb(125, 0, 0, 0)
        canvas.drawRoundRect(pad, pad, pad + boxWidth, pad + boxHeight, 9f * density, 9f * density, paint)

        textPaint.textSize = min(17f * density, height * 0.034f)
        textPaint.color = Color.WHITE
        drawShadowText(canvas, "Notebooks: ${engine.score} / ${engine.totalNotebooks}", pad * 1.65f, pad * 2.0f)
        drawShadowText(canvas, "Coins: ${engine.coins}", pad * 1.65f, pad * 3.1f)
        drawShadowText(canvas, "Time: ${formatTime(engine.currentEscapeTime)}", pad * 1.65f, pad * 4.2f)
        textPaint.textSize *= 0.80f
        textPaint.color = Color.rgb(255, 234, 167)
        val objective = if (engine.escapeMode) "ESCAPE! Reach a green exit!" else "Collect every notebook."
        drawShadowText(canvas, objective, pad * 1.65f, pad * 5.25f)

        val staminaLeft = pad * 1.65f
        val staminaTop = pad * 5.8f
        val staminaWidth = boxWidth - pad * 1.25f
        paint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRoundRect(staminaLeft, staminaTop, staminaLeft + staminaWidth, staminaTop + 10f * density, 5f * density, 5f * density, paint)
        paint.color = when {
            engine.exhausted -> Color.rgb(231, 76, 60)
            engine.sprinting -> Color.rgb(241, 196, 15)
            else -> Color.rgb(46, 204, 113)
        }
        canvas.drawRoundRect(staminaLeft, staminaTop, staminaLeft + staminaWidth * engine.stamina, staminaTop + 10f * density, 5f * density, 5f * density, paint)

        if (engine.phase == GamePhase.PLAYING) {
            drawControls(canvas)
            if (engine.canBuy()) drawBuyPrompt(canvas)
        }
        if (engine.message.isNotEmpty()) {
            textPaint.textSize = min(18f * density, height * 0.039f)
            textPaint.color = Color.rgb(255, 234, 167)
            textPaint.textAlign = Paint.Align.CENTER
            drawShadowText(canvas, engine.message, width * 0.5f, 42f * density)
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawControls(canvas: Canvas) {
        joystickRadius = min(58f * density, height * 0.14f)
        joystickCenterX = 24f * density + joystickRadius
        joystickCenterY = height - 22f * density - joystickRadius
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(72, 255, 255, 255)
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawCircle(joystickCenterX, joystickCenterY, joystickRadius, paint)
        paint.style = Paint.Style.FILL
        val knobX = joystickCenterX + engine.joystickX * joystickRadius * 0.56f
        val knobY = joystickCenterY - engine.joystickY * joystickRadius * 0.56f
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawCircle(knobX, knobY, joystickRadius * 0.36f, paint)

        runRadius = min(43f * density, height * 0.105f)
        runX = width - 24f * density - runRadius
        runY = height - 72f * density - runRadius
        paint.color = if (engine.sprintHeld) Color.rgb(255, 196, 90) else Color.rgb(255, 159, 104)
        canvas.drawCircle(runX, runY, runRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        canvas.drawCircle(runX, runY, runRadius, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.rgb(35, 35, 35)
        textPaint.textSize = min(17f * density, height * 0.04f)
        canvas.drawText("RUN", runX, runY - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)

        val slotSize = min(54f * density, height * 0.13f)
        val gap = 9f * density
        val inventoryWidth = slotSize * 3 + gap * 2
        var left = width * 0.5f - inventoryWidth * 0.5f
        val top = height - slotSize - 16f * density
        repeat(3) { index ->
            val rect = inventoryRects[index]
            rect.set(left, top, left + slotSize, top + slotSize)
            paint.color = Color.argb(145, 0, 0, 0)
            canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
            val item = engine.inventory[index]
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.color = if (item == null) Color.argb(130, 255, 255, 255) else Color.rgb(251, 212, 109)
            canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
            paint.style = Paint.Style.FILL
            if (item != null) {
                val inset = 3f * density
                val imageRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
                canvas.drawBitmap(itemBitmaps.getValue(item), null, imageRect, paint)
            } else {
                textPaint.color = Color.WHITE
                textPaint.textSize = 14f * density
                canvas.drawText("${index + 1}", rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            }
            left += slotSize + gap
        }
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawBuyPrompt(canvas: Canvas) {
        val promptWidth = min(280f * density, width * 0.42f)
        val promptHeight = min(48f * density, height * 0.105f)
        buyButton.set(width * 0.5f - promptWidth * 0.5f, height * 0.65f, width * 0.5f + promptWidth * 0.5f, height * 0.65f + promptHeight)
        paint.color = Color.rgb(251, 212, 109)
        canvas.drawRoundRect(buyButton, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        canvas.drawRoundRect(buyButton, 8f * density, 8f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = min(16f * density, height * 0.038f)
        textPaint.color = Color.rgb(35, 35, 35)
        canvas.drawText("Buy ${engine.vendingStock.label} · 1 coin", buyButton.centerX(), buyButton.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawIntro(canvas: Canvas) {
        paint.color = Color.argb(244, 20, 20, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        textPaint.textSize = min(38f * density, height * 0.095f)
        drawShadowText(canvas, "CHEESE SCHOOL", width * 0.5f, height * 0.18f)
        textPaint.textSize = min(16f * density, height * 0.038f)
        textPaint.typeface = android.graphics.Typeface.DEFAULT
        val lines = listOf(
            "Find every notebook and solve its math problem.",
            "Each notebook makes Cheese faster. The third one is impossible.",
            "Move with the left stick · drag the right side to look · hold RUN.",
            "Tap an item slot to use it. Escape through a green exit."
        )
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, width * 0.5f, height * (0.30f + index * 0.068f), textPaint)
        }

        val best = shortestEscapeTime
        if (best != null) {
            textPaint.textSize = min(18f * density, height * 0.042f)
            textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textPaint.color = Color.rgb(255, 215, 0)
            drawShadowText(canvas, "🏆 Shortest Escape Time: ${formatTime(best)}", width * 0.5f, height * 0.62f)
        }

        val buttonWidth = min(230f * density, width * 0.40f)
        val buttonHeight = min(54f * density, height * 0.13f)
        val buttonTop = if (best != null) height * 0.71f else height * 0.66f
        startButton.set(width * 0.5f - buttonWidth * 0.5f, buttonTop, width * 0.5f + buttonWidth * 0.5f, buttonTop + buttonHeight)
        drawActionButton(canvas, startButton, "TAP TO START")
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun createConfettiParticle(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        rng: java.util.Random
    ): ConfettiParticle {
        val shape = ParticleShape.entries[rng.nextInt(ParticleShape.entries.size)]
        val color = confettiColors[rng.nextInt(confettiColors.size)]
        val baseSize = (7f + rng.nextFloat() * 8f) * density
        val width = if (shape == ParticleShape.RIBBON) baseSize * 1.5f else baseSize
        val height = if (shape == ParticleShape.RIBBON) baseSize * 0.7f else baseSize
        val rotSpeed = (rng.nextFloat() - 0.5f) * 720f
        val flutterSpeed = 4f + rng.nextFloat() * 6f
        val flutterOffset = rng.nextFloat() * 6.28f
        val maxLife = 3.5f + rng.nextFloat() * 2.5f
        return ConfettiParticle(
            x = x,
            y = y,
            vx = vx,
            vy = vy,
            rotation = rng.nextFloat() * 360f,
            rotationSpeed = rotSpeed,
            width = width,
            height = height,
            color = color,
            shape = shape,
            flutterOffset = flutterOffset,
            flutterSpeed = flutterSpeed,
            life = 0f,
            maxLife = maxLife
        )
    }

    fun triggerWinCelebration() {
        confettiList.clear()
        confettiSpawnTimer = 0f
        val w = if (width > 0) width.toFloat() else 800f * density
        val h = if (height > 0) height.toFloat() else 480f * density

        // Left cannon explosion (firing up and right)
        repeat(80) {
            val angle = -Math.PI.toFloat() * (0.12f + confettiRandom.nextFloat() * 0.38f)
            val speed = (280f + confettiRandom.nextFloat() * 520f) * density
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            confettiList.add(createConfettiParticle(0f, h * 0.88f, vx, vy, confettiRandom))
        }

        // Right cannon explosion (firing up and left)
        repeat(80) {
            val angle = -Math.PI.toFloat() * (0.50f + confettiRandom.nextFloat() * 0.38f)
            val speed = (280f + confettiRandom.nextFloat() * 520f) * density
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            confettiList.add(createConfettiParticle(w, h * 0.88f, vx, vy, confettiRandom))
        }

        // Center celebration fountain
        repeat(50) {
            val angle = -Math.PI.toFloat() * (0.30f + confettiRandom.nextFloat() * 0.40f)
            val speed = (220f + confettiRandom.nextFloat() * 420f) * density
            val vx = cos(angle) * speed
            val vy = sin(angle) * speed
            confettiList.add(createConfettiParticle(w * 0.5f, h * 0.70f, vx, vy, confettiRandom))
        }

        // Top cascade shower
        repeat(60) {
            val x = confettiRandom.nextFloat() * w
            val y = -confettiRandom.nextFloat() * (h * 0.6f)
            val vx = (confettiRandom.nextFloat() - 0.5f) * 140f * density
            val vy = (50f + confettiRandom.nextFloat() * 150f) * density
            confettiList.add(createConfettiParticle(x, y, vx, vy, confettiRandom))
        }
    }

    private fun updateAndDrawConfetti(canvas: Canvas, dt: Float) {
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        // Continuous celebratory shower while viewing the win screen
        confettiSpawnTimer += dt
        if (confettiSpawnTimer >= 0.045f) {
            confettiSpawnTimer = 0f
            repeat(3) {
                val x = confettiRandom.nextFloat() * w
                val y = -15f * density
                val vx = (confettiRandom.nextFloat() - 0.5f) * 90f * density
                val vy = (70f + confettiRandom.nextFloat() * 140f) * density
                confettiList.add(createConfettiParticle(x, y, vx, vy, confettiRandom))
            }
        }

        val iterator = confettiList.iterator()
        val gravity = 320f * density

        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life += dt
            if (p.life >= p.maxLife || p.y > h + 40f * density) {
                iterator.remove()
                continue
            }

            p.vy += gravity * dt
            p.vx *= (1f - 0.35f * dt)
            val flutterSway = sin(p.life * p.flutterSpeed + p.flutterOffset) * 45f * density
            p.x += (p.vx + flutterSway) * dt
            p.y += p.vy * dt
            p.rotation += p.rotationSpeed * dt

            val alphaFraction = if (p.life > p.maxLife - 0.6f) {
                ((p.maxLife - p.life) / 0.6f).coerceIn(0f, 1f)
            } else {
                1f
            }
            val alphaInt = (alphaFraction * 255).toInt()

            paint.color = p.color
            paint.alpha = alphaInt

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)

            when (p.shape) {
                ParticleShape.RIBBON -> {
                    val scaleX = cos(p.life * p.flutterSpeed + p.flutterOffset)
                    canvas.scale(scaleX, 1f)
                    canvas.drawRoundRect(
                        -p.width * 0.5f,
                        -p.height * 0.5f,
                        p.width * 0.5f,
                        p.height * 0.5f,
                        2f * density,
                        2f * density,
                        paint
                    )
                }
                ParticleShape.CIRCLE -> {
                    canvas.drawCircle(0f, 0f, p.width * 0.5f, paint)
                }
                ParticleShape.STAR -> {
                    drawStar(canvas, p.width * 0.65f, paint)
                }
                ParticleShape.DIAMOND -> {
                    drawDiamond(canvas, p.width * 0.5f, p.height * 0.8f, paint)
                }
            }
            canvas.restore()
        }
        paint.alpha = 255
    }

    private fun drawStar(canvas: Canvas, size: Float, starPaint: Paint) {
        starPath.reset()
        val rOuter = size
        val rInner = size * 0.42f
        for (i in 0 until 8) {
            val angle = (i * Math.PI / 4).toFloat() - (Math.PI / 2).toFloat()
            val r = if (i % 2 == 0) rOuter else rInner
            val px = cos(angle) * r
            val py = sin(angle) * r
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        canvas.drawPath(starPath, starPaint)
    }

    private fun drawDiamond(canvas: Canvas, w: Float, h: Float, diamondPaint: Paint) {
        diamondPath.reset()
        diamondPath.moveTo(0f, -h)
        diamondPath.lineTo(w, 0f)
        diamondPath.lineTo(0f, h)
        diamondPath.lineTo(-w, 0f)
        diamondPath.close()
        canvas.drawPath(diamondPath, diamondPaint)
    }

    private fun drawEnd(canvas: Canvas, title: String, won: Boolean, dt: Float = 0f) {
        paint.color = if (won) Color.argb(240, 10, 110, 40) else Color.argb(242, 25, 20, 20)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        if (won) {
            updateAndDrawConfetti(canvas, dt)
        }

        textPaint.textAlign = Paint.Align.CENTER

        val cardWidth = min(420f * density, width * 0.72f)
        val cardHeight = if (won) min(230f * density, height * 0.72f) else min(190f * density, height * 0.60f)
        val cardLeft = width * 0.5f - cardWidth * 0.5f
        val cardTop = height * 0.5f - cardHeight * 0.5f
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        paint.color = Color.argb(170, 0, 0, 0)
        canvas.drawRoundRect(cardRect, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = if (won) Color.rgb(255, 215, 0) else Color.rgb(220, 60, 60)
        canvas.drawRoundRect(cardRect, 16f * density, 16f * density, paint)
        paint.style = Paint.Style.FILL

        textPaint.textSize = min(36f * density, height * 0.09f)
        textPaint.color = if (won) Color.rgb(255, 240, 150) else Color.rgb(255, 75, 75)
        textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        drawShadowText(canvas, title, width * 0.5f, cardTop + 42f * density)

        if (won) {
            var currentY = cardTop + 72f * density
            if (isNewRecord) {
                textPaint.textSize = min(17f * density, height * 0.041f)
                textPaint.color = Color.rgb(255, 225, 50)
                drawShadowText(canvas, "★ NEW SHORTEST TIME RECORD! ★", width * 0.5f, currentY)
                currentY += 28f * density
            }

            textPaint.textSize = min(19f * density, height * 0.046f)
            textPaint.color = Color.WHITE
            drawShadowText(canvas, "Escape Time: ${formatTime(engine.finalEscapeTime)}", width * 0.5f, currentY)
            currentY += 28f * density

            val bestTime = shortestEscapeTime ?: engine.finalEscapeTime
            textPaint.textSize = min(17f * density, height * 0.042f)
            textPaint.color = Color.rgb(255, 215, 0)
            drawShadowText(canvas, "🏆 Shortest Escape Time: ${formatTime(bestTime)}", width * 0.5f, currentY)

            val buttonWidth = min(210f * density, width * 0.36f)
            val buttonHeight = min(50f * density, height * 0.12f)
            val buttonTop = cardRect.bottom - buttonHeight - 16f * density
            restartButton.set(width * 0.5f - buttonWidth * 0.5f, buttonTop, width * 0.5f + buttonWidth * 0.5f, buttonTop + buttonHeight)
            drawActionButton(canvas, restartButton, "PLAY AGAIN")
        } else {
            textPaint.textSize = min(18f * density, height * 0.044f)
            textPaint.color = Color.WHITE
            drawShadowText(canvas, "Notebooks: ${engine.score} / ${engine.totalNotebooks}", width * 0.5f, cardTop + 78f * density)

            val best = shortestEscapeTime
            if (best != null) {
                textPaint.textSize = min(16f * density, height * 0.039f)
                textPaint.color = Color.rgb(255, 215, 0)
                drawShadowText(canvas, "🏆 Shortest Escape Time: ${formatTime(best)}", width * 0.5f, cardTop + 106f * density)
            }

            val buttonWidth = min(210f * density, width * 0.36f)
            val buttonHeight = min(50f * density, height * 0.12f)
            val buttonTop = cardRect.bottom - buttonHeight - 16f * density
            restartButton.set(width * 0.5f - buttonWidth * 0.5f, buttonTop, width * 0.5f + buttonWidth * 0.5f, buttonTop + buttonHeight)
            drawActionButton(canvas, restartButton, "PLAY AGAIN")
        }

        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawActionButton(canvas: Canvas, rect: RectF, label: String) {
        paint.color = Color.rgb(251, 212, 109)
        canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = Color.BLACK
        canvas.drawRoundRect(rect, 9f * density, 9f * density, paint)
        paint.style = Paint.Style.FILL
        textPaint.color = Color.rgb(35, 35, 35)
        textPaint.textSize = min(19f * density, height * 0.047f)
        canvas.drawText(label, rect.centerX(), rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawShadowText(canvas: Canvas, text: String, x: Float, y: Float) {
        val original = textPaint.color
        textPaint.color = Color.BLACK
        canvas.drawText(text, x + 2f * density, y + 2f * density, textPaint)
        textPaint.color = original
        canvas.drawText(text, x, y, textPaint)
    }

    private fun shade(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt(),
            (Color.green(color) * factor).toInt(),
            (Color.blue(color) * factor).toInt()
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> handleDown(pointerId, event.getX(index), event.getY(index))
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) handleMove(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> handleUp(pointerId)
            MotionEvent.ACTION_CANCEL -> clearTouches()
        }
        return true
    }

    private fun handleDown(pointerId: Int, x: Float, y: Float) {
        when (engine.phase) {
            GamePhase.INTRO -> if (startButton.contains(x, y)) {
                performClick()
                confettiList.clear()
                isNewRecord = false
                engine.startGame()
            }
            GamePhase.CAUGHT, GamePhase.WON -> if (restartButton.contains(x, y)) {
                performClick()
                confettiList.clear()
                isNewRecord = false
                engine.startGame()
            }
            GamePhase.PLAYING -> {
                inventoryRects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { slot ->
                    performClick()
                    if (engine.inventory[slot] != null) {
                        engine.useItem(slot)
                    } else {
                        hapticManager.performWarningHaptic(this)
                    }
                    return
                }
                if (engine.canBuy() && buyButton.contains(x, y)) {
                    performClick()
                    engine.buyFromVending()
                    return
                }
                if (hypot(x - runX, y - runY) <= runRadius * 1.25f && runPointer == -1) {
                    runPointer = pointerId
                    engine.sprintHeld = true
                    return
                }
                if (x < width * 0.43f && y > height * 0.48f && joystickPointer == -1) {
                    joystickPointer = pointerId
                    updateJoystick(x, y)
                    return
                }
                if (lookPointer == -1) {
                    lookPointer = pointerId
                    lookLastX = x
                }
            }
            else -> Unit
        }
        invalidate()
    }

    private fun handleMove(pointerId: Int, x: Float, y: Float) {
        when (pointerId) {
            joystickPointer -> updateJoystick(x, y)
            lookPointer -> {
                val dx = x - lookLastX
                lookLastX = x
                engine.turn(dx * 0.0042f)
            }
        }
    }

    private fun updateJoystick(x: Float, y: Float) {
        var dx = (x - joystickCenterX) / joystickRadius
        var dy = (joystickCenterY - y) / joystickRadius
        val length = hypot(dx, dy)
        if (length > 1f) {
            dx /= length
            dy /= length
        }
        engine.joystickX = dx
        engine.joystickY = dy
    }

    private fun handleUp(pointerId: Int) {
        when (pointerId) {
            joystickPointer -> {
                joystickPointer = -1
                engine.joystickX = 0f
                engine.joystickY = 0f
            }
            lookPointer -> lookPointer = -1
            runPointer -> {
                runPointer = -1
                engine.sprintHeld = false
            }
        }
    }

    private fun clearTouches() {
        joystickPointer = -1
        lookPointer = -1
        runPointer = -1
        engine.joystickX = 0f
        engine.joystickY = 0f
        engine.sprintHeld = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun onHostPause() {
        appPaused = true
        clearTouches()
        engine.pauseForLifecycle()
    }

    fun onHostResume() {
        appPaused = false
        lastFrameAt = SystemClock.elapsedRealtimeNanos()
        postInvalidateOnAnimation()
    }

    override fun showMath(problem: MathProblem) {
        hapticManager.performMathQuestionHaptic(this)
        host.requestMath(problem)
    }

    override fun showMessage(message: String) = Unit
    override fun cheeseNearby() = host.speakCheese()
    override fun phaseChanged(phase: GamePhase) = invalidate()
    override fun onPlayerWon(escapeTimeSeconds: Float) {
        triggerWinCelebration()
        host.onPlayerEscaped(escapeTimeSeconds)
        invalidate()
    }

    fun triggerWrongMathShake() {
        shakeTrauma = 1.0f
        wrongMathFlashAlpha = 0.65f
        hapticManager.performMathWrongHaptic(this)
        postInvalidateOnAnimation()
    }

    override fun onItemUsed(item: ItemType) {
        hapticManager.performItemUseHaptic(this)
        invalidate()
    }

    override fun onItemPickedUp(item: ItemType) {
        hapticManager.performItemPickupHaptic(this)
        invalidate()
    }

    override fun onActionFailed() {
        hapticManager.performWarningHaptic(this)
        invalidate()
    }

    override fun onMathWrong() {
        triggerWrongMathShake()
        invalidate()
    }
}
