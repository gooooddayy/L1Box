package com.github.tvbox.osc.base

import android.view.LayoutInflater
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

abstract class BaseVbActivity<T : ViewBinding> : BaseActivity() {

    protected lateinit var mBinding: T
    public override fun getLayoutResID(): Int {
        return -1
    }

    /**
     * 供子类在 onDestroy 中判断绑定是否成功。
     * 必须在基类中访问: 子类无法直接引用父类属性的 backing field。
     */
    protected fun isBindingInitialized(): Boolean = ::mBinding.isInitialized

    /**
     * 初始化viewBinding
     * 整体置于 try 内: 泛型强转与反射 inflate 均可能失败,
     * 失败后结束页面, 避免后续访问未初始化的 mBinding 引发二次崩溃
     */
    override fun initVb() {
        try {
            val type = javaClass.genericSuperclass as ParameterizedType
            val cls = type.actualTypeArguments[0] as Class<*>
            val inflate = cls.getDeclaredMethod("inflate", LayoutInflater::class.java)
            mBinding = inflate.invoke(null, layoutInflater) as T
            setContentView(mBinding.root)
        } catch (e: Throwable) {
            e.printStackTrace()
            finish()
        }
    }
}