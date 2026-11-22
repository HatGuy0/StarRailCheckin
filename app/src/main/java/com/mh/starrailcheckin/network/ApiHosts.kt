package com.mh.starrailcheckin.network

/**
 * 统一收口的接口域名、第三方数据源与 GitHub 备用代理。
 *
 * 新增 / 切换官方域名、增补数据源或国内代理时，只需改这里，
 * 避免 host 散落在各页面与网络层难以维护。
 */
object ApiHosts {

    // —— 米哈游 / 米游社官方接口域名 ——
    const val TAKUMI = "https://api-takumi.mihoyo.com"
    const val TAKUMI_MIYOUSHE = "https://api-takumi.miyoushe.com"
    const val TAKUMI_RECORD = "https://api-takumi-record.mihoyo.com"
    const val BBS_API = "https://bbs-api.mihoyo.com"
    const val BBS_API_MIYOUSHE = "https://bbs-api.miyoushe.com"
    const val PASSPORT_API = "https://passport-api.mihoyo.com"
    const val PASSPORT_API_MIYOUSHE = "https://passport-api.miyoushe.com"
    const val WEBSTATIC = "https://webstatic.mihoyo.com"
    const val ACT = "https://act.mihoyo.com"
    const val APP = "https://app.mihoyo.com"
    const val USER_MIYOUSHE = "https://user.miyoushe.com"
    const val BBS_MIHOYO = "https://bbs.mihoyo.com"
    const val BBS_API_STATIC_MIYOUSHE = "https://bbs-api-static.miyoushe.com"
    const val WWW_MIYOUSHE = "https://www.miyoushe.com"
    const val WWW_MIHOYO = "https://www.mihoyo.com"
    const val PUBLIC_DATA = "https://public-data-api.mihoyo.com"
    const val PUBLIC_OPERATION_HKRPG = "https://public-operation-hkrpg.mihoyo.com"

    // —— 第三方数据源 / CDN ——
    const val MIHOMO_API = "https://api.mihomo.me"
    const val ENKA_REFERER = "https://enka.network/"
    const val SCOREMYRELIC_BASE = "https://api.scoremyrelic.com/api/leaderboards"
    const val VIZUAL_BASE = "https://vizualabstract.github.io/StarRailStaticAPI"
    const val JSDELIVERT_MAR7TH = "https://cdn.jsdelivr.net/gh/Mar-7th/StarRailRes@master"
    const val GITHUB_RAW_MAR7TH = "https://raw.githubusercontent.com/Mar-7th/StarRailRes/master"
    const val GITHUB_RAW_MIAO = "https://raw.githubusercontent.com/yoimiya-kokomi/miao-plugin/master"
    const val JSDELIVERT_MIAO = "https://cdn.jsdelivr.net/gh/yoimiya-kokomi/miao-plugin@master"

    /** GitHub raw / Releases 国内代理前缀（按连通性排序依次回退，末尾保留斜杠）。 */
    val GH_PROXIES = listOf(
        "https://gh-proxy.org/",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/"
    )
}
