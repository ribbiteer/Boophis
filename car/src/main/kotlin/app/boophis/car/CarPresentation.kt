// SPDX-License-Identifier: GPL-3.0-or-later
// Boophis — local video player. Copyright (C) 2026 ribbiteer and contributors.
package app.boophis.car

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Full-screen Presentation on Boophis's private VirtualDisplay, hosting a ComposeView.
 * A Presentation is a Dialog, not a ComponentActivity, so the ViewTree owners are attached here.
 * Saved state is handed over across surface recreation via [saveState] / the constructor.
 */
internal class CarPresentation(
    outerContext: Context,
    display: Display,
    private val host: CarAppHost,
    private val env: CarEnv,
    private val restoredState: Bundle?,
    /** Shared across Presentation instances in one car session so ViewModels survive surfaces. */
    override val viewModelStore: ViewModelStore,
) : Presentation(outerContext, display, R.style.Theme_BoophisCarPresentation),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    var rootView: View? = null
        private set

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(restoredState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.setViewTreeLifecycleOwner(this@CarPresentation)
            decorView.setViewTreeSavedStateRegistryOwner(this@CarPresentation)
            decorView.setViewTreeViewModelStoreOwner(this@CarPresentation)
        }
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@CarPresentation))
            setViewTreeLifecycleOwner(this@CarPresentation)
            setViewTreeSavedStateRegistryOwner(this@CarPresentation)
            setViewTreeViewModelStoreOwner(this@CarPresentation)
            setContent { host.CarContent(env) }
        }
        rootView = compose
        setContentView(compose)
    }

    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        super.onStop()
    }

    /** Synthetic touch from [CarInputBridge]; coordinates are surface px == window px. */
    fun injectTouch(ev: MotionEvent): Boolean = window?.decorView?.dispatchTouchEvent(ev) ?: false

    fun saveState(): Bundle = Bundle().also { savedStateController.performSave(it) }

    /** Dismisses and moves the lifecycle to DESTROYED (disposes the composition). */
    fun destroy() {
        try {
            dismiss()
        } catch (_: Throwable) {
            // Display already gone; nothing to dismiss.
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        rootView = null
    }
}
