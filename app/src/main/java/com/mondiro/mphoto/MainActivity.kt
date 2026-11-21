package com.mondiro.mphoto

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.mondiro.mphoto.ui.AppViewModel
import com.mondiro.mphoto.ui.MPhotoRoot
import com.mondiro.mphoto.ui.MPhotoTheme

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MPhotoTheme { MPhotoRoot(vm) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Được mở từ app khác (bảng "Mở bằng…"): xem ngay ảnh / video đó. */
    private fun handleIntent(i: Intent?) {
        val uri = i?.data ?: return
        if (i.action == Intent.ACTION_VIEW || i.action == "android.provider.action.REVIEW" || i.action == "com.android.camera.action.REVIEW") {
            vm.openUri(uri, i.type ?: contentResolver.getType(uri))
        }
    }
}
