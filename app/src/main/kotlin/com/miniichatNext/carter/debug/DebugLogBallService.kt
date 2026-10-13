package com.miniichatNext.carter.debug

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView

/** 调试球信号：点击球后置位，MainActivity/AppRoot消费（弹应用内Dialog/导航）后复位。 */
object DebugNavRequest {
    /** 点击球→弹调试Dialog */
    val openPanel = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** 跳转Debug页 */
    val openDebug = kotlinx.coroutines.flow.MutableStateFlow(false)
}

/**
 * 调试球（系统级悬浮窗，TYPE_APPLICATION_OVERLAY）：
 * - 初始在顶栏右上角，可拖动；松手吸附最近水平边缘、垂直clamp在屏幕内；位置在球形态间保持
 * - 点击（未拖动）→拉起App并弹应用内MdDialog（折叠区块：日志等级/快速跳转）
 */
class DebugLogBallService : android.app.Service() {
    private lateinit var windowManager: WindowManager
    private var rootView: View? = null

    private var startX = 0
    private var startY = 0
    private var downRawX = 0f
    private var downRawY = 0f
    private var moved = false

    /** 球位置记忆（null=未初始化，用默认顶栏右上角）——弹窗/重建后不跳回初始位置 */
    private var ballX: Int? = null
    private var ballY: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        removeView()
        super.onDestroy()
    }

    private fun removeView() {
        rootView?.let { view -> runCatching { windowManager.removeView(view) } }
        rootView = null
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun screenWidth(): Int = resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = resources.displayMetrics.heightPixels

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun baseParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    )

    private fun chipBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(20).toFloat()
        }

    /** 球形态：位置用记忆值（首次=顶栏右上角） */
    @SuppressLint("ClickableViewAccessibility")
    private fun showBall() {
        val ball = TextView(this).apply {
            text = "Debug"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = chipBackground(Color.parseColor("#CC3A6FF0"))
        }
        val params = baseParams().apply {
            gravity = Gravity.TOP or Gravity.END
            x = ballX ?: dp(12)
            y = ballY ?: (statusBarHeight() + dp(4))
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (Math.abs(dx) + Math.abs(dy) > slop) moved = true
                    if (moved) {
                        val maxX = (screenWidth() - view.width - dp(4)).coerceAtLeast(0)
                        val maxY = (screenHeight() - view.height - dp(4)).coerceAtLeast(0)
                        params.x = (startX - dx.toInt()).coerceIn(0, maxX)
                        params.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        ballX = params.x
                        ballY = params.y
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        openDebugPanel()
                    } else {
                        snapToEdge(ball, params)
                    }
                    true
                }
                else -> false
            }
        }
        removeView()
        runCatching { windowManager.addView(ball, params) }
        rootView = ball
    }

    /** 松手吸附：水平贴最近边缘，垂直clamp在屏幕内（位置记忆同步更新） */
    private fun snapToEdge(view: View, params: WindowManager.LayoutParams) {
        val maxX = (screenWidth() - view.width - dp(4)).coerceAtLeast(0)
        val maxY = (screenHeight() - view.height - dp(4)).coerceAtLeast(0)
        params.x = if (params.x < maxX / 2) 0 else maxX
        params.y = params.y.coerceIn(0, maxY)
        ballX = params.x
        ballY = params.y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 点击球：拉起App弹应用内MdDialog（跨应用时切回App） */
    private fun openDebugPanel() {
        val intent = Intent(applicationContext, com.miniichatNext.carter.MainActivity::class.java)
            // 只用SINGLE_TOP：复用已有实例（onNewIntent），不清导航栈（CLEAR_TOP会重置回聊天页）
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_debug_panel", true)
        runCatching { startActivity(intent) }
    }

    companion object {
        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** 权限已授予时启动悬浮球（幂等） */
        fun start(context: Context) {
            if (!canDraw(context)) return
            runCatching {
                context.startService(Intent(context, DebugLogBallService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, DebugLogBallService::class.java))
            }
        }
    }
}
