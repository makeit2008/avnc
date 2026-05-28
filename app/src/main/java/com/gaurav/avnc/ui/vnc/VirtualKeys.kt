/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.vnc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ToggleButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.isVisible
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.VirtualKeysBinding
import com.gaurav.avnc.ui.vnc.input.InputHandler
import com.gaurav.avnc.util.AppPreferences
import com.gaurav.avnc.util.addOnGlobalLayoutListener
import com.gaurav.avnc.util.isTrue
import kotlin.math.min
import kotlin.math.sign

/**
 * Virtual keys allow the user to input keys which are not normally found on
 * keyboards but can be useful for controlling remote server.
 *
 * This class manages the inflation & visibility of virtual keys.
 */
class VirtualKeys(private val activity: VncActivity, private val inputHandler: InputHandler) {

    private val viewModel = activity.viewModel
    private val pref = activity.viewModel.pref
    private val frameView = activity.binding.frameView
    private val stub = activity.binding.virtualKeysStub
    private val toggleKeys = mutableSetOf<ToggleButton>()
    private val lockedToggleKeys = mutableSetOf<ToggleButton>()
    private val keyCharMap by lazy { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }
    private var openedWithKb = false
    private var closedByPiPMode = false
    private var isDestroyed = false

    val container: View? get() = stub.root

    fun show(saveVisibility: Boolean = false) {
        if (isDestroyed) return
        init()
        container?.visibility = View.VISIBLE
        if (saveVisibility) pref.runInfo.showVirtualKeys = true
    }

    fun hide(saveVisibility: Boolean = false) {
        container?.visibility = View.GONE
        openedWithKb = false //Reset flag
        if (saveVisibility) pref.runInfo.showVirtualKeys = false
    }

    fun onKeyboardOpen() {
        if (pref.input.vkOpenWithKeyboard && container?.visibility != View.VISIBLE) {
            show()
            openedWithKb = true
        }
    }

    fun onKeyboardClose() {
        if (openedWithKb) {
            hide()
            openedWithKb = false
        }

        // Scenario: User uses the TextBox to send text to server, and hides the keyboard. User
        // wants to end the session now, so he swipes-up from bottom to bring up the nav bar, but
        // the TextBox also sees that swipe-up and it shows the keyboard. Now tap on Back navigation
        // button will hide the keyboard instead of ending the session. User must switch away from
        // text-page to break this loop. So we clear the focus here to avoid this issue.
        (stub.binding as? VirtualKeysBinding)?.textBox?.let { if (it.isFocused) it.clearFocus() }
    }

    fun onConnected() {
        if (pref.runInfo.showVirtualKeys && !viewModel.inPiPMode.isTrue)
            show()
    }

    /**
     * Clean up resources and remove listeners to prevent memory leaks.
     * Should be called when VirtualKeys is no longer needed.
     */
    fun destroy() {
        isDestroyed = true
        releaseAllMetaKeys()
        toggleKeys.clear()
        lockedToggleKeys.clear()
        RepeatKeyHandler.clear()
    }

    fun releaseMetaKeys() {
        releaseUnlockedMetaKeys()
        // Also release locked keys when explicitly called
        lockedToggleKeys.clear()
    }

    private fun releaseUnlockedMetaKeys() {
        toggleKeys.forEach {
            if (it.isChecked && !lockedToggleKeys.contains(it))
                it.isChecked = false
        }
    }

    private fun releaseAllMetaKeys() {
        toggleKeys.forEach {
            if (it.isChecked)
                it.isChecked = false
        }
    }

