package com.lanxiuyun.lazyeat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * 主Activity，负责导航控制
 * 移除了相机和手势识别功能，这些功能应该在专门的Fragment中实现
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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
}