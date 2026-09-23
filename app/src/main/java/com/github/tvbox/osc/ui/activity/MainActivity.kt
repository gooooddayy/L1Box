package com.github.tvbox.osc.ui.activity

import android.os.Process
import android.view.MenuItem
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
import androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.ToastUtils
import com.github.tvbox.osc.base.BaseVbActivity
import com.github.tvbox.osc.constant.IntentKey
import com.github.tvbox.osc.databinding.ActivityMainBinding
import com.github.tvbox.osc.ui.fragment.GridFragment
import com.github.tvbox.osc.ui.fragment.HomeFragment
import com.github.tvbox.osc.ui.fragment.MyFragment
import com.github.tvbox.osc.util.Utils
import kotlin.system.exitProcess

class MainActivity : BaseVbActivity<ActivityMainBinding>() {

    var fragments = listOf(HomeFragment(),MyFragment())
    var useCacheConfig = false
    private var exitTime = 0L

    override fun init() {

        useCacheConfig = intent.extras?.getBoolean(IntentKey.CACHE_CONFIG_CHANGED, false)?:false

        // 必须用 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT：废弃的单参构造器默认是
        // BEHAVIOR_SET_USER_VISIBLE_HINT，切 tab 只回调 setUserVisibleHint 而**不走 onResume**，
        // 导致"从'我的'切回首页"时 HomeFragment.onResume 不触发、首页不刷新。
        mBinding.vp.adapter = object : FragmentPagerAdapter(
            supportFragmentManager,
            BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
        ) {
            override fun getItem(position: Int): Fragment {
                return fragments[position]
            }

            override fun getCount(): Int {
                return fragments.size
            }
        }
        // 保留全部页面的视图（切到"我的"再切回首页不销毁 HomeFragment 的 View）：
        // 刷新由 BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT 驱动的 onResume 负责，见上方注释
        mBinding.vp.offscreenPageLimit = fragments.size

        mBinding.bottomNav.setOnNavigationItemSelectedListener { menuItem: MenuItem ->
            mBinding.vp.setCurrentItem(menuItem.order, false)
            true
        }
        mBinding.vp.addOnPageChangeListener(object : SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                mBinding.bottomNav.menu.getItem(position).setChecked(true)
            }
        })
        // 清掉底部「首页 / 我的」两个 tab 的长按气泡：Material 的 NavigationBarItemView 会
        // 自动把菜单标题设成 tooltip。必须**递归整棵树**才清得到 —— bottomNav 的直接子级
        // 只有一个菜单容器，真正带 tooltip 的 item View 在它里面，只遍历直接子级等于清了个容器
        // （上一版就是这么漏的，气泡照旧弹）。递归里还会给可点击的 View 挂长按短路做兜底，
        // 之后 Material 再在什么时机重设都弹不出来。post 到布局完成，保证 item View 已建出来。
        mBinding.bottomNav.post {
            Utils.disableTooltipDeep(mBinding.bottomNav)
        }
    }

    override fun onBackPressed() {
        if (mBinding.vp.currentItem == 1) {
            mBinding.vp.currentItem = 0
            return
        }
        val homeFragment = fragments[0] as HomeFragment
        if (!homeFragment.isAdded) { // 资源不足销毁重建时未挂载到activity时getChildFragmentManager会崩溃
            confirmExit()
            return
        }
        val childFragments = homeFragment.allFragments
        if (childFragments.isEmpty()) { //加载中(没有tab)
            confirmExit()
            return
        }
        val fragment: Fragment = childFragments[homeFragment.tabIndex]
        if (fragment is GridFragment) { // 首页数据源动态加载的tab
            if (!fragment.restoreView()) { // 有回退的view,先回退(AList等文件夹列表),没有可回退的,返到主页tab
                if (!homeFragment.scrollToFirstTab()) {
                    confirmExit()
                }
            }
        } else {
            confirmExit()
        }
    }

    private fun confirmExit() {
        if (System.currentTimeMillis() - exitTime > 2000) {
            ToastUtils.showShort("再按一次退出程序")
            exitTime = System.currentTimeMillis()
        } else {
            ActivityUtils.finishAllActivities(true)
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }
    }
}