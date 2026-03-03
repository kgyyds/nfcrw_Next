package com.kgapp.kptool

data class LoginInfo(
    val cardCode: String,
    val cardNo: String?,
    val remainTimes: Int,
    val allowAmount: Int
)

data class RuntimeCardInfo(
    val cardCode: String,
    val cardNo: String?,
    val sector: Int,
    val keysHex: List<String>,
    val keys: List<ByteArray>
)

object AppSession {
    @Volatile
    private var loginInfo: LoginInfo? = null

    @Volatile
    private var runtimeInfo: RuntimeCardInfo? = null

    fun updateSession(login: LoginInfo, runtime: RuntimeCardInfo) {
        loginInfo = login
        runtimeInfo = runtime
    }

    fun getLoginInfo(): LoginInfo? = loginInfo

    fun getRuntimeInfo(): RuntimeCardInfo? = runtimeInfo

    fun clear() {
        loginInfo = null
        runtimeInfo = null
    }
}
