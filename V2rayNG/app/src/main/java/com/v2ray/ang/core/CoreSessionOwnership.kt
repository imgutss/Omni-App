package com.v2ray.ang.core

/**
 * A scanner-owned session is live while registered and not stopping; a foreground session is
 * live only while selected. Neither kind may borrow the other kind's global readiness state.
 */
internal fun ownsCoreSession(isolated: Boolean, selected: Boolean, registered: Boolean, stopRequested: Boolean): Boolean =
    if (isolated) registered && !stopRequested else selected
