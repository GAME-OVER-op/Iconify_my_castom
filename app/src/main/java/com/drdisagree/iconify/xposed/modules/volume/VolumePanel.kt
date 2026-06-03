package com.drdisagree.iconify.xposed.modules.volume

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.drdisagree.iconify.data.common.Const.SYSTEMUI_PACKAGE
import com.drdisagree.iconify.data.keys.XposedKey
import com.drdisagree.iconify.xposed.ModPack
import com.drdisagree.iconify.xposed.modules.extras.utils.misc.ViewHelper.toPx
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.ResourceHookManager
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.XposedHook.Companion.findClass
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.callMethod
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.callMethodSilently
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.getField
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.getFieldSilently
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.hookConstructor
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.hookMethod
import com.drdisagree.iconify.xposed.modules.extras.utils.toolkit.setField
import com.drdisagree.iconify.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlin.math.ceil
import kotlin.math.roundToInt

@SuppressLint("DiscouragedApi", "DefaultLocale")
class VolumePanel(context: Context) : ModPack(context) {

    private var showPercentage = false
    private var showWarning = true
    private var showAppVolume = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appVolumeSources = linkedMapOf<String, AppVolumeSource>()
    private var appVolumeButtonView: View? = null
    private var appVolumeSheetView: View? = null
    private var playbackCallbackRegistered = false

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            showPercentage = getBoolean(XposedKey.VOLUME_PANEL_PERCENTAGE)
            showWarning = getBoolean(XposedKey.VOLUME_PANEL_SAFETY_WARNING)
            showAppVolume = getBoolean(XposedKey.VOLUME_PANEL_APP_VOLUME)
        }

        if (showAppVolume) {
            mainHandler.post {
                registerPlaybackCallback()
                refreshPlaybackSources()
                updateFloatingPerAppVolumeOverlay()
            }
        } else {
            appVolumeSources.clear()
            dismissFloatingPerAppVolumeOverlay()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        showVolumePercentage()
        showSafetyWarning()
        initPerAppVolume()
    }

    private fun showVolumePercentage() {
        val volumeDialogImplClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.VolumeDialogImpl",
            suppressError = true
        )
        val audioStreamStateClass =
            findClass($$"$$SYSTEMUI_PACKAGE.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel$State")
        val audioStreamToStateClass = findClass(
            $$"$$SYSTEMUI_PACKAGE.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel$toState$1",
            $$"$$SYSTEMUI_PACKAGE.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel$toState$2",
            suppressError = true
        )

        volumeDialogImplClass
            .hookMethod("initRow")
            .runAfter { param ->
                if (!showPercentage) return@runAfter

                val rowHeader: TextView = param.args[0].getField("header") as TextView

                if ((rowHeader.parent as ViewGroup).findViewById<TextView>(
                        mContext.resources.getIdentifier(
                            "volume_number",
                            "id",
                            mContext.packageName
                        )
                    ) != null
                ) return@runAfter

                val volumeNumber = createVolumeTextView()
                (rowHeader.parent as ViewGroup).addView(volumeNumber, 0)

                param.args[0].setField(
                    "number",
                    (param.args[0].getField("view") as View).findViewById(
                        mContext.resources.getIdentifier(
                            "volume_number",
                            "id",
                            mContext.packageName
                        )
                    )
                )
            }

        volumeDialogImplClass
            .hookMethod("updateVolumeRowH")
            .runAfter { param ->
                if (!showPercentage) return@runAfter

                val volumeNumber: TextView =
                    (param.args[0].getField("view") as View).findViewById(
                        mContext.resources.getIdentifier(
                            "volume_number",
                            "id",
                            mContext.packageName
                        )
                    ) ?: return@runAfter

                val mState: Any = param.thisObject.getFieldSilently("mState") ?: return@runAfter

                val ss = mState
                    .getField("states")
                    .callMethod(
                        "get",
                        param.args[0].getField("stream")
                    ) ?: return@runAfter

                val levelMax: Int = ss.getField("levelMax") as Int

                volumeNumber.let {
                    if (it.text.isEmpty()) {
                        it.text = "0"
                    }

                    if (it.text.contains("%")) {
                        it.text = it.text.subSequence(0, it.text.length - 1)
                    }

                    var level = ceil(it.text.toString().toFloat() / levelMax * 100f).toInt()

                    if (level > 100) {
                        level = 100
                    } else if (level < 0) {
                        level = 0
                    }

                    it.text = String.format("%d%%", level)
                }
            }

        // Compose implementation of extended volume panel
        fun updateVolumeLabel(thisObject: Any) {
            val currentValue = thisObject.getField("value") as Float
            val maxValue = thisObject
                .getField("valueRange")
                .getField("_endInclusive") as Float
            val percentage = 100 * currentValue / maxValue
            var label = thisObject.getField("label") as String
            label = String.format("$label - ${percentage.roundToInt()}%%")

            thisObject.setField("label", label)
        }

        audioStreamStateClass
            .hookConstructor()
            .runAfter { param ->
                if (!showPercentage) return@runAfter

                updateVolumeLabel(param.thisObject)
            }

        audioStreamToStateClass
            .hookMethod("invokeSuspend")
            .runAfter { param ->
                if (!showPercentage) return@runAfter

                val state = param.result
                updateVolumeLabel(state)
                param.result = state
            }
    }

    private fun showSafetyWarning() {
        val volumeDialogImplClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.VolumeDialogImpl",
            suppressError = true
        )

        if (volumeDialogImplClass == null) {
            ResourceHookManager
                .hookBoolean()
                .whenCondition { !showWarning }
                .forPackageName(SYSTEMUI_PACKAGE)
                .addResource("enable_safety_warning") { false }
                .apply()
        } else {
            try {
                volumeDialogImplClass
                    .hookMethod(
                        "onShowSafetyWarning",
                        "showSafetyWarningH"
                    )
                    .throwError()
                    .runBefore { param ->
                        if (!showWarning) {
                            param.result = null
                        }
                    }
            } catch (_: Throwable) {
                volumeDialogImplClass
                    .hookConstructor()
                    .runAfter { param ->
                        if (showWarning) return@runAfter

                        val mControllerCallbackH = param.thisObject.getField("mControllerCallbackH")

                        mControllerCallbackH.javaClass
                            .hookMethod("onShowSafetyWarning")
                            .runBefore { param ->
                                if (!showWarning) {
                                    param.result = null
                                }
                            }
                    }
            }
        }
    }

    private fun initPerAppVolume() {
        if (showAppVolume) {
            mainHandler.post {
                registerPlaybackCallback()
                refreshPlaybackSources()
                updateFloatingPerAppVolumeOverlay()
            }
        }
    }

    private fun registerPlaybackCallback() {
        if (playbackCallbackRegistered) return

        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        runCatching {
            audioManager.registerAudioPlaybackCallback(
                object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                        updatePlaybackSources(configs)
                    }
                },
                mainHandler
            )

            playbackCallbackRegistered = true
            refreshPlaybackSources()
        }
    }

    private fun refreshPlaybackSources() {
        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val currentConfigs = runCatching {
            audioManager.callMethodSilently("getActivePlaybackConfigurations") as? List<*>
        }.getOrNull()

        updatePlaybackSources(
            currentConfigs?.filterIsInstance<AudioPlaybackConfiguration>().orEmpty()
        )
    }

    private fun updatePlaybackSources(configs: List<AudioPlaybackConfiguration>) {
        if (!showAppVolume) {
            appVolumeSources.clear()
            mainHandler.post {
                dismissFloatingPerAppVolumeOverlay()
            }
            return
        }

        val now = System.currentTimeMillis()
        val previousSources = appVolumeSources.toMap()
        val nextSources = linkedMapOf<String, AppVolumeSource>()

        configs.forEach { config ->
            if (!isPlaybackConfigActive(config)) return@forEach

            val uid = config.callMethodSilently("getClientUid") as? Int ?: return@forEach
            val packageName = resolvePackageName(uid) ?: return@forEach
            if (packageName == mContext.packageName || packageName == "android") return@forEach

            val proxy = config.callMethodSilently("getPlayerProxy")
            val previousSource = previousSources[packageName]
            val volume = previousSource?.volume ?: readStoredAppVolume(packageName)

            val source = previousSource?.copy(
                volume = volume,
                proxies = mutableListOf(),
                lastSeenAtMillis = now
            ) ?: AppVolumeSource(
                packageName = packageName,
                label = resolveAppLabel(packageName),
                icon = resolveAppIcon(packageName),
                volume = volume,
                proxies = mutableListOf(),
                lastSeenAtMillis = now
            )

            if (proxy != null) {
                source.proxies.add(proxy)
            }

            source.volume = volume
            source.lastSeenAtMillis = now
            nextSources[packageName] = source
        }

        previousSources.values.forEach { previousSource ->
            if (nextSources.containsKey(previousSource.packageName)) return@forEach

            val recentlySeen = now - previousSource.lastSeenAtMillis <= MUTED_SOURCE_KEEP_MS
            val isMutedByIconify = previousSource.volume <= 0.001f
            val sheetIsOpen = appVolumeSheetView != null

            if ((isMutedByIconify && recentlySeen) || sheetIsOpen) {
                nextSources[previousSource.packageName] = previousSource
            }
        }

        appVolumeSources.clear()
        appVolumeSources.putAll(nextSources)

        appVolumeSources.values.forEach { source ->
            applyVolumeToSource(source)
        }

        mainHandler.post {
            updateFloatingPerAppVolumeOverlay()
            refreshFloatingPerAppVolumeSheet()
        }
    }

    private fun isPlaybackConfigActive(config: AudioPlaybackConfiguration): Boolean {
        val isActive = config.callMethodSilently("isActive") as? Boolean
        if (isActive != null) return isActive

        val state = config.callMethodSilently("getPlayerState") as? Int ?: return false
        return state == PLAYER_STATE_STARTED
    }

    private fun resolvePackageName(uid: Int): String? {
        val packages = mContext.packageManager.getPackagesForUid(uid).orEmpty()
        return packages.firstOrNull { it != "android" && it != mContext.packageName }
            ?: packages.firstOrNull()
    }

    private fun resolveAppLabel(packageName: String): String {
        return runCatching {
            val info = mContext.packageManager.getApplicationInfo(packageName, 0)
            mContext.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun resolveAppIcon(packageName: String) =
        runCatching {
            mContext.packageManager.getApplicationIcon(packageName)
        }.getOrNull()

    private fun readStoredAppVolume(packageName: String): Float {
        return getAppVolumePrefs().getFloat(packageName, 1f).coerceIn(0f, 1f)
    }

    private fun writeStoredAppVolume(packageName: String, volume: Float) {
        getAppVolumePrefs()
            .edit()
            .putFloat(packageName, volume.coerceIn(0f, 1f))
            .apply()
    }

    private fun getAppVolumePrefs() =
        mContext
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PER_APP_VOLUME_PREFS, Context.MODE_PRIVATE)

    private fun setSourceVolume(packageName: String, volume: Float) {
        val source = appVolumeSources[packageName] ?: return
        source.volume = volume.coerceIn(0f, 1f)
        writeStoredAppVolume(packageName, source.volume)
        applyVolumeToSource(source)
    }

    private fun applyVolumeToSource(source: AppVolumeSource) {
        val volume = source.volume.coerceIn(0f, 1f)

        source.proxies.forEach { proxy ->
            proxy.callMethodSilently("setVolume", volume)
        }
    }

    private fun shouldShowFloatingPerAppVolumeOverlay(): Boolean {
        if (!showAppVolume) return false

        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return appVolumeSources.isNotEmpty() || audioManager?.isMusicActive == true
    }

    private fun updateFloatingPerAppVolumeOverlay() {
        if (!shouldShowFloatingPerAppVolumeOverlay()) {
            dismissFloatingPerAppVolumeButton()
            return
        }

        val existingButton = appVolumeButtonView
        if (existingButton != null) {
            updateFloatingPerAppVolumeButton(existingButton)
            return
        }

        val button = createFloatingPerAppVolumeButton()
        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val params = WindowManager.LayoutParams(
            mContext.toPx(58),
            mContext.toPx(58),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = mContext.toPx(14)
            y = 0
            setTitle("Iconify per-app volume button")
        }

        runCatching {
            windowManager.addView(button, params)
            appVolumeButtonView = button
            updateFloatingPerAppVolumeButton(button)
        }
    }

    private fun createFloatingPerAppVolumeButton(): ImageButton {
        return ImageButton(mContext).apply {
            alpha = 0.96f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                mContext.toPx(10),
                mContext.toPx(10),
                mContext.toPx(10),
                mContext.toPx(10)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 18, 22, 30))
            }
            setOnClickListener {
                refreshPlaybackSources()
                showFloatingPerAppVolumeSheet()
            }
        }
    }

    private fun updateFloatingPerAppVolumeButton(button: View) {
        val imageButton = button as? ImageButton ?: return
        val firstSource = appVolumeSources.values.firstOrNull()
        val fallbackIcon = runCatching {
            mContext.getDrawable(android.R.drawable.ic_media_play)
        }.getOrNull()

        imageButton.setImageDrawable(firstSource?.icon ?: fallbackIcon)
    }

    private fun dismissFloatingPerAppVolumeOverlay() {
        dismissFloatingPerAppVolumeSheet()
        dismissFloatingPerAppVolumeButton()
    }

    private fun dismissFloatingPerAppVolumeButton() {
        val view = appVolumeButtonView ?: return
        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        runCatching {
            windowManager.removeView(view)
        }

        appVolumeButtonView = null
    }

    private fun showFloatingPerAppVolumeSheet() {
        dismissFloatingPerAppVolumeSheet()

        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val overlay = FrameLayout(mContext).apply {
            setBackgroundColor(Color.argb(88, 0, 0, 0))
            setOnClickListener {
                dismissFloatingPerAppVolumeSheet()
            }
        }

        val sheet = createFloatingPerAppVolumeSheetContent().apply {
            setOnClickListener {
                // Consume clicks inside the sheet.
            }
        }

        overlay.addView(
            sheet,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = mContext.toPx(12)
                rightMargin = mContext.toPx(12)
                bottomMargin = mContext.toPx(12)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("Iconify per-app volume sheet")
        }

        runCatching {
            windowManager.addView(overlay, params)
            appVolumeSheetView = overlay
        }
    }

    private fun dismissFloatingPerAppVolumeSheet() {
        val view = appVolumeSheetView ?: return
        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        runCatching {
            windowManager.removeView(view)
        }

        appVolumeSheetView = null
    }

    private fun refreshFloatingPerAppVolumeSheet() {
        if (appVolumeSheetView == null) return

        dismissFloatingPerAppVolumeSheet()
        showFloatingPerAppVolumeSheet()
    }

    private fun createFloatingPerAppVolumeSheetContent(): View {
        val container = LinearLayout(mContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                mContext.toPx(22),
                mContext.toPx(18),
                mContext.toPx(22),
                mContext.toPx(24)
            )
            background = GradientDrawable().apply {
                cornerRadius = mContext.toPx(28).toFloat()
                setColor(Color.argb(248, 24, 24, 24))
            }
        }

        container.addView(TextView(mContext).apply {
            text = "Громкость приложений"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        container.addView(TextView(mContext).apply {
            text = if (appVolumeSources.isEmpty()) {
                "Активные источники пока не найдены"
            } else {
                "Активные источники: ${appVolumeSources.size}"
            }
            textSize = 12f
            setTextColor(Color.argb(190, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, mContext.toPx(6), 0, mContext.toPx(12))
        })

        if (appVolumeSources.isEmpty()) {
            container.addView(TextView(mContext).apply {
                text = "Запусти музыку или видео и открой окно ещё раз."
                textSize = 13f
                setTextColor(Color.argb(220, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(0, mContext.toPx(10), 0, mContext.toPx(10))
            })
        } else {
            appVolumeSources.values.forEach { source ->
                container.addView(createPerAppVolumeRow(source))
            }
        }

        return ScrollView(mContext).apply {
            addView(container)
        }
    }

    private fun createPerAppVolumeRow(source: AppVolumeSource): View {
        val row = LinearLayout(mContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, mContext.toPx(8), 0, mContext.toPx(8))
        }

        row.addView(ImageView(mContext).apply {
            setImageDrawable(source.icon)
        }, LinearLayout.LayoutParams(mContext.toPx(34), mContext.toPx(34)).apply {
            rightMargin = mContext.toPx(12)
        })

        row.addView(TextView(mContext).apply {
            text = source.label
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val percentText = TextView(mContext).apply {
            text = "${(source.volume * 100f).roundToInt().coerceIn(0, 100)}%"
            textSize = 12f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.END
        }

        row.addView(percentText, LinearLayout.LayoutParams(mContext.toPx(42), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = mContext.toPx(8)
        })

        row.addView(SeekBar(mContext).apply {
            max = 100
            progress = (source.volume * 100f).roundToInt().coerceIn(0, 100)
            isEnabled = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    percentText.text = "${progress.coerceIn(0, 100)}%"

                    if (fromUser) {
                        setSourceVolume(source.packageName, progress / 100f)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(mContext.toPx(150), ViewGroup.LayoutParams.WRAP_CONTENT))

        return row
    }

    private data class AppVolumeSource(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        var volume: Float,
        val proxies: MutableList<Any>,
        var lastSeenAtMillis: Long
    )

    private fun createVolumeTextView(): TextView {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = mContext.toPx(8)
        }

        val volumeNumber = TextView(mContext).apply {
            layoutParams = params
            id = mContext.resources.getIdentifier(
                "volume_number",
                "id",
                mContext.packageName
            )
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(
                mContext.resources.getColor(
                    mContext.resources.getIdentifier(
                        "android:color/system_accent1_300",
                        "color",
                        mContext.packageName
                    ), mContext.theme
                )
            )
            text = String.format("%d%%", 0)
        }

        return volumeNumber
    }

    companion object {
        private const val PER_APP_VOLUME_PREFS = "iconify_per_app_volume"
        private const val PLAYER_STATE_STARTED = 2
        private const val MUTED_SOURCE_KEEP_MS = 10 * 60 * 1000L
    }
}
