package com.drdisagree.iconify.xposed.modules.volume

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
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
    private val appVolumeButtons = WeakHashMap<ImageButton, Unit>()
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
                updatePerAppVolumeButtons()
            }
        } else {
            dismissPerAppVolumeBottomSheet()
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
        hookLegacyVolumeDialogForPerAppVolume()
        hookModernVolumeDialogForPerAppVolume()

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

    private fun hookLegacyVolumeDialogForPerAppVolume() {
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

                registerPlaybackCallback()
                refreshPlaybackSources()

                val root = findRootFromLegacyVolumeDialog(param.thisObject) ?: return@runAfter
                root.post {
                    attachPerAppVolumeButton(root)
                    updatePerAppVolumeButtons()
                }
            }
    }

    private fun hookModernVolumeDialogForPerAppVolume() {
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

                val dialog = param.args.firstOrNull { it is Dialog } as? Dialog
                val root = dialog?.window?.decorView as? ViewGroup ?: return@runAfter

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

    private fun attachPerAppVolumeButton(root: ViewGroup) {
        val targetButton = findSettingsOrBottomButton(root) ?: return
        val targetParent = targetButton.parent as? ViewGroup ?: return

        val existingStack = root.findViewWithTag<LinearLayout>(PER_APP_VOLUME_BUTTON_STACK_TAG)
        if (existingStack != null) {
            val existingButton = existingStack.findViewWithTag<ImageButton>(PER_APP_VOLUME_BUTTON_TAG)
            if (existingButton != null) {
                appVolumeButtons[existingButton] = Unit
                updatePerAppVolumeButton(existingButton)
            }
            updatePerAppVolumeButtons()
            return
        }

        val originalIndex = targetParent.indexOfChild(targetButton)
        if (originalIndex < 0) return

        val originalParams = targetButton.layoutParams
        val buttonSize = resolveButtonSize(targetButton)
        val stack = createPerAppVolumeButtonStack()
        val appButton = createPerAppVolumeButton()

        runCatching {
            targetParent.clipChildren = false
            targetParent.clipToPadding = false

            targetParent.removeView(targetButton)

            stack.addView(
                targetButton,
                LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    gravity = Gravity.CENTER
                }
            )

            stack.addView(
                appButton,
                LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                    gravity = Gravity.CENTER
                }
            )

            targetParent.addView(stack, originalIndex, originalParams)

            expandTouchAncestors(stack, buttonSize)

            appVolumeButtons[appButton] = Unit
            updatePerAppVolumeButton(appButton)
            updatePerAppVolumeButtons()
        }.onFailure {
            runCatching {
                if (targetButton.parent == null) {
                    targetParent.addView(targetButton, originalIndex, originalParams)
                }
            }
        }
    }

    private fun createPerAppVolumeButtonStack(): LinearLayout {
        return LinearLayout(mContext).apply {
            tag = PER_APP_VOLUME_BUTTON_STACK_TAG
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            alpha = 0.98f
            isClickable = false
            setPadding(0, 0, 0, 0)
            background = null
        }
    }

    private fun createPerAppVolumeButton(): ImageButton {
        return ImageButton(mContext).apply {
            tag = PER_APP_VOLUME_BUTTON_TAG
            alpha = 0.98f
            isClickable = true
            isFocusable = true
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                mContext.toPx(9),
                mContext.toPx(9),
                mContext.toPx(9),
                mContext.toPx(9)
            )
            background = null
            contentDescription = "Per-app volume"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        view.isPressed = false
                        view.performClick()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        true
                    }

                    else -> true
                }
            }
            setOnClickListener {
                refreshPlaybackSources()
                showPerAppVolumeBottomSheet()
            }
        }
    }

    private fun resolveButtonSize(referenceView: View): Int {
        val referenceParams = referenceView.layoutParams
        val measuredWidth = referenceView.width
        val measuredHeight = referenceView.height

        return when {
            measuredWidth > 0 && measuredHeight > 0 -> minOf(measuredWidth, measuredHeight)
            referenceParams?.width != null && referenceParams.width > 0 &&
                    referenceParams.height > 0 -> minOf(referenceParams.width, referenceParams.height)
            else -> mContext.toPx(48)
        }.coerceIn(mContext.toPx(40), mContext.toPx(58))
    }

    private fun expandTouchAncestors(anchor: View, extraHeight: Int) {
        var current: View? = anchor
        repeat(8) {
            val parent = current?.parent as? ViewGroup ?: return

            parent.clipChildren = false
            parent.clipToPadding = false
            parent.minimumHeight = maxOf(parent.minimumHeight, parent.height + extraHeight)

            val params = parent.layoutParams
            if (params != null && params.height > 0) {
                params.height += extraHeight
                parent.layoutParams = params
            }

            parent.requestLayout()
            current = parent
        }
    }

    private fun findSettingsOrBottomButton(root: ViewGroup): View? {
        findViewByResourceName(
            root,
            "volume_panel_dialog_settings_button"
        )?.let { return it }

        findViewByResourceName(
            root,
            "volume_dialog_bottom_section_container"
        )?.let { bottomSection ->
            findBottomClickableButton(bottomSection as? ViewGroup ?: root)?.let { return it }
        }

        findCompactVolumePanelView(root)?.let { compactPanel ->
            findBottomClickableButton(compactPanel)?.let { return it }
        }

        return findBottomClickableButton(root)
    }

    private fun findViewByResourceName(root: ViewGroup, name: String): View? {
        val id = mContext.resources.getIdentifier(name, "id", mContext.packageName)
        if (id == 0) return null

        return root.findViewById(id)
    }

    private fun findCompactVolumePanelView(root: ViewGroup): ViewGroup? {
        val displayWidth = mContext.resources.displayMetrics.widthPixels
        val minWidth = mContext.toPx(48)
        val maxWidth = mContext.toPx(170)
        val minHeight = mContext.toPx(140)

        var bestCandidate: ViewGroup? = null
        var bestScore = Int.MIN_VALUE

        fun visit(view: View) {
            val group = view as? ViewGroup ?: return
            val width = if (group.width > 0) group.width else group.layoutParams?.width ?: 0
            val height = if (group.height > 0) group.height else group.layoutParams?.height ?: 0

            val looksLikeCompactPanel =
                width in minWidth..maxWidth &&
                        height >= minHeight &&
                        height > width * 2 &&
                        group.childCount >= 2

            if (looksLikeCompactPanel) {
                val location = IntArray(2)
                runCatching {
                    group.getLocationOnScreen(location)
                }

                val rightSideBonus = if (location[0] > displayWidth / 2) 5000 else 0
                val score = height * 10 - width + rightSideBonus

                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = group
                }
            }

            for (i in 0 until group.childCount) {
                visit(group.getChildAt(i))
            }
        }

        visit(root)
        return bestCandidate
    }

    private fun findBottomClickableButton(root: ViewGroup): View? {
        var bestView: View? = null
        var bestScore = Int.MIN_VALUE

        fun visit(view: View) {
            if (view.tag == PER_APP_VOLUME_BUTTON_TAG ||
                view.tag == PER_APP_VOLUME_BUTTON_STACK_TAG
            ) {
                return
            }

            val group = view as? ViewGroup
            val isCandidate =
                view.isShown &&
                        view !is SeekBar &&
                        (view.isClickable || view.hasOnClickListeners())

            if (isCandidate) {
                val location = IntArray(2)
                runCatching {
                    view.getLocationOnScreen(location)
                }

                val width = if (view.width > 0) view.width else view.layoutParams?.width ?: 0
                val height = if (view.height > 0) view.height else view.layoutParams?.height ?: 0
                val bottom = location[1] + height
                val compactBonus = if (width <= mContext.toPx(80) && height <= mContext.toPx(80)) {
                    3000
                } else {
                    0
                }
                val score = bottom * 10 + compactBonus

                if (score > bestScore) {
                    bestScore = score
                    bestView = view
                }
            }

            if (group != null) {
                for (i in 0 until group.childCount) {
                    visit(group.getChildAt(i))
                }
            }
        }

        visit(root)
        return bestView
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
            if (button.parent == null) {
                appVolumeButtons.remove(button)
                return@forEach
            }

            button.visibility = if (shouldShow) View.VISIBLE else View.GONE
            updatePerAppVolumeButton(button)
        }
    }

    private fun updatePerAppVolumeButton(button: ImageButton) {
        val firstSource = appVolumeSources.values.firstOrNull()
        val fallbackIcon = runCatching {
            mContext.getDrawable(android.R.drawable.ic_media_play)
        }.getOrNull()

        button.setImageDrawable(firstSource?.icon ?: fallbackIcon)
    }

    private fun showPerAppVolumeBottomSheet() {
        dismissPerAppVolumeBottomSheet()

        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val overlay = FrameLayout(mContext).apply {
            setBackgroundColor(Color.argb(85, 0, 0, 0))
            setOnClickListener {
                dismissPerAppVolumeBottomSheet()
            }
        }

        val sheet = createPerAppVolumeSheetContent()
        sheet.setOnClickListener {
            // Consume clicks so tapping the sheet does not close the overlay.
        }

        overlay.addView(
            sheet,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            setTitle("Iconify per-app volume")
        }

        runCatching {
            windowManager.addView(overlay, params)
            appVolumeSheetView = overlay
        }
    }

    private fun dismissPerAppVolumeBottomSheet() {
        val view = appVolumeSheetView ?: return
        val windowManager =
            mContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        runCatching {
            windowManager.removeView(view)
        }

        appVolumeSheetView = null
    }

    private fun refreshPerAppVolumeBottomSheet() {
        if (appVolumeSheetView == null) return

        dismissPerAppVolumeBottomSheet()
        showPerAppVolumeBottomSheet()
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
        private const val PER_APP_VOLUME_PREFS = "iconify_per_app_volume"
        private const val PLAYER_STATE_STARTED = 2
    }
}
