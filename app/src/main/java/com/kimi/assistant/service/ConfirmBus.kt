package com.kimi.assistant.service

import com.kimi.assistant.command.CommandRouter

/**
 * Sesli akış ile onay ekranı arasında köprü.
 * Servis bir işlem hazırladığında buraya bırakır, ConfirmActivity okur.
 */
object ConfirmBus {

    @Volatile
    var pending: CommandRouter.PendingAction? = null
        private set

    @Volatile
    var description: String = ""
        private set

    fun request(action: CommandRouter.PendingAction, desc: String) {
        pending = action
        description = desc
    }

    fun clear() {
        pending = null
        description = ""
    }
}
