package com.casa.tv

import android.app.Activity
import android.os.Bundle

/** Shows the pending (or last) note when the app may not draw over other apps. */
class NoteActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val note = CasaService.takePending() ?: CasaService.lastNote
        if (note == null) { finish(); return }
        setContentView(NoteView.build(this, note) { reply ->
            CasaService.answer(this, note, reply)
            finish()
        })
    }
}
