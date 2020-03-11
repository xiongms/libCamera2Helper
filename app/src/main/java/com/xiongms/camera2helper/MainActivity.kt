package com.xiongms.camera2helper

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import com.xiongms.libcamera2helper.CameraHelper
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private val perms = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.RECORD_AUDIO
    )

    private var mCamera2Helper: CameraHelper? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }


    /**
     * 请求APP所需权限
     */
    private fun requestPermissions() {
        if (!hasPermission(perms)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(perms, 101)
            }
        }
    }

    /**
     * 权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            for (item in grantResults) {
                if (item != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@MainActivity, "请在系统设置中允许所有权限", Toast.LENGTH_LONG).show()
                    requestPermissions()
                    return
                }
            }

            initCarema()
        }
    }

    /**
     * 判断是否有权限
     */
    private fun hasPermission(perms: Array<String>): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (perm in perms) {
                if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        } else {
            return true
        }
    }

    override fun onStart() {
        super.onStart()

        if (hasPermission(perms)) {
            initCarema()
        } else {
            requestPermissions()
        }
    }

    private fun initCarema() {
        mCamera2Helper = CameraHelper(this, textureView)
        mCamera2Helper!!.setOnPreviewSurfaceCallback(object :
            CameraHelper.OnPreviewSurfaceCallback {
            override fun onPreviewUpdate(bitmap: Bitmap) {
                // 实时获取摄像头预览图片
                Log.e(TAG, "获取到图片信息")
            }
        })
    }

    fun startRecord(view: View) {
        mCamera2Helper?.startRecord(object : CameraHelper.OnRecordListener {
            override fun onSavedRecord(file: File) {
                Log.e(TAG, "视频保存成功")
            }

            override fun onError(error: Throwable) {
                Toast.makeText(this@MainActivity, "视频录制失败", Toast.LENGTH_LONG).show()
                error.printStackTrace()
                // 重新录制
                mCamera2Helper?.startRecord(this)
            }
        })
    }

    fun stopRecord(view: View) {
        val videoFile = File(this.cacheDir, "video_${SystemClock.elapsedRealtime()}.mp4")
        if (mCamera2Helper!!.stopRecord(videoFile)) {
            Toast.makeText(this@MainActivity, "视频保存成功", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this@MainActivity, "视频保存失败", Toast.LENGTH_LONG).show()
        }
    }
}
