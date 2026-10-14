package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallKitActions

class CallKitActionsAdapter(private val session: CallSession) : CallKitActions {
    override fun accept(callId: String) {
        val state = session.state.value
        if (state.callId == callId && state.phase == CallPhase.Incoming) session.accept()
    }

    override fun endOrDecline(callId: String) {
        val state = session.state.value
        if (state.callId != callId || state.phase is CallPhase.Ended || state.phase == CallPhase.Idle) return
        if (state.phase == CallPhase.Incoming) session.decline() else session.hangup()
    }

    override fun setMuted(muted: Boolean) = session.setMuted(muted)
}
