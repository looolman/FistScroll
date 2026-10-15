package com.fist.scroll

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var access: TextView
    private lateinit var startBtn: Button
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float = 16f, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.parseColor("#202124"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(CameraService.PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(root)
        }
        setContentView(scroll)

        root.addView(text("FistScroll", 28f, true))
        root.addView(text("Листай TikTok жестом, не касаясь экрана."))

        // ---- выбор жеста ----
        root.addView(text("Как будешь листать?", 18f, true))
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            Mode.FIST to "✊ Кулак — сожми руку перед камерой",
            Mode.TWO to "✌ Два пальца — покажи знак V",
            Mode.WAVE to "👋 Взмах — резко проведи рукой вверх",
            Mode.WINK to "😉 Подмигивание — закрой один глаз",
            Mode.BROW to "🤨 Брови — подними брови"
        )
        val saved = prefs.getString(CameraService.KEY_MODE, Mode.FIST)
        for ((mode, title) in options) {
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = title
                textSize = 16f
                tag = mode
                isChecked = mode == saved
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val mode = g.findViewById<RadioButton>(checkedId).tag as String
            prefs.edit().putString(CameraService.KEY_MODE, mode).apply()
        }
        root.addView(group)

        // ---- чувствительность ----
        root.addView(text("Чувствительность", 18f, true))
        val sensLabel = text("")
        val sens = SeekBar(this).apply {
            max = 9 // 0..9 => 1..10
            progress = prefs.getInt(CameraService.KEY_SENS, 5) - 1
        }
        sensLabel.text = "Сейчас: ${sens.progress + 1} из 10 (больше = легче срабатывает)"
        sens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.edit().putInt(CameraService.KEY_SENS, p + 1).apply()
                sensLabel.text = "Сейчас: ${p + 1} из 10 (больше = легче срабатывает)"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(sensLabel)
        root.addView(sens)

        // ---- автояркость ----
        val brightCheck = CheckBox(this).apply {
            text = "☀ Автояркость: если в комнате темно, камера и приложение усиливают картинку, " +
                "чтобы лучше видеть глаза и руки (в статусе появится ☀)"
            textSize = 15f
            isChecked = prefs.getBoolean(CameraService.KEY_BRIGHT, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CameraService.KEY_BRIGHT, on).apply()
            }
        }
        root.addView(brightCheck)

        // ---- экономный режим ----
        val ecoCheck = CheckBox(this).apply {
            text = "🔋 Экономный режим: реже смотрит в камеру, меньше нагрузка на телефон и батарею " +
                "(реакция чуть медленнее)"
            textSize = 15f
            isChecked = prefs.getBoolean(CameraService.KEY_ECO, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CameraService.KEY_ECO, on).apply()
            }
        }
        root.addView(ecoCheck)

        // ---- палец вверх = предыдущее видео ----
        root.addView(text("Дополнительный жест", 18f, true))
        val thumbCheck = CheckBox(this).apply {
            text = "👍 Палец вверх — предыдущее видео (свайп вниз по экрану). " +
                "Работает во всех режимах. Покажи большой палец вверх, остальные пальцы сожми, " +
                "и подержи около четверти секунды."
            textSize = 15f
            isChecked = prefs.getBoolean(CameraService.KEY_THUMB, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CameraService.KEY_THUMB, on).apply()
            }
        }
        root.addView(thumbCheck)

        // ---- подсказка поверх экрана ----
        root.addView(text("Подсказка «что видит камера»", 18f, true))
        val hintCheck = CheckBox(this).apply {
            text = "💬 Показывать маленькое окошко поверх TikTok: что сейчас видит камера " +
                "(рука, глаза, брови). Касания сквозь него проходят, TikTok не мешает. " +
                "Работает, когда включена служба спец. возможностей."
            textSize = 15f
            isChecked = prefs.getBoolean(CameraService.KEY_HINT, true)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CameraService.KEY_HINT, on).apply()
            }
        }
        root.addView(hintCheck)
        root.addView(text("Где показывать окошко:", 16f))
        val hintPlaces = listOf("Сверху слева", "Сверху справа", "Снизу слева", "Снизу справа")
        val hintSpinner = Spinner(this)
        hintSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, hintPlaces
        )
        hintSpinner.setSelection(prefs.getInt(CameraService.KEY_HINT_POS, 0).coerceIn(0, 3))
        hintSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt(CameraService.KEY_HINT_POS, pos).apply()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(hintSpinner)

        // ---- запуск приложений жестом ----
        root.addView(text("Запуск приложений жестом", 18f, true))
        val launchCheck = CheckBox(this).apply {
            text = "Открывать приложения жестом: ☝ один палец и ✌ два пальца. " +
                "Работает со всеми режимами, в том числе с подмиганием. " +
                "(В режиме «Два пальца» два пальца листают видео, поэтому открывает приложение только один палец.)"
            textSize = 15f
            isChecked = prefs.getBoolean(CameraService.KEY_LAUNCH, false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CameraService.KEY_LAUNCH, on).apply()
            }
        }
        root.addView(launchCheck)

        val pm = packageManager
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val items = listOf("— ничего —" to "") + apps

        fun picker(title: String, key: String, known: List<String>) {
            root.addView(text(title, 16f))
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, items.map { it.first }
            )
            var pkg = prefs.getString(key, null)
            if (pkg == null) {
                pkg = items.firstOrNull { it.second in known }?.second ?: ""
                prefs.edit().putString(key, pkg).apply()
            }
            spinner.setSelection(items.indexOfFirst { it.second == pkg }.coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    prefs.edit().putString(key, items[pos].second).apply()
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            root.addView(spinner)
        }
        picker(
            "☝ Один палец открывает:", CameraService.KEY_APP_ONE,
            listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
        )
        picker(
            "✌ Два пальца открывают:", CameraService.KEY_APP_TWO,
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go")
        )
        root.addView(text("Покажи палец(ы) и подержи около секунды (чем выше чувствительность, тем короче). " +
            "После запуска опусти руку, и жест можно повторить через 1,5 секунды. Если приложение не открывается: " +
            "Настройки → Приложения → FistScroll → Другие разрешения → " +
            "«Всплывающие окна в фоновом режиме» → Разрешить."))

        // ---- шаги настройки ----
        root.addView(text("Настройка", 18f, true))
        root.addView(button("1. Разрешить камеру") { askPermissions() })
        root.addView(button("2. Включить службу «специальных возможностей»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Найди FistScroll и включи его", Toast.LENGTH_LONG).show()
        })
        access = text("")
        root.addView(access)

        startBtn = button("3. Старт") { toggle() }
        root.addView(startBtn)

        root.addView(button("Проверить свайп (через 4 секунды)") {
            val svc = SwipeService.instance
            if (svc == null) {
                Toast.makeText(this, "Сначала включи службу (шаг 2)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Открой TikTok — свайп сейчас будет", Toast.LENGTH_SHORT).show()
                main.postDelayed({ SwipeService.instance?.swipeUp() }, 4000)
            }
        })

        status = text("Остановлено", 18f, true).apply { gravity = Gravity.CENTER }
        status.setPadding(0, dp(20), 0, dp(4))
        root.addView(status)
        root.addView(text("Для жестов рукой держи руку в 20–40 см от фронтальной камеры, чтобы вся ладонь была в кадре. " +
            "Для подмигивания лицо должно быть в кадре, закрывай один глаз на долю секунды, второй держи открытым. " +
            "Телефон лучше держать вертикально."))
    }

    override fun onResume() {
        super.onResume()
        CameraService.statusListener = { status.text = it; refresh() }
        status.text = CameraService.lastStatus
        refresh()
    }

    override fun onPause() {
        CameraService.statusListener = null
        super.onPause()
    }

    private fun refresh() {
        val on = SwipeService.instance != null
        access.text = if (on) "✅ Служба спец. возможностей включена" else "❌ Служба спец. возможностей выключена"
        access.setTextColor(Color.parseColor(if (on) "#1e7e34" else "#b3261e"))
        startBtn.text = if (CameraService.running) "3. Стоп" else "3. Старт"
    }

    private fun askPermissions() {
        val list = ArrayList<String>()
        list.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    private fun toggle() {
        if (CameraService.running) {
            startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
            main.postDelayed({ refresh() }, 300)
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            askPermissions()
            Toast.makeText(this, "Сначала разреши камеру", Toast.LENGTH_LONG).show()
            return
        }
        if (SwipeService.instance == null) {
            Toast.makeText(this, "Не забудь включить службу (шаг 2), иначе свайпов не будет", Toast.LENGTH_LONG).show()
        }
        val i = Intent(this, CameraService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        main.postDelayed({ refresh() }, 300)
    }
}
