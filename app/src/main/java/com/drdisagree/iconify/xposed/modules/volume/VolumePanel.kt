package com.drdisagree.iconify.xposed.modules.volume

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
        hookExpandedVolumePanelDialogForPerAppVolume()

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

    private fun hookExpandedVolumePanelDialogForPerAppVolume() {
        val volumePanelDialogClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.VolumePanelDialog",
            suppressError = true
        ) ?: return

        volumePanelDialogClass
            .hookMethod("show", "onStart", "onWindowFocusChanged")
            .suppressError()
            .runAfter { param ->
                if (!showAppVolume) return@runAfter

                val dialog = param.thisObject as? Dialog ?: return@runAfter
                val root = dialog.window?.decorView as? ViewGroup ?: return@runAfter

                registerPlaybackCallback()
                refreshPlaybackSources()

                root.post {
                    attachPerAppVolumeExpandedPanel(root)
                    updatePerAppVolumePanels()
                }
            }

        volumePanelDialogClass
            .hookMethod("dismiss", "cancel")
            .suppressError()
            .runBefore {
                appVolumePanels.keys.toList().forEach { panel ->
                    (panel.parent as? ViewGroup)?.removeView(panel)
                    appVolumePanels.remove(panel)
                }
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

    private fun findRootFromLegacyVolumeDialog(volumeDialog: Any?): ViewGroup? {
        val dialogView = volumeDialog.getFieldSilently("mDialogView") as? View
        if (dialogView != null) return findDecorRoot(dialogView)

        val dialog = volumeDialog.getFieldSilently("mDialog") as? Dialog
        if (dialog != null) return dialog.window?.decorView as? ViewGroup

        return null
    }

    private fun findDecorRoot(view: View): ViewGroup? {
        var current: View? = view

        repeat(12) {
            val parent = current?.parent as? View
            if (parent == null) {
                return current as? ViewGroup
            }
            current = parent
        }

        return current as? ViewGroup
    }

    private fun attachPerAppVolumeExpandedPanel(root: ViewGroup) {
        val parent = findVolumePanelDialogOverlayParent(root) ?: return
        val existingPanel = root.findViewWithTag<LinearLayout>(PER_APP_VOLUME_PANEL_TAG)
            ?: parent.findViewWithTag(PER_APP_VOLUME_PANEL_TAG)

        if (existingPanel != null) {
            if (existingPanel.parent !== parent) {
                (existingPanel.parent as? ViewGroup)?.removeView(existingPanel)
                addPerAppVolumePanel(parent, existingPanel)
            } else {
                existingPanel.layoutParams = createPerAppVolumePanelLayoutParams(parent)
                existingPanel.requestLayout()
                parent.requestLayout()
            }

            appVolumePanels[existingPanel] = Unit
            updatePerAppVolumePanel(existingPanel)
            updatePerAppVolumePanels()
            return
        }

        val panel = createPerAppVolumePanel()
        addPerAppVolumePanel(parent, panel)
        appVolumePanels[panel] = Unit
        updatePerAppVolumePanel(panel)
        updatePerAppVolumePanels()
    }

    private fun addPerAppVolumePanel(parent: ViewGroup, panel: LinearLayout) {
        runCatching {
            parent.clipChildren = false
            parent.clipToPadding = false
            panel.isClickable = true
            panel.isFocusable = true
            parent.addView(panel, createPerAppVolumePanelLayoutParams(parent))
            parent.requestLayout()
        }
    }

    private fun createPerAppVolumePanel(): LinearLayout {
        return LinearLayout(mContext).apply {
            tag = PER_APP_VOLUME_PANEL_TAG
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                mContext.toPx(18),
                mContext.toPx(12),
                mContext.toPx(18),
                mContext.toPx(12)
            )
            background = GradientDrawable().apply {
                cornerRadius = mContext.toPx(24).toFloat()
                setColor(Color.argb(210, 28, 32, 38))
            }
        }
    }

    private fun createPerAppVolumePanelLayoutParams(parent: ViewGroup): ViewGroup.LayoutParams {
        val width = mContext.resources.displayMetrics.widthPixels
        val topMargin = calculateExpandedPanelTopMargin(parent)

        return when (parent) {
            is FrameLayout -> FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = mContext.toPx(38)
                rightMargin = mContext.toPx(38)
                this.topMargin = topMargin
            }

            is LinearLayout -> LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = mContext.toPx(38)
                rightMargin = mContext.toPx(38)
                this.topMargin = mContext.toPx(8)
                bottomMargin = mContext.toPx(8)
            }

            else -> ViewGroup.MarginLayoutParams(
                width - mContext.toPx(76),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
                leftMargin = mContext.toPx(38)
                rightMargin = mContext.toPx(38)
            }
        }
    }

    private fun findVolumePanelDialogOverlayParent(root: ViewGroup): ViewGroup? {
        // VolumePanelDialog is the expanded sound settings window.  We attach the
        // panel to the dialog content/decor only there, never to the compact
        // VolumeDialog, otherwise it appears in the wrong place and may be
        // non-clickable.
        val content = root.findViewById<ViewGroup>(android.R.id.content)
        if (content != null) return content

        if (root is FrameLayout) return root

        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i) as? ViewGroup ?: continue
            if (child is FrameLayout || child.width >= root.width * 0.75f) {
                return child
            }
        }

        return root
    }

    private fun calculateExpandedPanelTopMargin(parent: ViewGroup): Int {
        val displayHeight = mContext.resources.displayMetrics.heightPixels
        val parentHeight = if (parent.height > 0) parent.height else displayHeight

        // Expanded panel on this SystemUI starts around the lower half of the
        // screen.  Put the block below the media slider area, not in the compact
        // volume dialog.
        return if (parentHeight >= displayHeight * 0.75f) {
            (displayHeight * 0.58f).toInt()
        } else {
            (parentHeight * 0.34f).toInt()
        }
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

            if (panel.visibility == View.VISIBLE) {
                panel.layoutParams = createPerAppVolumePanelLayoutParams(panel.parent as ViewGroup)
                panel.requestLayout()
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
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, mContext.toPx(4))
        })

        panel.addView(TextView(mContext).apply {
            text = if (appVolumeSources.isEmpty()) {
                "Активные источники пока не найдены"
            } else {
                "Активные источники: ${appVolumeSources.size}"
            }
            textSize = 12f
            setTextColor(Color.argb(190, 255, 255, 255))
            setPadding(0, 0, 0, mContext.toPx(8))
        })

        if (appVolumeSources.isEmpty()) {
            panel.addView(TextView(mContext).apply {
                text = "Запусти музыку/видео и открой расширенную панель громкости ещё раз."
                textSize = 13f
                setTextColor(Color.argb(220, 255, 255, 255))
                setPadding(0, mContext.toPx(4), 0, mContext.toPx(4))
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
            setPadding(0, mContext.toPx(7), 0, mContext.toPx(7))
        }

        row.addView(ImageView(mContext).apply {
            setImageDrawable(source.icon)
        }, LinearLayout.LayoutParams(mContext.toPx(32), mContext.toPx(32)).apply {
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
        }, LinearLayout.LayoutParams(mContext.toPx(160), ViewGroup.LayoutParams.WRAP_CONTENT))

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
