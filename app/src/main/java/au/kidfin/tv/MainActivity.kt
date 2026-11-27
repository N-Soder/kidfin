// SPDX-License-Identifier: GPL-3.0-or-later

package au.kidfin.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import au.kidfin.tv.ui.KidFinApp
import au.kidfin.tv.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel((application as KidFinApplication).container) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KidFinApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.onAppStarted()
    }

    override fun onStop() {
        // Home or another app: stop playback and freeze the allowance before going invisible.
        vm.onAppStopped()
        super.onStop()
    }
}