    private fun onAfterKeyEvent(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_UP && !KeyEvent.isModifierKey(event.keyCode))
            releaseUnlockedMetaKeys()
    }

    private fun onPiPModeChanged(inPiPMode: Boolean) {
        if (inPiPMode && container?.isVisible == true) {
            hide()
            closedByPiPMode = true
        } else if (!inPiPMode && closedByPiPMode) {
            show()
            closedByPiPMode = false
        }
    }

    private fun init() {
        if (stub.isInflated)
            return

        stub.viewStub?.inflate()
        val binding = stub.binding as VirtualKeysBinding
        initTextPage(binding)
        initKeys(binding)
        initPager(binding)
        inputHandler.onAfterKeyEventListeners += ::onAfterKeyEvent
        viewModel.inPiPMode.observe(activity) { onPiPModeChanged(it) }
    }

    /**
     * To keep everything in single XML layout file, things are done in a slightly weird way.
     * Both keys & text pages are initially attached to temporary View. After inflation, they
     * are detached and passed onto ViewPager adapter. Adapter will insert them at proper place.
     */
    private fun initPager(binding: VirtualKeysBinding) {
        val root = binding.root
        val keys = binding.keys
        val pager = binding.pager
        val pages = listOf(binding.keysPage, binding.textPage)

        binding.tmpPageHost.apply {
            removeAllViews()
            (parent as ViewGroup).removeView(this)
        }

        // Setup pager
        pager.offscreenPageLimit = pages.size
        pager.adapter = object : PagerAdapter() {
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, obj: Any) = (view === obj)
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                pages[position].let {
                    container.addView(it)
                    return it
                }
            }

            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                container.removeView(obj as View)
            }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            val textPageIndex = pages.indexOf(binding.textPage)
            override fun onPageSelected(position: Int) {
                if (ViewCompat.getRootWindowInsets(root)?.isVisible(Type.ime()) == true) {
                    if (position == textPageIndex) binding.textBox.requestFocus()
                    else frameView.requestFocus()
                }
                pref.runInfo.virtualKeysTextBoxVisible = (position == textPageIndex)
            }
        })

        // Setup Layout. Keys grid is the primary View used for deciding size of Virtual keys.
        // All keys are shown if screen is wide enough. Otherwise width is limited to FrameView,
        // and HorizontalScrollView is relied upon to access all keys.
        // NOTE: Paddings in root/pager view is NOT handled by this code.

        // Start with something sane using AT_MOST for more accurate measurement
        val maxWidth = if (frameView.width > 0) frameView.width else 
            MeasureSpec.getSize(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        keys.measure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        root.layoutParams = root.layoutParams.apply { 
            width = keys.measuredWidth
            height = keys.measuredHeight 
        }

        // Update size after layout changes
        addOnGlobalLayoutListener(activity, keys) {
            val w = min(keys.width, frameView.width)
            val h = keys.height
            if (w > 0 && h > 0 && (root.width != w || root.height != h))
                root.layoutParams = root.layoutParams.apply { width = w; height = h }
        }

        // Switch to text page if it was active last time
        if (pref.runInfo.virtualKeysTextBoxVisible)
            pager.setCurrentItem(pages.indexOf(binding.textPage), false)
    }

    private fun initTextPage(binding: VirtualKeysBinding) {
        binding.textPageBackBtn.setOnClickListener {
            binding.pager.setCurrentItem(0, true)
        }
        binding.textBox.setOnEditorActionListener { _, _, _ ->
            handleTextBoxAction(binding.textBox)
            true
        }
        binding.textBox.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) frameView.requestFocus()
        }
        binding.textBox.onTextCopyListener = {
            viewModel.sendClipboardText()
        }
    }

    private fun initKeys(binding: VirtualKeysBinding) {
        binding.keys.rowCount = pref.input.vkRowCount
        VirtualKeyLayoutConfig.getLayout(pref).forEach { vk ->
            val view = VirtualKeyViewFactory.create(binding.root.context, vk)
            binding.keys.addView(view)

            when {
                vk == VirtualKey.ToggleKeyboard -> {
                    view.setOnClickListener {
                        @Suppress("DEPRECATION")
                        ContextCompat.getSystemService(frameView.context, InputMethodManager::class.java)
                            ?.toggleSoftInput(0, 0)
                    }
                }
                vk == VirtualKey.CloseKeys -> {
                    view.setOnClickListener { hide(true) }
                }
                vk.keyAction != null -> {
                    if (view is ToggleButton)
                        initToggleKey(view, vk.keyAction)
                    else
                        initNormalKey(view, vk.keyAction)
                }
            }
        }
    }

    private fun initToggleKey(key: ToggleButton, keyAction: KeyAction) {
        key.setOnCheckedChangeListener { _, isChecked ->
            sendKeyAction(keyAction, isChecked)
            if (!isChecked) lockedToggleKeys.remove(key)
        }
        key.setOnLongClickListener {
            key.toggle()
            if (key.isChecked) lockedToggleKeys.add(key)
            true
        }

        // Special handling for Super/Meta keys with single tap
        if (keyAction.keyCode == KeyEvent.KEYCODE_META_LEFT || 
            keyAction.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (pref.input.vkUseSuperWithSingleTap) {
                key.setOnClickListener {
                    key.isChecked = true
                    key.isChecked = false
                }
            }
        }

        toggleKeys.add(key)
    }

    private fun initNormalKey(key: View, keyAction: KeyAction) {
        check(key !is ToggleButton) { "use initToggleKey()" }
        key.setOnClickListener { sendKeyAction(keyAction) }
        key.setOnTouchListener(RepeatKeyHandler.touchListener)
    }

    private fun handleTextBoxAction(textBox: EditText) {
        val text = textBox.text?.toString()?.takeIf { it.isNotEmpty() } ?: "\n"
        
        // Limit text length to prevent performance issues
        if (text.length > 1000) {
            Log.w("VirtualKeys", "Text too long (${text.length} chars), truncating")
            textBox.setText("")
            return
        }
        
        val events = keyCharMap.getEvents(text.toCharArray())

        // Release Meta keys to avoid interference with these key events
        releaseMetaKeys()

        // These events are sent to KeyHandler.onKeyEvent() instead of onVkKeyEvent()
        // to treat these like normal system key events.
        if (events == null)
            inputHandler.onKeyEvent(KeyEvent(SystemClock.uptimeMillis(), text, 0, 0))
        else
            events.forEach { inputHandler.onKeyEvent(it) }

        textBox.setText("")
    }

    private fun sendKeyAction(keyAction: KeyAction) {
        sendKeyAction(keyAction, true)
        sendKeyAction(keyAction, false)
    }

    private fun sendKeyAction(keyAction: KeyAction, isDown: Boolean) {
        val action = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        val now = SystemClock.uptimeMillis()
        
        val event = if (keyAction.metaState != 0) {
            // Create KeyEvent with meta state for shifted keys
            KeyEvent(now, now, action, keyAction.keyCode, 0, keyAction.metaState)
        } else {
            KeyEvent(action, keyAction.keyCode)
        }
        
        inputHandler.onVkKeyEvent(event)
    }

    /**
     * Shared handler for repeatable keys to avoid creating multiple listener instances.
     */
    private object RepeatKeyHandler {
        private val handler = Handler(Looper.getMainLooper())
        private val pendingRunnables = HashMap<View, Runnable>()

        val touchListener = View.OnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val runnable = object : Runnable {
                        override fun run() {
                            if (pendingRunnables.containsKey(v)) {
                                v.performClick()
                                handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
                            }
                        }
                    }
                    pendingRunnables[v] = runnable
                    handler.postDelayed(runnable, ViewConfiguration.getKeyRepeatTimeout().toLong())
                }

                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    pendingRunnables.remove(v)?.let { handler.removeCallbacks(it) }
                }
            }
            false
        }

        fun clear() {
            pendingRunnables.values.forEach { handler.removeCallbacks(it) }
            pendingRunnables.clear()
        }
    }
}

