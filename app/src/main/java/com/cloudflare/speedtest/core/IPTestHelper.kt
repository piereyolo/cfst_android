package com.cloudflare.speedtest.core

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.system.measureTimeMillis
import kotlin.text.ifEmpty
import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.TimeoutException

import java.util.concurrent.atomic.AtomicLong


object IPTestHelper {

    fun getRegionChineseName(code: String): String {
        return regionMap[code.uppercase()] ?: "未知地区"
    }

    // Cloudflare 数据中心地区码映射（部分常见）
    val regionMap = mapOf(
        "AAE" to "阿尔及利亚安纳巴",
        "ABJ" to "科特迪瓦阿比让",
        "ABQ" to "美国阿尔伯克基",
        "ACC" to "加纳阿克拉",
        "ACX" to "中国兴义",
        "ADB" to "土耳其伊兹密尔",
        "ADD" to "埃塞俄比亚亚的斯亚贝巴",
        "ADL" to "澳大利亚阿德莱德",
        "AGR" to "印度阿格拉",
        "AKL" to "新西兰奥克兰",
        "AKX" to "哈萨克斯坦阿克托别",
        "ALA" to "哈萨克斯坦阿拉木图",
        "ALG" to "阿尔及利亚阿尔及尔",
        "AMD" to "印度艾哈迈达巴德",
        "AMM" to "约旦安曼",
        "AMS" to "荷兰阿姆斯特丹",
        "ANC" to "美国安克雷奇",
        "ARI" to "智利阿里卡",
        "ARN" to "瑞典斯德哥尔摩",
        "ARU" to "巴西阿拉萨图巴",
        "ASK" to "科特迪瓦亚穆苏克罗",
        "ASU" to "巴拉圭亚松森",
        "ATH" to "希腊雅典",
        "ATL" to "美国亚特兰大",
        "AUS" to "美国奥斯汀",
        "BAH" to "巴林麦纳麦",
        "BAQ" to "哥伦比亚巴兰基亚",
        "BBI" to "印度布巴内斯瓦尔",
        "BCN" to "西班牙巴塞罗那",
        "BEG" to "塞尔维亚贝尔格莱德",
        "BEL" to "巴西贝伦",
        "BEY" to "黎巴嫩贝鲁特",
        "BGI" to "巴巴多斯布里奇敦",
        "BGR" to "美国班戈",
        "BGW" to "伊拉克巴格达",
        "BHY" to "中国北海",
        "BKK" to "泰国曼谷",
        "BLR" to "印度班加罗尔",
        "BNA" to "美国纳什维尔",
        "BNE" to "澳大利亚布里斯班",
        "BNU" to "巴西布卢梅瑙",
        "BOD" to "法国波尔多",
        "BOG" to "哥伦比亚波哥大",
        "BOM" to "印度孟买",
        "BOS" to "美国波士顿",
        "BRU" to "比利时布鲁塞尔",
        "BSB" to "巴西巴西利亚",
        "BSR" to "伊拉克巴士拉",
        "BTS" to "斯洛伐克布拉迪斯拉发",
        "BUD" to "匈牙利布达佩斯",
        "BUF" to "美国布法罗",
        "BWN" to "文莱斯里巴加湾市",
        "CAI" to "埃及开罗",
        "CAN" to "中国广州",
        "CAW" to "巴西坎波斯多斯戈伊塔卡泽斯",
        "CBR" to "澳大利亚堪培拉",
        "CCP" to "智利康塞普西翁",
        "CCU" to "印度加尔各答",
        "CDG" to "法国巴黎",
        "CEB" to "菲律宾宿务",
        "CFC" to "巴西卡卡多尔",
        "CGB" to "巴西库亚巴",
        "CGD" to "中国常德",
        "CGK" to "印度尼西亚雅加达",
        "CGO" to "中国郑州",
        "CGP" to "孟加拉国吉大港",
        "CGY" to "菲律宾卡加延德奥罗",
        "CHC" to "新西兰基督城",
        "CJB" to "印度哥印拜陀",
        "CKG" to "中国重庆",
        "CLE" to "美国克利夫兰",
        "CLO" to "哥伦比亚卡利",
        "CLT" to "美国夏洛特",
        "CMB" to "斯里兰卡科伦坡",
        "CMH" to "美国哥伦布",
        "CNF" to "巴西贝洛奥里藏特",
        "CNN" to "印度坎努尔",
        "CNX" to "泰国清迈",
        "COK" to "印度科钦",
        "COR" to "阿根廷科尔多瓦",
        "CPH" to "丹麦哥本哈根",
        "CPT" to "南非开普敦",
        "CRK" to "菲律宾塔拉克市",
        "CSX" to "中国长沙",
        "CTU" to "中国成都",
        "CWB" to "巴西库里提巴",
        "CZL" to "阿尔及利亚康斯坦丁",
        "CZX" to "中国常州",
        "DAC" to "孟加拉国达卡",
        "DAD" to "越南岘港",
        "DAR" to "坦桑尼亚达累斯萨拉姆",
        "DEL" to "印度新德里",
        "DEN" to "美国丹佛",
        "DFW" to "美国达拉斯",
        "DKR" to "塞内加尔达喀尔",
        "DLC" to "中国大连",
        "DME" to "俄罗斯莫斯科",
        "DMM" to "沙特阿拉伯达曼",
        "DOH" to "卡塔尔多哈",
        "DPS" to "印度尼西亚登巴萨",
        "DTW" to "美国底特律",
        "DUB" to "爱尔兰都柏林",
        "DUR" to "南非德班",
        "DUS" to "德国杜塞尔多夫",
        "DXB" to "阿联酋迪拜",
        "EBB" to "乌干达坎帕拉",
        "EBL" to "伊拉克埃尔比勒",
        "EDI" to "英国爱丁堡",
        "EVN" to "亚美尼亚埃里温",
        "EWR" to "美国纽瓦克",
        "EZE" to "阿根廷布宜诺斯艾利斯",
        "FCO" to "意大利罗马",
        "FIH" to "刚果民主共和国金沙萨",
        "FLN" to "巴西弗洛里亚诺波利斯",
        "FOC" to "中国福州",
        "FOR" to "巴西福塔莱萨",
        "FRA" to "德国法兰克福",
        "FRU" to "吉尔吉斯斯坦比什凯克",
        "FSD" to "美国苏福尔斯",
        "FUK" to "日本福冈",
        "FUO" to "中国佛山",
        "GBE" to "博茨瓦纳哈博罗内",
        "GDL" to "墨西哥瓜达拉哈拉",
        "GEO" to "圭亚那乔治敦",
        "GIG" to "巴西里约热内卢",
        "GND" to "格林纳达圣乔治",
        "GOT" to "瑞典哥德堡",
        "GRU" to "巴西圣保罗",
        "GUA" to "危地马拉危地马拉城",
        "GUM" to "关岛哈加特纳",
        "GVA" to "瑞士日内瓦",
        "GYD" to "阿塞拜疆巴库",
        "GYE" to "厄瓜多尔瓜亚基尔",
        "GYN" to "巴西戈亚尼亚",
        "HAK" to "中国海口",
        "HAM" to "德国汉堡",
        "HAN" to "越南河内",
        "HBA" to "澳大利亚霍巴特",
        "HEL" to "芬兰赫尔辛基",
        "HFA" to "以色列海法",
        "HFE" to "中国淮南",
        "HGH" to "中国绍兴",
        "HKG" to "中国香港",
        "HNL" to "美国檀香山",
        "HRE" to "津巴布韦哈拉雷",
        "HYD" to "印度海得拉巴",
        "HYN" to "中国台州",
        "IAD" to "美国阿什本",
        "IAH" to "美国休斯顿",
        "ICN" to "韩国首尔",
        "IND" to "美国印第安纳波利斯",
        "ISB" to "巴基斯坦伊斯兰堡",
        "IST" to "土耳其伊斯坦布尔",
        "ISU" to "伊拉克苏莱曼尼亚",
        "ITJ" to "巴西伊塔雅伊",
        "IXC" to "印度昌迪加尔",
        "JAX" to "美国杰克逊维尔",
        "JDO" to "巴西北茹阿泽鲁",
        "JED" to "沙特阿拉伯吉达",
        "JHB" to "马来西亚新山",
        "JIB" to "吉布提市",
        "JNB" to "南非约翰内斯堡",
        "JOG" to "印度尼西亚日惹",
        "JOI" to "巴西若因维利",
        "JRG" to "印度桑巴尔普尔",
        "JSR" to "孟加拉国杰索尔",
        "JXG" to "中国嘉兴",
        "KBP" to "乌克兰基辅",
        "KCH" to "马来西亚古晋",
        "KEF" to "冰岛雷克雅未克",
        "KGL" to "卢旺达基加利",
        "KHH" to "中国台湾高雄",
        "KHI" to "巴基斯坦卡拉奇",
        "KHN" to "中国新余市",
        "KIN" to "牙买加金斯敦",
        "KIV" to "摩尔多瓦基希讷乌",
        "KIX" to "日本大阪",
        "KJA" to "俄罗斯克拉斯诺亚尔斯克",
        "KMG" to "中国昆明",
        "KNU" to "印度坎普尔",
        "KTM" to "尼泊尔加德满都",
        "KUL" to "马来西亚吉隆坡",
        "KWE" to "中国贵阳",
        "KWI" to "科威特科威特城",
        "LAD" to "安哥拉罗安达",
        "LAS" to "美国拉斯维加斯",
        "LAX" to "美国洛杉矶",
        "LCA" to "塞浦路斯尼科西亚",
        "LED" to "俄罗斯圣彼得堡",
        "LHE" to "巴基斯坦拉合尔",
        "LHR" to "英国伦敦",
        "LHW" to "中国兰州",
        "LIM" to "秘鲁利马",
        "LIS" to "葡萄牙里斯本",
        "LJU" to "斯洛文尼亚卢布尔雅那",
        "LLK" to "阿塞拜疆阿斯塔拉",
        "LLW" to "马拉维利隆圭",
        "LOCAL" to "本地网络",
        "LOS" to "尼日利亚拉各斯",
        "LPB" to "玻利维亚拉巴斯",
        "LUN" to "赞比亚卢萨卡",
        "LUX" to "卢森堡卢森堡市",
        "LYA" to "中国洛阳",
        "LYS" to "法国里昂",
        "MAA" to "印度钦奈",
        "MAD" to "西班牙马德里",
        "MAN" to "英国曼彻斯特",
        "MAO" to "巴西马瑙斯",
        "MBA" to "肯尼亚蒙巴萨",
        "MCI" to "美国堪萨斯城",
        "MCT" to "阿曼马斯喀特",
        "MDE" to "哥伦比亚麦德林",
        "MEL" to "澳大利亚墨尔本",
        "MEM" to "美国孟菲斯",
        "MEX" to "墨西哥墨西哥城",
        "MFE" to "美国麦卡伦",
        "MFM" to "中国澳门",
        "MIA" to "美国迈阿密",
        "MLA" to "马耳他圣韦内拉",
        "MLE" to "马累, 马尔代夫",
        "MLG" to "印度尼西亚玛琅",
        "MNL" to "菲律宾马尼拉",
        "MPM" to "莫桑比克马普托",
        "MRS" to "法国马赛",
        "MRU" to "毛里求斯路易港",
        "MSP" to "美国明尼阿波利斯",
        "MSQ" to "白俄罗斯明斯克",
        "MUC" to "德国慕尼黑",
        "MXP" to "意大利米兰",
        "NAG" to "印度那格浦尔",
        "NBO" to "肯尼亚内罗毕",
        "NJF" to "伊拉克纳杰夫",
        "NNG" to "中国南宁",
        "NOU" to "新喀里多尼亚努美阿",
        "NQN" to "阿根廷内乌肯",
        "NQZ" to "哈萨克斯坦阿斯塔纳",
        "NRT" to "日本东京",
        "NVT" to "巴西蒂姆博",
        "OKA" to "日本那霸",
        "OKC" to "美国俄克拉荷马城",
        "OMA" to "美国奥马哈",
        "ORD" to "美国芝加哥",
        "ORF" to "美国诺福克",
        "ORK" to "爱尔兰科克",
        "ORN" to "阿尔及利亚奥兰",
        "OSL" to "挪威奥斯陆",
        "OTP" to "罗马尼亚布加勒斯特",
        "OUA" to "布基纳法索瓦加杜古",
        "PAT" to "印度巴特那",
        "PBH" to "不丹廷布",
        "PBM" to "苏里南帕拉马里博",
        "PDX" to "美国波特兰",
        "PER" to "澳大利亚珀斯",
        "PHL" to "美国费城",
        "PHX" to "美国菲尼克斯",
        "PIT" to "美国匹兹堡",
        "PKX" to "中国廊坊",
        "PMO" to "意大利巴勒莫",
        "PMW" to "巴西帕尔马斯",
        "PNH" to "柬埔寨金边",
        "PNQ" to "印度浦那",
        "POA" to "巴西阿雷格里港",
        "POS" to "特立尼达和多巴哥西班牙港",
        "PPT" to "法属波利尼西亚塔希提",
        "PRG" to "捷克共和国布拉格",
        "PTY" to "巴拿马巴拿马城",
        "QRO" to "墨西哥克雷塔罗",
        "QWJ" to "巴西阿美利卡纳",
        "RAO" to "巴西里贝朗普雷图",
        "RDU" to "美国达勒姆",
        "REC" to "巴西累西腓",
        "RIC" to "美国里士满",
        "RIX" to "拉脱维亚里加",
        "RUH" to "沙特阿拉伯利雅得",
        "RUN" to "法国留尼汪",
        "SAN" to "美国圣迭戈",
        "SAP" to "洪都拉斯圣佩德罗苏拉",
        "SAT" to "美国圣安东尼奥",
        "SCL" to "智利圣地亚哥",
        "SDQ" to "多米尼加共和国圣多明各",
        "SEA" to "美国西雅图",
        "SFO" to "美国旧金山",
        "SGN" to "越南胡志明市",
        "SHA" to "中国上海",
        "SIN" to "新加坡",
        "SJC" to "美国圣何塞",
        "SJK" to "巴西圣若泽杜斯坎普斯",
        "SJO" to "哥斯达黎加圣何塞",
        "SJP" to "巴西圣若泽杜里奥普雷图",
        "SJU" to "波多黎各圣胡安",
        "SJW" to "中国衡水",
        "SKG" to "希腊塞萨洛尼基",
        "SKP" to "北马其顿斯科普里",
        "SLC" to "美国盐湖城",
        "SMF" to "美国萨克拉门托",
        "SOD" to "巴西索罗卡巴",
        "SOF" to "保加利亚索非亚",
        "SSA" to "巴西萨尔瓦多",
        "STI" to "多米尼加共和国圣地亚哥德洛斯卡巴耶罗斯",
        "STL" to "美国圣路易斯",
        "STR" to "德国斯图加特",
        "SUV" to "斐济苏瓦",
        "SVX" to "俄罗斯叶卡捷琳堡",
        "SYD" to "澳大利亚悉尼",
        "SZX" to "中国深圳",
        "TAO" to "中国青岛",
        "TAS" to "乌兹别克斯坦塔什干",
        "TBS" to "格鲁吉亚第比利斯",
        "TEN" to "中国铜仁",
        "TGU" to "洪都拉斯特古西加尔巴",
        "TIA" to "阿尔巴尼亚地拉那",
        "TLH" to "美国塔拉哈西",
        "TLL" to "爱沙尼亚塔林",
        "TLV" to "以色列特拉维夫",
        "TNA" to "中国济南",
        "TNR" to "马达加斯加塔那那利佛",
        "TPA" to "美国坦帕",
        "TPE" to "中国台北",
        "TSN" to "中国天津",
        "TUN" to "突尼斯突尼斯",
        "TXL" to "德国柏林",
        "TYN" to "中国阳泉",
        "UDI" to "巴西乌贝兰迪亚",
        "UIO" to "厄瓜多尔基多",
        "ULN" to "蒙古乌兰巴托",
        "URT" to "泰国素叻他尼",
        "VCP" to "巴西坎皮纳斯",
        "VIE" to "奥地利维也纳",
        "VIX" to "巴西维多利亚",
        "VNO" to "立陶宛维尔纽斯",
        "VTE" to "老挝万象",
        "WAW" to "波兰华沙",
        "WDH" to "纳米比亚温得和克",
        "WHU" to "中国芜湖",
        "WLG" to "新西兰惠灵顿",
        "WRO" to "波兰弗罗茨瓦夫",
        "XAP" to "巴西沙佩科",
        "XFN" to "中国襄阳",
        "XIY" to "中国宝鸡",
        "XNH" to "伊拉克纳西里耶",
        "XNN" to "中国西宁",
        "YHZ" to "加拿大哈利法克斯",
        "YOW" to "加拿大渥太华",
        "YUL" to "加拿大蒙特利尔",
        "YVR" to "加拿大温哥华",
        "YWG" to "加拿大温尼伯",
        "YXE" to "加拿大萨斯卡通",
        "YYC" to "加拿大卡尔加里",
        "YYZ" to "加拿大多伦多",
        "ZAG" to "克罗地亚萨格勒布",
        "ZDM" to "拉马拉",
        "ZGN" to "中国中山",
        "ZRH" to "瑞士苏黎世"
    )

