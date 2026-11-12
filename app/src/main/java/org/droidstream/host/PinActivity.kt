package org.droidstream.host

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class PinActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 24)
        }
        val tv = TextView(this).apply {
            text = "Inserisci il PIN di 4 cifre mostrato in Moonlight"
            textSize = 16f
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setHint("1234")
        }
        val ok = Button(this).apply { text = "Accoppia" }
        ok.setOnClickListener {
            val p = et.text.toString().trim()
            if (p.length == 4) {
                Pairing.submitPin(p)
                finish()
            }
        }
        layout.addView(tv); layout.addView(et); layout.addView(ok)
        setContentView(layout)
    }
}