/**
 * Data class to hold key action information including meta state.
 * This fixes the issue where shifted keys were using incorrect keyCode values.
 */
data class KeyAction(
    val keyCode: Int,
    val metaState: Int = 0
) {
    companion object {
        fun simple(keyCode: Int) = KeyAction(keyCode)
        fun shifted(keyCode: Int) = KeyAction(keyCode, KeyEvent.META_SHIFT_ON)
    }
}

/**
 * NOTE: Names of these enums may be persisted in app preferences. So if any key name
 *       is ever modified, add a migration to handle old name.
 */
enum class VirtualKey(
    /**
     * [KeyAction] to be generated when this key is pressed.
     * Contains both keyCode and optional metaState for shifted keys.
     */
    val keyAction: KeyAction? = null,

    /**
     * If key name is not appropriate for UI, use this to set the label.
     */
    val label: String? = null,

    /**
     * If icon is set, this key will be rendered as an ImageButton.
     */
    val icon: Int? = null,

    /**
     * Short description of the key, if the label itself isn't sufficient.
     */
    val description: String? = null,

    val isToggle: Boolean = false,
) {

    // Special actions
    ToggleKeyboard(description = "Toggle keyboard", icon = R.drawable.ic_keyboard),
    CloseKeys(description = "Close virtual keys", icon = R.drawable.ic_clear),

    // Meta keys
    LeftShift(keyAction = KeyAction.simple(KeyEvent.KEYCODE_SHIFT_LEFT), 
              label = "", icon = R.drawable.ic_key_shift, isToggle = true),
    LeftCtrl(keyAction = KeyAction.simple(KeyEvent.KEYCODE_CTRL_LEFT), 
             label = "", icon = R.drawable.ic_key_ctrl, isToggle = true),
    LeftAlt(keyAction = KeyAction.simple(KeyEvent.KEYCODE_ALT_LEFT), 
            label = "", icon = R.drawable.ic_key_alt, isToggle = true),
    LeftSuper(keyAction = KeyAction.simple(KeyEvent.KEYCODE_META_LEFT), 
              label = "", icon = R.drawable.ic_super_key, isToggle = true),

    // Navigation & editing keys
    Esc(keyAction = KeyAction.simple(KeyEvent.KEYCODE_ESCAPE), 
        label = "Esc", icon = R.drawable.ic_key_esc),
    Tab(keyAction = KeyAction.simple(KeyEvent.KEYCODE_TAB), 
        label = "Tab", icon = R.drawable.ic_key_tab),
    Home(keyAction = KeyAction.simple(KeyEvent.KEYCODE_MOVE_HOME), 
         icon = R.drawable.ic_key_home),
    End(keyAction = KeyAction.simple(KeyEvent.KEYCODE_MOVE_END), 
        icon = R.drawable.ic_key_end),
    PgUp(keyAction = KeyAction.simple(KeyEvent.KEYCODE_PAGE_UP), 
         label = "PgUp"),
    PgDn(keyAction = KeyAction.simple(KeyEvent.KEYCODE_PAGE_DOWN), 
         label = "PgDn"),
    Insert(keyAction = KeyAction.simple(KeyEvent.KEYCODE_INSERT), 
           label = "Ins"),
    Delete(keyAction = KeyAction.simple(KeyEvent.KEYCODE_FORWARD_DEL), 
           label = "Del", icon = R.drawable.ic_key_delete),
    Space(keyAction = KeyAction.simple(KeyEvent.KEYCODE_SPACE), 
          label = "Space", icon = R.drawable.ic_key_space),
    Enter(keyAction = KeyAction.simple(KeyEvent.KEYCODE_ENTER), 
          icon = R.drawable.ic_key_enter),
    Backspace(keyAction = KeyAction.simple(KeyEvent.KEYCODE_DEL), 
              icon = R.drawable.ic_key_backspace),

    // Arrow keys
    Left(keyAction = KeyAction.simple(KeyEvent.KEYCODE_DPAD_LEFT), 
         icon = R.drawable.ic_key_arrow_left),
    Right(keyAction = KeyAction.simple(KeyEvent.KEYCODE_DPAD_RIGHT), 
          icon = R.drawable.ic_key_arrow_right),
    Up(keyAction = KeyAction.simple(KeyEvent.KEYCODE_DPAD_UP), 
       icon = R.drawable.ic_key_arrow_up),
    Down(keyAction = KeyAction.simple(KeyEvent.KEYCODE_DPAD_DOWN), 
         icon = R.drawable.ic_key_arrow_down),

    // Function keys
    F1(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F1), label = "F1"),
    F2(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F2), label = "F2"),
    F3(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F3), label = "F3"),
    F4(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F4), label = "F4"),
    F5(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F5), label = "F5"),
    F6(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F6), label = "F6"),
    F7(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F7), label = "F7"),
    F8(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F8), label = "F8"),
    F9(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F9), label = "F9"),
    F10(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F10), label = "F10"),
    F11(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F11), label = "F11"),
    F12(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F12), label = "F12"),

    // Letters (uppercase) - keeping them for compatibility
    A(keyAction = KeyAction.simple(KeyEvent.KEYCODE_A), label = "A"),
    B(keyAction = KeyAction.simple(KeyEvent.KEYCODE_B), label = "B"),
    C(keyAction = KeyAction.simple(KeyEvent.KEYCODE_C), label = "C"),
    D(keyAction = KeyAction.simple(KeyEvent.KEYCODE_D), label = "D"),
    E(keyAction = KeyAction.simple(KeyEvent.KEYCODE_E), label = "E"),
    F(keyAction = KeyAction.simple(KeyEvent.KEYCODE_F), label = "F"),
    G(keyAction = KeyAction.simple(KeyEvent.KEYCODE_G), label = "G"),
    H(keyAction = KeyAction.simple(KeyEvent.KEYCODE_H), label = "H"),
    I(keyAction = KeyAction.simple(KeyEvent.KEYCODE_I), label = "I"),
    J(keyAction = KeyAction.simple(KeyEvent.KEYCODE_J), label = "J"),
    K(keyAction = KeyAction.simple(KeyEvent.KEYCODE_K), label = "K"),
    L(keyAction = KeyAction.simple(KeyEvent.KEYCODE_L), label = "L"),
    M(keyAction = KeyAction.simple(KeyEvent.KEYCODE_M), label = "M"),
    N(keyAction = KeyAction.simple(KeyEvent.KEYCODE_N), label = "N"),
    O(keyAction = KeyAction.simple(KeyEvent.KEYCODE_O), label = "O"),
    P(keyAction = KeyAction.simple(KeyEvent.KEYCODE_P), label = "P"),
    Q(keyAction = KeyAction.simple(KeyEvent.KEYCODE_Q), label = "Q"),
    KeyR(keyAction = KeyAction.simple(KeyEvent.KEYCODE_R), label = "R"),
    S(keyAction = KeyAction.simple(KeyEvent.KEYCODE_S), label = "S"),
    T(keyAction = KeyAction.simple(KeyEvent.KEYCODE_T), label = "T"),
    U(keyAction = KeyAction.simple(KeyEvent.KEYCODE_U), label = "U"),
    V(keyAction = KeyAction.simple(KeyEvent.KEYCODE_V), label = "V"),
    W(keyAction = KeyAction.simple(KeyEvent.KEYCODE_W), label = "W"),
    X(keyAction = KeyAction.simple(KeyEvent.KEYCODE_X), label = "X"),
    Y(keyAction = KeyAction.simple(KeyEvent.KEYCODE_Y), label = "Y"),
    Z(keyAction = KeyAction.simple(KeyEvent.KEYCODE_Z), label = "Z"),

    // Numbers
    Num0(keyAction = KeyAction.simple(KeyEvent.KEYCODE_0), label = "0"),
    Num1(keyAction = KeyAction.simple(KeyEvent.KEYCODE_1), label = "1"),
    Num2(keyAction = KeyAction.simple(KeyEvent.KEYCODE_2), label = "2"),
    Num3(keyAction = KeyAction.simple(KeyEvent.KEYCODE_3), label = "3"),
    Num4(keyAction = KeyAction.simple(KeyEvent.KEYCODE_4), label = "4"),
    Num5(keyAction = KeyAction.simple(KeyEvent.KEYCODE_5), label = "5"),
    Num6(keyAction = KeyAction.simple(KeyEvent.KEYCODE_6), label = "6"),
    Num7(keyAction = KeyAction.simple(KeyEvent.KEYCODE_7), label = "7"),
    Num8(keyAction = KeyAction.simple(KeyEvent.KEYCODE_8), label = "8"),
    Num9(keyAction = KeyAction.simple(KeyEvent.KEYCODE_9), label = "9"),

    // Symbols - using KeyAction with metaState for shifted symbols
    LeftBracket(keyAction = KeyAction.simple(KeyEvent.KEYCODE_LEFT_BRACKET), 
                label = "["),
    RightBracket(keyAction = KeyAction.simple(KeyEvent.KEYCODE_RIGHT_BRACKET), 
                 label = "]"),
    CurlyBraceLeft(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_LEFT_BRACKET), 
                   label = "{"),
    CurlyBraceRight(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_RIGHT_BRACKET), 
                    label = "}"),
    Pipe(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_BACKSLASH), 
         label = "|"),
    Backslash(keyAction = KeyAction.simple(KeyEvent.KEYCODE_BACKSLASH), 
              label = "\\"),
    Colon(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_SEMICOLON), 
          label = ":"),
    Semicolon(keyAction = KeyAction.simple(KeyEvent.KEYCODE_SEMICOLON), 
              label = ";"),
    DoubleQuote(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_APOSTROPHE), 
                label = "\""),
    Apostrophe(keyAction = KeyAction.simple(KeyEvent.KEYCODE_APOSTROPHE), 
               label = "'"),
    Comma(keyAction = KeyAction.simple(KeyEvent.KEYCODE_COMMA), 
          label = ","),
    Period(keyAction = KeyAction.simple(KeyEvent.KEYCODE_PERIOD), 
           label = "."),
    Slash(keyAction = KeyAction.simple(KeyEvent.KEYCODE_SLASH), 
          label = "/"),
    LessThan(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_COMMA), 
             label = "<"),
    GreaterThan(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_PERIOD), 
                label = ">"),
    Question(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_SLASH), 
             label = "?"),
    Yen(keyAction = KeyAction.simple(KeyEvent.KEYCODE_YEN), 
        label = "¥"),

    // Additional symbols accessed via Shift
    Tilde(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_GRAVE), 
          label = "~"),
    Grave(keyAction = KeyAction.simple(KeyEvent.KEYCODE_GRAVE), 
          label = "`"),
    Exclamation(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_1), 
                label = "!"),
    AtSymbol(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_2), 
             label = "@"),
    HashSymbol(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_3), 
               label = "#"),
    Dollar(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_4), 
           label = "$"),
    Percent(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_5), 
            label = "%"),
    Caret(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_6), 
          label = "^"),
    Ampersand(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_7), 
              label = "&"),
    Asterisk(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_8), 
             label = "*"),
    ParenthesisLeft(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_9), 
                    label = "("),
    ParenthesisRight(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_0), 
                     label = ")"),
    Underscore(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_MINUS), 
               label = "_"),
    Plus(keyAction = KeyAction.shifted(KeyEvent.KEYCODE_EQUALS), 
         label = "+"),
    Minus(keyAction = KeyAction.simple(KeyEvent.KEYCODE_MINUS), 
          label = "-"),
    Equals(keyAction = KeyAction.simple(KeyEvent.KEYCODE_EQUALS), 
           label = "="),
}

