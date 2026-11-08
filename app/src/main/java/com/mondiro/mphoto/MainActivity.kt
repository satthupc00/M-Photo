package com.mondiro.mphoto

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
    }
}
