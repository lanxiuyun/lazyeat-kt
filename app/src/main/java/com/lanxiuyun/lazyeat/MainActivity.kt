package com.lanxiuyun.lazyeat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lanxiuyun.lazyeat.service.ServiceManager
import com.lanxiuyun.lazyeat.service.SwipeAccessibilityService
import com.lanxiuyun.lazyeat.utils.LogUtils
import com.lanxiuyun.lazyeat.HandOverlayView

/**
 * 主Activity - 吃饭刷抖音助手
 * 负责导航控制和权限请求
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 20
        private const val ACCESSIBILITY_REQUEST_CODE = 1002
        private const val PREFS_NAME = "lazyeat_prefs"
        private const val PREF_SKIP_ACCESSIBILITY = "skip_accessibility_prompt"
        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
        // HandOverlayView的静态引用
        var handOverlayView: HandOverlayView? = null
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

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
            // 权限已获取，检查辅助功能
            checkAndRequestAccessibility()
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

    override fun onResume() {
        super.onResume()
        // 从设置页面返回时检查辅助功能状态
        if (allPermissionsGranted() && !isAccessibilityServiceEnabled()) {
            // 检查是否已跳过提示
            if (!prefs.getBoolean(PREF_SKIP_ACCESSIBILITY, false)) {
                // 只在服务运行时提醒
                if (ServiceManager.isGestureRecognitionServiceRunning(this)) {
                    Toast.makeText(this, "⚠️ 辅助功能未开启，手势无法控制滑动", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 检查并请求辅助功能权限
     */
    private fun checkAndRequestAccessibility() {
        if (isAccessibilityServiceEnabled()) {
            LogUtils.i(TAG, "辅助功能已开启")
            startGestureService()
            return
        }

        // 检查用户是否选择过"不再询问"
        if (prefs.getBoolean(PREF_SKIP_ACCESSIBILITY, false)) {
            LogUtils.i(TAG, "用户已选择跳过辅助功能引导")
            startGestureService()
            return
        }

        // 显示引导对话框
        AlertDialog.Builder(this)
            .setTitle("需要开启辅助功能")
            .setMessage("为了执行滑动操作，需要开启辅助功能服务。\n\n" +
                    "开启步骤：\n" +
                    "1. 点击'去开启'\n" +
                    "2. 找到'吃饭刷抖音助手'\n" +
                    "3. 开启服务开关\n" +
                    "4. 返回应用即可使用")
            .setPositiveButton("去开启") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("稍后再说") { _, _ ->
                Toast.makeText(this, "未开启辅助功能，手势无法执行滑动", Toast.LENGTH_LONG).show()
                startGestureService()
            }
            .setNeutralButton("不再询问") { _, _ ->
                prefs.edit().putBoolean(PREF_SKIP_ACCESSIBILITY, true).apply()
                Toast.makeText(this, "可在设置中手动开启辅助功能", Toast.LENGTH_SHORT).show()
                startGestureService()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 检查辅助功能是否已开启
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        LogUtils.d(TAG, "已启用的辅助功能服务: $enabledServices")

        if (enabledServices.isNullOrEmpty()) {
            LogUtils.d(TAG, "没有启用的辅助功能服务")
            return false
        }

        // 构造完整的服务名称（格式：包名/类名）
        val serviceName = "$packageName/.service.SwipeAccessibilityService"
        val serviceNameFull = "$packageName/${SwipeAccessibilityService::class.java.name}"

        LogUtils.d(TAG, "检查服务名称: $serviceName")
        LogUtils.d(TAG, "检查服务名称(完整): $serviceNameFull")

        // 检查多种可能的格式（适配不同系统）
        val isEnabled = when {
            enabledServices.contains(serviceName) -> {
                LogUtils.d(TAG, "匹配到短格式服务名")
                true
            }
            enabledServices.contains(serviceNameFull) -> {
                LogUtils.d(TAG, "匹配到完整服务名")
                true
            }
            enabledServices.contains("SwipeAccessibilityService") -> {
                LogUtils.d(TAG, "匹配到类名")
                true
            }
            enabledServices.split(":").any { it.contains("lazyeat") && it.contains("Swipe") } -> {
                LogUtils.d(TAG, "匹配到包含lazyeat和Swipe的服务")
                true
            }
            else -> false
        }

        LogUtils.i(TAG, "辅助功能服务是否启用: $isEnabled")
        return isEnabled
    }

    /**
     * 检查辅助功能状态并提示
     */
    private fun checkAccessibilityStatus() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "⚠️ 辅助功能未开启，手势无法控制滑动", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开辅助功能设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, ACCESSIBILITY_REQUEST_CODE)
        } catch (e: Exception) {
            LogUtils.e(TAG, "打开辅助功能设置失败: ${e.message}")
            Toast.makeText(this, "请手动前往设置 -> 辅助功能开启服务", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 启动手势识别服务
     */
    private fun startGestureService() {
        LogUtils.i(TAG, "启动手势识别服务")
        ServiceManager.startGestureRecognitionService(this)
        Toast.makeText(this, "手势识别服务已启动", Toast.LENGTH_SHORT).show()
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
                // 基本权限已获取，检查辅助功能
                checkAndRequestAccessibility()
            } else {
                // 权限被拒绝，显示提示
                Toast.makeText(this, "需要相机权限才能运行", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 辅助功能权限回调
        if (requestCode == ACCESSIBILITY_REQUEST_CODE) {
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "✅ 辅助功能已开启，可以开始使用了！", Toast.LENGTH_SHORT).show()
                startGestureService()
            } else {
                Toast.makeText(this, "⚠️ 未开启辅助功能，手势无法控制滑动", Toast.LENGTH_LONG).show()
                startGestureService()
            }
        }
    }
}