/**
 * Users can change the layout of keys in app settings.
 * Layout configuration is stored as a simple list of key-names.
 */
object VirtualKeyLayoutConfig {

    private val DEFAULT_LAYOUT = listOf(
        VirtualKey.ToggleKeyboard, VirtualKey.CloseKeys,
        VirtualKey.Esc, VirtualKey.LeftSuper,
        VirtualKey.Tab, VirtualKey.LeftCtrl, 
        VirtualKey.LeftShift, VirtualKey.LeftAlt,
        VirtualKey.Home, VirtualKey.Left, 
        VirtualKey.Up, VirtualKey.Down, 
        VirtualKey.End, VirtualKey.Right, 
        VirtualKey.PgUp, VirtualKey.PgDn
    )

    /**
     * In older versions, before users could customize key layout, there was a pref to
     * 'Show all' keys. This layout is used for compatibility with that pref.
     */
    private val DEFAULT_LAYOUT_ALL = DEFAULT_LAYOUT +
        listOf(
            VirtualKey.Insert, VirtualKey.Delete,
            VirtualKey.F1, VirtualKey.F2, VirtualKey.F3,
            VirtualKey.F4, VirtualKey.F5, VirtualKey.F6,
            VirtualKey.F7, VirtualKey.F8, VirtualKey.F9,
            VirtualKey.F10, VirtualKey.F11, VirtualKey.F12
        )