    fun extractRegionCode1(ray: String): String {
        return if (ray.contains("-")) {
            ray.split("-").last()
        } else {
            // 如果没有连字符，可能是纯代码或无效格式
            if (ray.length <= 4 && ray.all { it.isLetter() }) ray else "UNKNOWN"
        }
    }
    class CustomDns(private val targetDomain: String, private val targetIp: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return if (hostname == targetDomain) {
                // 将目标域名解析为指定IP
                listOf(InetAddress.getByName(targetIp))
            } else {
                // 其他域名使用系统DNS
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }
    fun createTestClient(hostname:String,ip:String,timeoutSeconds: Int = 6): OkHttpClient {
        return try {
            // 创建信任所有证书的TrustManager
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // 安装信任所有证书的SSLContext
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            // 创建不验证主机名的HostnameVerifier
            val hostnameVerifier = HostnameVerifier { _, _ -> true }

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .dns(CustomDns(hostname,ip))
                .build()
        } catch (e: Exception) {
            // 如果SSL初始化失败，创建普通客户端
            OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .callTimeout((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }

    data class TestResult(
        val ip: String,
        val success: Boolean,
        val delay: Long,
        val statusCode: Int = -1
    ) : Comparable<TestResult> {
        override fun compareTo(other: TestResult): Int {
            // 排序规则：成功的在前，失败的在后；成功的按延迟升序排序
            return when {
                this.success && !other.success -> -1
                !this.success && other.success -> 1
                this.success && other.success -> this.delay.compareTo(other.delay)
                else -> 0 // 两个都失败，保持原顺序
            }
        }
    }

    data class TestDownloadResult(
        val ip: String,
        val success: Boolean,
        val delay: Long,
        val regionCode: String = "未知地区"
    )

    fun testSingleIpSync(
        ip: String,
        timeoutSeconds: Int = 6,
        targetUrl: String = "https://www.cloudflare-cn.com"
    ): TestResult {
        val url = targetUrl.toHttpUrlOrNull() ?: return TestResult(ip, false, -1)

        val hostname = url.host  // 使用 .host 替代 .host()

        val request = Request.Builder()
            .url(targetUrl)
            .head() // 使用HEAD方法
            .header("Host", hostname)
            .header("Connection", "close")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val client = createTestClient(hostname,ip,timeoutSeconds)

        return try {
            // 测试连接时间
            val startTime = System.nanoTime()
            // 重新获取状态码
            val response = client.newCall(request).execute()

            val statusCode = response.code
            response.close()

            val delayMs =((System.nanoTime()-startTime)/1_000_000).toLong()


            if (statusCode in listOf(200, 204, 301, 302, 307, 308,421,403)) {
            TestResult(ip, true, delayMs, statusCode)
            } else {
                TestResult(ip, false, -1, statusCode)
            }
        } catch (e: IOException) {
            TestResult(ip, false, -1)
        } catch (e: Exception) {
            TestResult(ip, false, -1)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }


    fun downloadWithSpeedTracking(
        ip: String,
        timeoutSeconds: Long,
        targetUrl: String = "https://speed.cloudflare.com/__down?bytes=40485760"
    ): TestDownloadResult {
        val url = targetUrl.toHttpUrlOrNull() ?: return TestDownloadResult(ip,false,0,"未知地区")
        val hostname = url.host
        val client = createTestClient(hostname, ip,(timeoutSeconds+5).toInt())

        val request = Request.Builder()
            .url(targetUrl)
            .header("Host", hostname)
            .header("Connection", "keep-alive")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val startTime = System.nanoTime()
        val totalBytesDownloaded = AtomicLong(0)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val call = client.newCall(request)
            val future = executor.submit<TestDownloadResult?> {
                try {
                    var cfRayCodeChinese:String="未知地区"
                    val response = call.execute()
                    response.use { // 使用use确保资源自动关闭
                        val inputStream = response.body?.byteStream() ?: return@submit null
                        val buffer = ByteArray(20480)
                        var bytesRead: Int



                        if (it.isSuccessful) {
                            val cfRay = it.headers["CF-RAY"]

                            val cfRayCode = extractRegionCode1(cfRay.toString())

                            if(cfRayCode.isNotEmpty()){
                                cfRayCodeChinese = cfRayCode+":"+getRegionChineseName(cfRayCode)
                            }

                        }

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            totalBytesDownloaded.addAndGet(bytesRead.toLong())
                            val elapsedSec = (System.nanoTime() - startTime) / 1_000_000_000.0

                            // 提前检查超时
                            if (elapsedSec >= timeoutSeconds) {
                                println("Download timed out after $timeoutSeconds seconds.")
                                break
                            }
                        }
                    }

                    // 计算平均下载速度
                    val totalBytes = totalBytesDownloaded.get()
                    val totalTimeSec = (System.nanoTime() - startTime) / 1_000_000_000.0

                    if (totalTimeSec > 0) {
                        TestDownloadResult(ip,true,(totalBytes / 1024.0 / totalTimeSec).toLong(),cfRayCodeChinese)

                    } else {
                        TestDownloadResult(ip,false,0,cfRayCodeChinese)
                    }
                } catch (e: IOException) {
                    println("Download failed with exception: ${e.message}")
                    TestDownloadResult(ip,false,0,"未知地区")
                }
            }

            // 使用future.get的超时控制，而不是Thread.sleep
            return try {
                future.get(timeoutSeconds+5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                println("Download timed out after $timeoutSeconds seconds.")
                call.cancel()
                TestDownloadResult(ip,false,0,"未知地区1")
            } catch (e: Exception) {
                println("Download failed: ${e.message}")
                TestDownloadResult(ip,false,0,"未知地区2")
            }
        } finally {
            executor.shutdown()
        }
    }

}
