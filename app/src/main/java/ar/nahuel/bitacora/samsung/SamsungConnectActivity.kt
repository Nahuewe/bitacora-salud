package ar.nahuel.bitacora.samsung

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SamsungConnectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val status = SamsungHealthSource(applicationContext).connect(this@SamsungConnectActivity)
            setResult(RESULT_OK, Intent().putExtra(SamsungIpc.KEY_STATUS, SamsungIpc.encode(status)))
            finish()
        }
    }
}