    fun getDefaultLayout(pref: AppPreferences): List<VirtualKey> {
        return if (pref.input.vkShowAll) DEFAULT_LAYOUT_ALL else DEFAULT_LAYOUT
    }

    fun getLayout(pref: AppPreferences): List<VirtualKey> {
        runCatching {
            pref.input.vkLayout?.let { vkLayout ->
                vkLayout.split(',').map { VirtualKey.valueOf(it) }.let { keys ->
                    check(keys.isNotEmpty())
                    return keys
                }
            }
        }.onFailure { Log.e(javaClass.simpleName, "Error parsing key layout [${pref.input.vkLayout}]: ", it) }

        return getDefaultLayout(pref)
    }

    fun setLayout(pref: AppPreferences, keys: List<VirtualKey>) {
        if (keys == getDefaultLayout(pref) && pref.input.vkLayout != null) {
            // Restoring the defaults, so simply remove the pref.
            // Pref is only used if user changes the default layout.
            pref.input.vkLayout = null
            return
        }

        if (keys == getLayout(pref))
            return   // Nothing changed

        pref.input.vkLayout = keys.joinToString(",") { it.name }
    }
}

/**
 * Factory for creating individual key [View]s.
 */
object VirtualKeyViewFactory {

    /**
     * There are three types of Views that are generated:
     *
     * [ToggleButton] - if [key] is a toggle
     * [ImageButton]  - if [key] has an icon (label will be ignored)
     * [Button]       - in all other cases
     */
    fun create(context: Context, key: VirtualKey): View {
        val view = if (key.isToggle) createToggle(context, key) else createSimple(context, key)
        view.layoutParams = GridLayout.LayoutParams().apply {
            width = GridLayout.LayoutParams.WRAP_CONTENT
            height = GridLayout.LayoutParams.WRAP_CONTENT
            setGravity(Gravity.CENTER)
        }
        return view
    }

