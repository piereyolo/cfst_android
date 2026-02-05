package com.cloudflare.speedtest.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {
    private lateinit var sharedViewModel: SharedViewModel

    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)



        sharedViewModel = SharedViewModel()



//        if (checkStoragePermission()) {
//
//        } else {
//            // 存储权限申请
//            requestStoragePermission()
//        }

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // 创建 ViewPager 适配器
        val adapter = ViewPagerAdapter(this)
        adapter.addFragment(IPListFragment(), "IP 列表")
       // adapter.addFragment(SecondTabFragment(), "Tab 2")
        adapter.addFragment(IPTestFragment(), "IP 测试")  // 改为测试Fragment

        adapter.addFragment(ResultFragment(), "测试结果")  // 添加第三个Tab

        viewPager.adapter = adapter

        viewPager.isUserInputEnabled = false

        // 连接 TabLayout 和 ViewPager
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
    }



    // 检查存储权限
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 及以上
            android.os.Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 请求存储权限
    private fun requestStoragePermission() {
        // 保存待测试的参数


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (API 30) 及以上需要 MANAGE_EXTERNAL_STORAGE 权限
            AlertDialog.Builder(this)
                .setTitle("需要存储权限")
                .setMessage("应用需要访问所有文件的权限来保存测试结果到 /storage/emulated/0/iptestresult.csv\n\n请点击确定后在系统设置中授予权限。")
                .setPositiveButton("确定") { _, _ ->
                    try {
                        val intent = android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        val uri = android.net.Uri.parse("package:${this.packageName}")
                        startActivity(android.content.Intent(intent).setData(uri))
                    } catch (e: Exception) {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            // Android 10 及以下请求 WRITE_EXTERNAL_STORAGE 权限
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    // 处理权限请求结果
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予
                Toast.makeText(this, "存储权限已授予", Toast.LENGTH_SHORT).show()

            } else {
                // 权限被拒绝
                Toast.makeText(this, "需要存储权限来保存测试结果", Toast.LENGTH_SHORT).show()
            }

        }
    }

    // 处理从系统设置返回的权限状态
    override fun onResume() {
        super.onResume()
        // 检查是否从系统设置返回，如果已经获得权限，则继续测试
    }

}