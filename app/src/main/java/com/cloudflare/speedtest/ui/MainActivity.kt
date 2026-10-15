package com.cloudflare.speedtest.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.cloudflare.speedtest.core.RegionData
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // 地区码 → 中文地区名映射（数据在 res/raw/regions.json），幂等
        RegionData.init(this)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)

        // 创建 ViewPager 适配器
        val adapter = ViewPagerAdapter(this)
        adapter.addFragment(IPListFragment(), "IP 列表")
        adapter.addFragment(IPTestFragment(), "IP 测试")
        adapter.addFragment(ResultFragment(), "测试结果")
        adapter.addFragment(PushIPToServerTestFragment(), "下载测试")
        adapter.addFragment(VerifyNodeFragment(), "真连接验证")

        viewPager.adapter = adapter

        viewPager.isUserInputEnabled = false

        // 连接 TabLayout 和 ViewPager
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()
    }
}
