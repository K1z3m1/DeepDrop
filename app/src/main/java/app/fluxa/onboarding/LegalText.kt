package app.fluxa

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Legal copy shared by the About screen and the first-run consent screen, so both always say the
 * same thing. Strings are Thai; [I18n] translates them at runtime.
 */
object LegalText {
    val TERMS = listOf(
        "แอพนี้ให้ใช้งานตามสภาพที่เป็น (as is) ผู้พัฒนาไม่รับประกันว่าจะใช้ได้กับทุกเกมหรือทุกอุปกรณ์",
        "การปรับความละเอียด/DPI ผ่านสิทธิ์ WRITE_SECURE_SETTINGS เป็นการเปลี่ยนค่าของระบบ ผู้ใช้รับผิดชอบการใช้งานเอง และควรกด \"คืนค่าจอเดิม\" เมื่อเลิกใช้",
        "ใช้กับเกมหรือแอพที่คุณมีสิทธิ์ใช้งานเท่านั้น และไม่ใช้ในทางที่ขัดกับกติกาของเกมนั้น",
        "การเปิดใช้เฟรมเจนเนอเรชันเพิ่มภาระ GPU ความร้อน และการใช้แบตเตอรี่ ควรใช้ด้วยความระมัดระวัง",
        "ผู้พัฒนาไม่รับผิดชอบต่อความเสียหายหรือการถูกลงโทษจากเกม (เช่น ถูกแบนบัญชี) ที่เกิดจากการใช้แอพ",
    )

    val PRIVACY = listOf(
        "แอพขอสิทธิ์อินเทอร์เน็ตเพียงเพื่อวัดค่า ping (เชื่อมต่อไปที่ 8.8.8.8) และไม่ส่งหรือแชร์ข้อมูลส่วนตัวใด ๆ ออกจากเครื่องของคุณ",
        "ภาพหน้าจอที่จับถูกประมวลผลบนเครื่องเพื่อสร้างเฟรมเท่านั้น ไม่ถูกอัปโหลด",
        "บริการ Accessibility ใช้แสดง overlay ให้แตะทะลุไปที่เกมได้ และรู้ชื่อแอพที่เปิดอยู่เพื่อสรุปสถิติการเล่นในเครื่อง ไม่อ่านหรือเก็บเนื้อหาบนหน้าจอ",
        "ไมโครโฟนใช้เฉพาะเมื่อคุณเลือกอัดเสียงนอก และวิดีโอที่อัดถูกเก็บในโฟลเดอร์ Movies บนเครื่อง",
        "ข้อมูลที่เก็บในเครื่อง ได้แก่ การตั้งค่าของแอพ ค่าความละเอียดและ DPI เดิมของจอ (เพื่อใช้คืนค่า) และวิดีโอที่คุณอัด",
        "ไม่มีโฆษณา ไม่มีการติดตาม และไม่เก็บสถิติการใช้งาน",
        "แอพไม่เก็บบันทึกการทำงาน (log) ไม่มีหน้าดู log และไม่มีระบบส่งออกข้อมูลใด ๆ",
        "ลิงก์ติดต่อผู้พัฒนาจะเปิดในเบราว์เซอร์หรือแอพภายนอก ซึ่งอยู่ภายใต้นโยบายของผู้ให้บริการนั้น",
        "ลบข้อมูลทั้งหมดได้โดยล้างข้อมูลแอพหรือถอนการติดตั้ง ส่วนวิดีโอในโฟลเดอร์ Movies ต้องลบเอง",
        "ถอนความยินยอมได้ทุกเมื่อที่ เกี่ยวกับแอพ > ทบทวนการยินยอม และปิดสิทธิ์ต่าง ๆ ได้ในการตั้งค่าระบบของ Android",
    )

    val SOURCE = listOf(
        "ส่วนสร้างเฟรม DIS เป็น Vulkan compute ของ Dense Inverse Search optical flow โดย qwertypower (DEVAR Entertainment LLC) สัญญา GPL-3.0-or-later",
        "อัลกอริทึมมาจาก DISOpticalFlow ของ OpenCV ซึ่งรับมาจาก OF_DIS ต้นฉบับของ Till Kroeger",
        "hiddenapibypass โดย LSPosed (Apache-2.0)",
        "AndroidX Core KTX โดย Google (Apache-2.0)",
    )
}

/** Remembers whether the user accepted the current version of the terms + privacy policy. */
object Consent {
    /** Bump when the terms, privacy policy or requested permissions change, to ask everyone again. */
    const val VERSION = 1

    private const val K_VERSION = "consent_version"
    private const val K_TIME = "consent_time"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("fluxa", Context.MODE_PRIVATE)

    fun isAccepted(ctx: Context): Boolean = prefs(ctx).getInt(K_VERSION, 0) >= VERSION

    fun acceptedAt(ctx: Context): Long = prefs(ctx).getLong(K_TIME, 0L)

    fun accept(ctx: Context) {
        prefs(ctx).edit().putInt(K_VERSION, VERSION).putLong(K_TIME, System.currentTimeMillis()).apply()
    }

    fun withdraw(ctx: Context) {
        prefs(ctx).edit().remove(K_VERSION).remove(K_TIME).apply()
    }
}

/** Card-style popup with a title and paragraphs (terms, privacy, licences). */
fun showLegalSheet(activity: Activity, kit: Kit, title: String, paragraphs: List<String>) {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val card = LinearLayout(kit.ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = kit.rounded(Palette.PANEL_CARD, 24, Palette.PANEL_STROKE, 1)
        setPadding(kit.dp(22), kit.dp(22), kit.dp(22), kit.dp(16))
    }
    card.addView(kit.text(title, 18f, Palette.TEXT, bold = true))
    val inner = LinearLayout(kit.ctx).apply { orientation = LinearLayout.VERTICAL }
    paragraphs.forEachIndexed { i, t ->
        inner.addView(
            kit.text(t, 13.5f, Palette.MUTED).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(if (i == 0) 14 else 10) },
        )
    }
    card.addView(
        ScrollView(kit.ctx).apply { isVerticalScrollBarEnabled = false; addView(inner) },
        LinearLayout.LayoutParams(-1, -2),
    )
    card.addView(
        kit.text("ปิดหน้า", 14f, Palette.PRIMARY, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, kit.dp(14), 0, kit.dp(14))
            setOnClickListener { dialog.dismiss() }
        },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(10) },
    )
    dialog.setContentView(card)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(0))
        val w = (activity.resources.displayMetrics.widthPixels * 0.9f).toInt()
        setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    dialog.show()
}
