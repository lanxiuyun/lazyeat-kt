package com.lanxiuyun.lazyeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lanxiuyun.lazyeat.service.MousePointerManager
import com.lanxiuyun.lazyeat.service.ServiceManager
import com.lanxiuyun.lazyeat.utils.LogUtils
import com.lanxiuyun.lazyeat.HandOverlayView

/**
 * 主Activity，负责导航控制和权限请求
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 20
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
        // 添加HandOverlayView的静态引用
        var handOverlayView: HandOverlayView? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取HandOverlayView引用
        handOverlayView = findViewById(R.id.hand_overlay)

        // 检查并请求权限
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        } else {
            // 权限已获取，检查悬浮窗权限
            checkAndRequestOverlayPermission()
        }

        // 设置底部导航
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        
        // 配置导航栏
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home
            )
        )
        
        // 设置ActionBar与导航控制器的关联
        setupActionBarWithNavController(navController, appBarConfiguration)
        // 设置底部导航与导航控制器的关联
        navView.setupWithNavController(navController)
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // 请求悬浮窗权限
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } catch (e: Exception) {
                LogUtils.e(TAG, "请求悬浮窗权限失败: ${e.message}")
                Toast.makeText(this, "请求权限失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            // 已有悬浮窗权限，启动服务
            startServices()
        }
    }

    private fun startServices() {
        // 启动手势识别服务
        ServiceManager.startGestureRecognitionService(this)
        // 启动悬浮窗服务
        MousePointerManager.startMousePointerService(this)
    }
    
    /**
     * 检查是否已获得所有必要权限
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                // 基本权限已获取，检查悬浮窗权限
                checkAndRequestOverlayPermission()
            } else {
                // 权限被拒绝，显示提示
                Toast.makeText(this, "需要相机权限才能运行", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Settings.canDrawOverlays(this)) {
                // 用户授予了悬浮窗权限
                startServices()
            } else {
                // 用户拒绝了悬浮窗权限
                Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}