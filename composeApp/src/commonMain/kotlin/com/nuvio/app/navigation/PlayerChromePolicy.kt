package com.nuvio.app.navigation

/**
 * Routes that get full-screen player chrome: landscape lock + hidden system bars. Upstream applies
 * this in its App shell keyed on the route (not inside the player screen), so the lock holds while
 * the player exits; the fork's composition root (App.kt) applies the same rule.
 */
internal fun wantsPlayerChrome(route: AppRoute?): Boolean = route is PlayerRoute
