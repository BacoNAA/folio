package com.baconaa.folio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.baconaa.folio.records.LocalTextStore
import com.baconaa.folio.ui.FolioApp
import com.baconaa.folio.ui.FolioViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: FolioViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val store = LocalTextStore.get(applicationContext)
                return FolioViewModel(store, store, store, store) as T
            }
        })[FolioViewModel::class.java]
        setContent { FolioApp(model) }
    }

    override fun onStop() {
        model.save()
        super.onStop()
    }
}
