package com.lanxiuyun.lazyeat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lanxiuyun.lazyeat.service.ServiceManager

/**
 * 主Activity，负责导航控制和权限请求
 * 移除了相机和手势识别功能，这些功能应该在专门的Fragment中实现
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 20
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 检查并请求权限
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        } else {
            // 权限已获取，自动启动手势识别服务
            ServiceManager.startGestureRecognitionService(this)
        }

        // 设置底部导航
        val navView: BottomNavigationView = findViewById(R.id.nav_view)
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        
        // 配置导航栏，只保留Home作为顶级目标
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_home)
        )
        
        // 设置ActionBar与导航控制器的关联
        setupActionBarWithNavController(navController, appBarConfiguration)
        // 设置底部导航与导航控制器的关联
        navView.setupWithNavController(navController)
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
                // 权限已获取，自动启动手势识别服务
                ServiceManager.startGestureRecognitionService(this)
            } else {
                // 权限被拒绝，显示提示
                // 这里可以显示一个对话框解释为什么需要这些权限
            }
        }
    }
}