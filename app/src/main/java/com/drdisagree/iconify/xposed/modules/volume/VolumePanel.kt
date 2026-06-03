package com.drdisagree.iconify.xposed.modules.volume

import android.annotation.SuppressLint
import android.content.Context
import android.widget.SeekBar
import android.widget.ImageView
import android.os.Looper
import android.os.Handler
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

@SuppressLint("DiscouragedApi", "DefaultLocale")
class VolumePanel(context: Context) : ModPack(context) {

    private var showPercentage = false
    private var showWarning = true
    private var showAppVolume = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appVolumeSources = linkedMapOf<String, AppVolumeSource>()
    private val appVolumePanels = WeakHashMap<LinearLayout, Unit>()
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
                updatePerAppVolumePanels()
            }
        } else {
            appVolumeSources.clear()
            updatePerAppVolumePanels()
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
        hookPerAppVolumeExpandedPanel()

        if (showAppVolume) {
            registerPlaybackCallback()
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

    private fun hookPerAppVolumeExpandedPanel() {
        val volumeDialogImplClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.VolumeDialogImpl",
            suppressError = true
        ) ?: return

        volumeDialogImplClass
            .hookMethod("initRow")
            .suppressError()
            .runAfter { param ->
                if (!showAppVolume) return@runAfter

                registerPlaybackCallback()
                refreshPlaybackSources()
                attachPerAppVolumePanel(param.args[0])
            }

        volumeDialogImplClass
            .hookMethod(
                "showH",
                "updateRowsH",
                "updateVolumeRowH"
            )
            .suppressError()
            .runAfter { param ->
                if (showAppVolume) {
                    registerPlaybackCallback()
                    refreshPlaybackSources()
                    attachPerAppVolumePanelsFromDialog(param.thisObject)
                }

                updatePerAppVolumePanels()
            }
    }

    private fun refreshPlaybackSources() {
        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val currentConfigs =
            audioManager.callMethodSilently("getActivePlaybackConfigurations") as? List<*>

        updatePlaybackSources(
            currentConfigs?.filterIsInstance<AudioPlaybackConfiguration>().orEmpty()
        )
    }

    private fun attachPerAppVolumePanelsFromDialog(volumeDialog: Any?) {
        val rows = volumeDialog
            .getFieldSilently("mRows") as? Iterable<*> ?: return

        rows.forEach { row ->
            attachPerAppVolumePanel(row)
        }
    }

    private fun attachPerAppVolumePanel(row: Any?) {
        val stream = row.getFieldSilently("stream") as? Int
        if (stream != AudioManager.STREAM_MUSIC) return

        val rowView = row.getFieldSilently("view") as? View ?: return
        val parent = findExpandedRowsParent(rowView) ?: return

        val existingPanel = parent.findViewWithTag<LinearLayout>(PER_APP_VOLUME_PANEL_TAG)
        if (existingPanel != null) {
            appVolumePanels[existingPanel] = Unit
            updatePerAppVolumePanel(existingPanel)
            return
        }

        val panel = LinearLayout(mContext).apply {
            tag = PER_APP_VOLUME_PANEL_TAG
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                mContext.toPx(12),
                mContext.toPx(8),
                mContext.toPx(12),
                mContext.toPx(10)
            )
            background = GradientDrawable().apply {
                cornerRadius = mContext.toPx(18).toFloat()
                setColor(Color.argb(42, 255, 255, 255))
            }
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = mContext.toPx(4)
            bottomMargin = mContext.toPx(8)
        }

        runCatching {
            val index = parent.indexOfChild(rowView)
            if (index >= 0) {
                parent.addView(panel, index + 1, params)
            } else {
                parent.addView(panel, params)
            }

            appVolumePanels[panel] = Unit
            updatePerAppVolumePanel(panel)
        }
    }

    private fun findExpandedRowsParent(rowView: View): LinearLayout? {
        var current: View? = rowView

        repeat(5) {
            val parent = current?.parent as? View ?: return@repeat
            val parentLayout = parent as? LinearLayout

            if (parentLayout != null && parentLayout.orientation == LinearLayout.VERTICAL) {
                return parentLayout
            }

            current = parent
        }

        return rowView.parent as? LinearLayout
    }

    private fun updatePlaybackSources(configs: List<AudioPlaybackConfiguration>) {
        if (!showAppVolume) {
            appVolumeSources.clear()
            mainHandler.post {
                updatePerAppVolumePanels()
            }
            return
        }

        val previousVolumes = appVolumeSources.mapValues { it.value.volume }
        appVolumeSources.clear()

        configs.forEach { config ->
            if (!isPlaybackConfigActive(config)) return@forEach

            val uid = config.callMethodSilently("getClientUid") as? Int ?: return@forEach
            val packageName = resolvePackageName(uid) ?: return@forEach
            if (packageName == mContext.packageName || packageName == "android") return@forEach

            val proxy = config.callMethodSilently("getPlayerProxy")
            val volume = previousVolumes[packageName] ?: readStoredAppVolume(packageName)

            val source = appVolumeSources.getOrPut(packageName) {
                AppVolumeSource(
                    packageName = packageName,
                    label = resolveAppLabel(packageName),
                    icon = resolveAppIcon(packageName),
                    volume = volume,
                    proxies = mutableListOf()
                )
            }

            if (proxy != null) {
                source.proxies.add(proxy)
            }
            source.volume = volume
        }

        appVolumeSources.values.forEach { source ->
            applyVolumeToSource(source)
        }

        mainHandler.post {
            updatePerAppVolumePanels()
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

    private fun updatePerAppVolumePanels() {
        val shouldShow = shouldShowPerAppVolumePanel()

        appVolumePanels.keys.toList().forEach { panel ->
            if (panel.parent == null) {
                appVolumePanels.remove(panel)
                return@forEach
            }

            panel.visibility = if (shouldShow) View.VISIBLE else View.GONE

            if (shouldShow) {
                updatePerAppVolumePanel(panel)
            }
        }
    }

    private fun shouldShowPerAppVolumePanel(): Boolean {
        if (!showAppVolume) return false

        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return appVolumeSources.isNotEmpty() || audioManager?.isMusicActive == true
    }

    private fun updatePerAppVolumePanel(panel: LinearLayout) {
        panel.removeAllViews()

        panel.addView(TextView(mContext).apply {
            text = "Громкость приложений"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, mContext.toPx(4))
        })

        panel.addView(TextView(mContext).apply {
            text = if (appVolumeSources.isEmpty()) {
                "Активные источники пока не найдены"
            } else {
                "Активные источники: ${appVolumeSources.size}"
            }
            textSize = 11f
            setTextColor(Color.argb(185, 255, 255, 255))
            setPadding(0, 0, 0, mContext.toPx(8))
        })

        if (appVolumeSources.isEmpty()) {
            panel.addView(TextView(mContext).apply {
                text = "Запусти музыку/видео и снова раскрой панель громкости."
                textSize = 12f
                setTextColor(Color.argb(210, 255, 255, 255))
                setPadding(0, mContext.toPx(2), 0, mContext.toPx(2))
            })
        } else {
            appVolumeSources.values.forEach { source ->
                panel.addView(createPerAppVolumeRow(source))
            }
        }
    }

    private fun createPerAppVolumeRow(source: AppVolumeSource): View {
        val row = LinearLayout(mContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, mContext.toPx(5), 0, mContext.toPx(5))
        }

        row.addView(ImageView(mContext).apply {
            setImageDrawable(source.icon)
        }, LinearLayout.LayoutParams(mContext.toPx(30), mContext.toPx(30)).apply {
            rightMargin = mContext.toPx(10)
        })

        row.addView(TextView(mContext).apply {
            text = source.label
            textSize = 13f
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
            isEnabled = source.proxies.isNotEmpty()
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
        val proxies: MutableList<Any>
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
        private const val PER_APP_VOLUME_PANEL_TAG = "iconify_per_app_volume_panel"
        private const val PER_APP_VOLUME_PREFS = "iconify_per_app_volume"
        private const val PLAYER_STATE_STARTED = 2
    }

}