    private fun createSimple(context: Context, key: VirtualKey): View {
        return if (key.icon != null)
            ImageButton(context, null, 0, selectStyle(key))
                .apply {
                    setImageDrawable(ContextCompat.getDrawable(context, key.icon))
                    contentDescription = getDescription(key)
                }
        else
            Button(context, null, 0, selectStyle(key))
                .apply { text = getLabel(key) }
    }

    private fun createToggle(context: Context, key: VirtualKey): View {
        val view = ToggleButton(context, null, 0, selectStyle(key))
        view.isClickable = true

        if (key.icon != null) {
            view.setCompoundDrawablesRelativeWithIntrinsicBounds(key.icon, 0, 0, 0)
            view.contentDescription = getDescription(key)
            // Set label for toggle buttons with icons
            getLabel(key).let { label ->
                view.text = label
                view.textOff = label
                view.textOn = label
            }
        } else {
            val label = getLabel(key)
            view.text = label
            view.textOff = label
            view.textOn = label
        }

        return view
    }

    private fun selectStyle(key: VirtualKey): Int {
        if (key == VirtualKey.CloseKeys || key == VirtualKey.ToggleKeyboard)
            return R.style.VirtualKey_Special

        if (key.isToggle) {
            return if (key.icon != null) R.style.VirtualKey_Toggle_Image else R.style.VirtualKey_Toggle
        }

        return R.style.VirtualKey
    }

