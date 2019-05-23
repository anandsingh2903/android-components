/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package mozilla.components.feature.app.links

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import androidx.fragment.app.FragmentManager
import mozilla.components.browser.session.SelectionAwareSessionObserver
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.feature.app.links.RedirectDialogFragment.Companion.FRAGMENT_TAG
import mozilla.components.support.base.feature.LifecycleAwareFeature

/**
 * This feature implements use cases for detecting and handling redirects to external apps. The user
 * is asked to confirm her intention before leaving the app. These include the Android Intents,
 * custom schemes and support for [Intent.CATEGORY_BROWSABLE] `http(s)` URLs.
 *
 * In the case of Android Intents that are not installed, and with no fallback, the user is prompted
 * to search the installed market place.
 *
 * It provides use cases to detect and open links openable in third party non-browser apps.
 *
 * It provides a [RequestInterceptor] to do the detection and asking of consent.
 *
 * It requires: a [Context], and a [FragmentManager].
 *
 * A [Boolean] flag is provided at construction to allow the feature and use cases to be landed without
 * adjoining UI. The UI will be activated in https://github.com/mozilla-mobile/android-components/issues/2974
 * and https://github.com/mozilla-mobile/android-components/issues/2975.
 */
class AppLinksFeature(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val sessionId: String? = null,
    private val interceptLinkClicks: Boolean = false,
    private val fragmentManager: FragmentManager? = null,
    private var dialog: RedirectDialogFragment = SimpleRedirectDialogFragment.newInstance(),
    private val useCases: AppLinksUseCases = AppLinksUseCases(context)
) : LifecycleAwareFeature {

    private val observer: SelectionAwareSessionObserver = object : SelectionAwareSessionObserver(sessionManager) {
        override fun onLoadRequest(session: Session, triggeredByUserInteraction: Boolean) {
            if (!triggeredByUserInteraction) {
                return
            }

            val url = session.url
            val redirect = useCases.appLinkRedirect.invoke(url)

            if (redirect.hasExternalApp()) {
                handleRedirect(redirect, session)
            }
        }
    }

    /**
     * Starts observing app links on the selected session.
     */
    override fun start() {
        if (interceptLinkClicks) {
            observer.observeIdOrSelected(sessionId)
        }
        findPreviousDialogFragment()?.let {
            reAttachOnConfirmRedirectListener(it)
        }
    }

    override fun stop() {
        if (interceptLinkClicks) {
            observer.stop()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleRedirect(redirect: AppLinkRedirect, session: Session) {
        if (!redirect.hasExternalApp()) {
            handleFallback(redirect, session)
        }

        val doOpenApp = {
            useCases.openAppLink.invoke(redirect)
        }

        if (!session.private) {
            doOpenApp()
            return
        }

        dialog.setAppLinkRedirect(redirect)
        dialog.onConfirmRedirect = doOpenApp
        dialog.onDismiss(object : DialogInterface {
            override fun dismiss() {
                handleFallback(redirect, session)
            }

            override fun cancel() {
                dismiss()
            }
        })

        if (!isAlreadyADialogCreated()) {
            dialog.show(fragmentManager, FRAGMENT_TAG)
        }
    }

    private fun handleFallback(redirect: AppLinkRedirect, session: Session) {
        redirect.webUrl?.let {
            sessionManager.getOrCreateEngineSession(session).loadUrl(it)
        }
    }

    private fun isAlreadyADialogCreated(): Boolean {
        return findPreviousDialogFragment() != null
    }

    private fun reAttachOnConfirmRedirectListener(previousDialog: RedirectDialogFragment?) {
        previousDialog?.apply {
            this@AppLinksFeature.dialog = this
        }
    }

    private fun findPreviousDialogFragment(): RedirectDialogFragment? {
        return fragmentManager?.findFragmentByTag(FRAGMENT_TAG) as? RedirectDialogFragment
    }
}
