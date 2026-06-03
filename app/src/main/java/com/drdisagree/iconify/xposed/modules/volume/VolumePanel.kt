package com.drdisagree.iconify.xposed.modules.volume

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.widget.SeekBar
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.ImageView
import android.os.Looper
import android.os.Handler
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@SuppressLint("DiscouragedApi", "DefaultLocale")
class VolumePanel(context: Context) : ModPack(context) {

    private var showPercentage = false
    private var showWarning = true
    private var showAppVolume = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appVolumeSources = linkedMapOf<String, AppVolumeSource>()
    private val appVolumeButtons = WeakHashMap<View, Unit>()
    private var appVolumeSheetDialog: Dialog? = null
    private var currentVolumeDialog: Dialog? = null
    private var currentVolumeDialogObject: Any? = null
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
                updatePerAppVolumeButtons()
            }
        } else {
            appVolumeSheetDialog?.dismiss()
            appVolumeSheetDialog = null
            appVolumeSources.clear()
            updatePerAppVolumeButtons()
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
        hookPerAppVolumeButton()

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

    private fun hookPerAppVolumeButton() {
        hookLegacyVolumeDialogButton()
        hookModernVolumeDialogButton()
    }

    private fun hookLegacyVolumeDialogButton() {
        val volumeDialogImplClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.VolumeDialogImpl",
            suppressError = true
        ) ?: return

        volumeDialogImplClass
            .hookMethod(
                "showH",
                "updateRowsH",
                "updateVolumeRowH",
                "initRow"
            )
            .suppressError()
            .runAfter { param ->
                if (!showAppVolume) return@runAfter

                currentVolumeDialogObject = param.thisObject
                registerPlaybackCallback()
                refreshPlaybackSources()

                val root = findRootFromVolumeDialog(param.thisObject) ?: return@runAfter
                root.post {
                    attachPerAppVolumeButton(root)
                    updatePerAppVolumeButtons()
                }
            }
    }

    private fun hookModernVolumeDialogButton() {
        val volumeDialogViewBinderClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.dialog.ui.binder.VolumeDialogViewBinder",
            suppressError = true
        )

        volumeDialogViewBinderClass
            .hookMethod("bind")
            .suppressError()
            .runAfter { param ->
                if (!showAppVolume) return@runAfter

                registerPlaybackCallback()
                refreshPlaybackSources()

                currentVolumeDialog = param.args.firstOrNull { it is Dialog } as? Dialog
                currentVolumeDialogObject = currentVolumeDialog

                val root = currentVolumeDialog
                    ?.window
                    ?.decorView as? ViewGroup ?: return@runAfter

                root.post {
                    attachPerAppVolumeButton(root)
                    updatePerAppVolumeButtons()
                }
            }

        val volumeDialogSlidersViewBinderClass = findClass(
            "$SYSTEMUI_PACKAGE.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder",
            suppressError = true
        )

        volumeDialogSlidersViewBinderClass
            .hookMethod("bind")
            .suppressError()
            .runAfter { param ->
                if (!showAppVolume) return@runAfter

                registerPlaybackCallback()
                refreshPlaybackSources()

                val bindView = param.args.firstOrNull { it is View } as? View ?: return@runAfter
                val root = findDecorRoot(bindView) ?: return@runAfter

                root.post {
                    attachPerAppVolumeButton(root)
                    updatePerAppVolumeButtons()
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

    private fun findRootFromVolumeDialog(volumeDialog: Any?): ViewGroup? {
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

    private fun attachPerAppVolumeButton(root: ViewGroup) {
        val panelView = findCompactVolumePanelView(root) ?: root
        val existingHost = root.findViewWithTag<LinearLayout>(PER_APP_VOLUME_BUTTON_HOST_TAG)

        if (existingHost != null) {
            placePerAppVolumeButtonHost(root, panelView, existingHost)

            val existingButton = existingHost.findViewWithTag<ImageButton>(PER_APP_VOLUME_BUTTON_TAG)
            if (existingButton != null) {
                appVolumeButtons[existingButton] = Unit
                updatePerAppVolumeButton(existingButton)
            }

            updatePerAppVolumeButtons()
            return
        }

        val host = LinearLayout(mContext).apply {
            tag = PER_APP_VOLUME_BUTTON_HOST_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0.99f
            setPadding(
                mContext.toPx(5),
                mContext.toPx(5),
                mContext.toPx(5),
                mContext.toPx(7)
            )
            background = GradientDrawable().apply {
                cornerRadius = mContext.toPx(28).toFloat()
                setColor(Color.argb(238, 14, 18, 24))
            }
        }

        val button = ImageButton(mContext).apply {
            tag = PER_APP_VOLUME_BUTTON_TAG
            alpha = 0.98f
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                mContext.toPx(9),
                mContext.toPx(9),
                mContext.toPx(9),
                mContext.toPx(9)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 224, 239, 255))
            }
            setOnClickListener {
                refreshPlaybackSources()
                showPerAppVolumeBottomSheet()
            }
        }

        host.addView(
            button,
            LinearLayout.LayoutParams(mContext.toPx(46), mContext.toPx(46)).apply {
                gravity = Gravity.CENTER
            }
        )

        runCatching {
            val params = createPerAppVolumeHostLayoutParams(root, panelView)
            root.addView(host, params)
            appVolumeButtons[button] = Unit
            updatePerAppVolumeButton(button)
            updatePerAppVolumeButtons()
        }
    }

    private fun placePerAppVolumeButtonHost(
        root: ViewGroup,
        panelView: View,
        host: LinearLayout
    ) {
        runCatching {
            host.layoutParams = createPerAppVolumeHostLayoutParams(root, panelView)
            host.requestLayout()
        }
    }

    private fun createPerAppVolumeHostLayoutParams(
        root: ViewGroup,
        panelView: View
    ): ViewGroup.LayoutParams {
        val panelBounds = getBoundsInsideRoot(root, panelView)
        val width = panelBounds.width().coerceAtLeast(mContext.toPx(72))
        val top = (panelBounds.bottom - mContext.toPx(3)).coerceAtLeast(0)

        return when (root) {
            is FrameLayout -> FrameLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                leftMargin = panelBounds.left.coerceAtLeast(0)
                topMargin = top
            }

            is LinearLayout -> LinearLayout.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = mContext.toPx(2)
                bottomMargin = mContext.toPx(2)
            }

            else -> ViewGroup.MarginLayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = panelBounds.left.coerceAtLeast(0)
                topMargin = top
            }
        }
    }

    private fun findCompactVolumePanelView(root: ViewGroup): ViewGroup? {
        val candidates = mutableListOf<Pair<ViewGroup, Int>>()

        fun visit(view: View) {
            val viewGroup = view as? ViewGroup ?: return

            if (viewGroup.tag != PER_APP_VOLUME_BUTTON_HOST_TAG) {
                val score = scoreCompactVolumePanelCandidate(root, viewGroup)
                if (score > 0) {
                    candidates.add(viewGroup to score)
                }
            }

            for (i in 0 until viewGroup.childCount) {
                visit(viewGroup.getChildAt(i))
            }
        }

        visit(root)

        return candidates.maxByOrNull { it.second }?.first
    }

    private fun scoreCompactVolumePanelCandidate(root: ViewGroup, view: ViewGroup): Int {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return 0

        val bounds = getBoundsInsideRoot(root, view)
        val width = bounds.width()
        val height = bounds.height()
        val rootWidth = root.width.takeIf { it > 0 } ?: mContext.resources.displayMetrics.widthPixels
        val rightGap = rootWidth - bounds.right

        if (width !in mContext.toPx(54)..mContext.toPx(170)) return 0
        if (height < mContext.toPx(220)) return 0
        if (rightGap < -mContext.toPx(8) || rightGap > mContext.toPx(96)) return 0

        var score = 1000
        score -= abs(width - mContext.toPx(92))
        score -= rightGap.coerceAtLeast(0) / 2
        score += (height / 12).coerceAtMost(80)

        if (view.background != null) score += 120
        if (view is LinearLayout && view.orientation == LinearLayout.VERTICAL) score += 180
        if (view.childCount >= 2) score += 80
        if (view.findViewWithTag<View>(PER_APP_VOLUME_BUTTON_HOST_TAG) != null) score -= 500

        return score
    }

    private fun getBoundsInsideRoot(root: View, child: View): Rect {
        val rootLocation = IntArray(2)
        val childLocation = IntArray(2)

        root.getLocationOnScreen(rootLocation)
        child.getLocationOnScreen(childLocation)

        val left = childLocation[0] - rootLocation[0]
        val top = childLocation[1] - rootLocation[1]

        return Rect(
            left,
            top,
            left + child.width,
            top + child.height
        )
    }

    private fun updatePlaybackSources(configs: List<AudioPlaybackConfiguration>) {
        if (!showAppVolume) {
            appVolumeSources.clear()
            mainHandler.post {
                updatePerAppVolumeButtons()
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
            updatePerAppVolumeButtons()
            refreshPerAppVolumeBottomSheet()
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

    private fun updatePerAppVolumeButtons() {
        val audioManager =
            mContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val shouldShow = showAppVolume &&
                (appVolumeSources.isNotEmpty() || audioManager?.isMusicActive == true)

        appVolumeButtons.keys.toList().forEach { button ->
            val host = button.parent as? View

            if (host == null || host.parent == null) {
                appVolumeButtons.remove(button)
                return@forEach
            }

            host.visibility = if (shouldShow) View.VISIBLE else View.GONE
            button.visibility = if (shouldShow) View.VISIBLE else View.GONE

            if (button is ImageButton) {
                updatePerAppVolumeButton(button)
            }
        }
    }

    private fun updatePerAppVolumeButton(button: ImageButton) {
        val firstSource = appVolumeSources.values.firstOrNull()
        val fallbackIcon = runCatching {
            mContext.getDrawable(android.R.drawable.ic_media_play)
        }.getOrNull()

        button.setImageDrawable(firstSource?.icon ?: fallbackIcon)
    }

    private fun hideSystemVolumeDialog() {
        runCatching {
            currentVolumeDialog?.dismiss()
        }

        currentVolumeDialogObject.callMethodSilently("dismissH")
        currentVolumeDialogObject.callMethodSilently("dismiss")
    }

    private fun showPerAppVolumeBottomSheet() {
        appVolumeSheetDialog?.dismiss()

        val dialog = Dialog(mContext).apply {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setContentView(createPerAppVolumeSheetContent())
            setOnDismissListener {
                if (appVolumeSheetDialog === this) {
                    appVolumeSheetDialog = null
                }
            }
        }

        appVolumeSheetDialog = dialog

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.25f)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun refreshPerAppVolumeBottomSheet() {
        val dialog = appVolumeSheetDialog ?: return
        if (!dialog.isShowing) return

        dialog.setContentView(createPerAppVolumeSheetContent())
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createPerAppVolumeSheetContent(): View {
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
                text = "Запусти музыку/видео и открой окно ещё раз."
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
        private const val PER_APP_VOLUME_BUTTON_TAG = "iconify_per_app_volume_button"
        private const val PER_APP_VOLUME_BUTTON_HOST_TAG = "iconify_per_app_volume_button_host"
        private const val PER_APP_VOLUME_PREFS = "iconify_per_app_volume"
        private const val PLAYER_STATE_STARTED = 2
    }

}