    private fun getLabel(virtualKey: VirtualKey) = virtualKey.label ?: virtualKey.name
    private fun getDescription(virtualKey: VirtualKey) = virtualKey.description ?: getLabel(virtualKey)
}

/**
 * Simple extension to add hook for Copy action.
 */
class VkEditText(context: Context, attributeSet: AttributeSet? = null) : 
    AppCompatEditText(context, attributeSet) {

    var onTextCopyListener: (() -> Unit)? = null

    override fun onTextContextMenuItem(id: Int): Boolean {
        val result = super.onTextContextMenuItem(id)
        if (result && (id == android.R.id.cut || id == android.R.id.copy)) {
            onTextCopyListener?.invoke()
        }
        return result
    }
}

/**
 * Stock [HorizontalScrollView] intercepts all scroll events irrespective of whether
 * it can actually scroll or not. It makes it unsuitable for use as child/parent of
 * another horizontally scrollable View, e.g. ViewPager.
 *
 * [NestableHorizontalScrollView] fixes this by only intercepting events when it is scrollable.
 */
class NestableHorizontalScrollView(context: Context, attributeSet: AttributeSet? = null) :
    HorizontalScrollView(context, attributeSet) {
    /**
     * Direction of current horizontal scrolling.
     * See [canScrollHorizontally].
     */
    private var hScrollDirection = 0
    private val gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            hScrollDirection = 0
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, 
            e2: MotionEvent, 
            distanceX: Float, 
            distanceY: Float
        ): Boolean {
            hScrollDirection = distanceX.sign.toInt()
            return true
        }
    })

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        if (hScrollDirection != 0 && !canScrollHorizontally(hScrollDirection))
            return false

        return super.onInterceptTouchEvent(ev)
    }
}

