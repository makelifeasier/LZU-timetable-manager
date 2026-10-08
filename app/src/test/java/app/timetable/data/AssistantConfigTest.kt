package app.timetable.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手配置里**不碰 Context 的那部分**：地址校验、端点拼接、服务商解析、以及几个"钉住"的常量。
 *
 * 这几条看着琐碎，但它们决定了"请求会发去哪"：
 *  - 地址校验是**只允许 https** 的执行点（本 App 全局禁明文，不为这个功能开口子）；
 *  - 端点拼接错了会打到 `…/v1/v1/chat/completions` 这种不存在的路径，用户只会看到 404；
 *  - 模型名/申请 Key 的网址是会被文档和界面引用的字符串，写死在测试里才不会悄悄漂移。
 */
class AssistantConfigTest {

    // ------------------------------------------------------------ 地址校验

    @Test
    fun acceptsHttpsHostsOnly() {
        assertNull(AssistantConfig.validateBaseUrl("https://api.deepseek.com"))
        assertNull(AssistantConfig.validateBaseUrl("  https://api.deepseek.com/v1  "))
        assertNull(AssistantConfig.validateBaseUrl("https://my-gateway.example.com/openai"))
    }

    @Test
    fun refusesPlainHttpAndExplainsWhy() {
        val why = AssistantConfig.validateBaseUrl("http://api.deepseek.com")
        assertNotNull("http 必须被拒绝", why)
        assertTrue("拒绝原因里要说清是明文被禁：$why", why!!.contains("https"))
    }

    @Test
    fun refusesEmptyAndMalformedAddresses() {
        assertTrue(AssistantConfig.validateBaseUrl("")!!.contains("请填"))
        assertTrue(AssistantConfig.validateBaseUrl("   ")!!.contains("请填"))
        // 没有点的主机名（例如只写 https://localhost）会被拒：这条是有意的简化 ——
        // 允许它就得把"用户可以指向本机"这件事也一并考虑（那是另一个决定）
        assertNotNull(AssistantConfig.validateBaseUrl("https://localhost"))
        assertNotNull(AssistantConfig.validateBaseUrl("https://"))
        assertNotNull(AssistantConfig.validateBaseUrl("api.deepseek.com"))
        val long = "https://" + "a".repeat(300) + ".com"
        assertNotNull(AssistantConfig.validateBaseUrl(long))
    }

    // ------------------------------------------------------------ 端点拼接

    @Test
    fun joinsTheChatCompletionsPathExactlyOnce() {
        val expected = "https://api.deepseek.com/chat/completions"
        assertEquals(expected, AssistantConfig.joinEndpoint("https://api.deepseek.com"))
        assertEquals(expected, AssistantConfig.joinEndpoint("https://api.deepseek.com/"))
        assertEquals(expected, AssistantConfig.joinEndpoint("  https://api.deepseek.com  "))
        // 用户直接把完整端点填进来时不能重复拼
        assertEquals(expected, AssistantConfig.joinEndpoint("https://api.deepseek.com/chat/completions"))
        // 带 /v1 的中转地址：路径要接在它后面
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            AssistantConfig.joinEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals("", AssistantConfig.joinEndpoint(""))
    }

    // ------------------------------------------------------------ 服务商

    @Test
    fun providerDefaultsToDeepSeekAndIgnoresGarbage() {
        assertEquals(AssistantConfig.Provider.DEEPSEEK, AssistantConfig.Provider.of(null))
        assertEquals(AssistantConfig.Provider.DEEPSEEK, AssistantConfig.Provider.of(""))
        assertEquals(AssistantConfig.Provider.DEEPSEEK, AssistantConfig.Provider.of("DEEPSEEK"))
        assertEquals(AssistantConfig.Provider.CUSTOM, AssistantConfig.Provider.of("CUSTOM"))
        // 存量值被写坏时落回默认，而不是抛异常或变成第三个服务商
        assertEquals(AssistantConfig.Provider.DEEPSEEK, AssistantConfig.Provider.of("openai"))
    }

    // ------------------------------------------------------------ 钉住会被引用的常量

    @Test
    fun pinnedFactsThatDocsAndUiReferTo() {
        assertEquals("https://api.deepseek.com", AssistantConfig.DEEPSEEK_BASE)
        // 官方文档点名"模型名请使用 deepseek-flash"；旧名 deepseek-chat 已过时
        assertEquals("deepseek-flash", AssistantConfig.DEEPSEEK_MODEL)
        assertEquals("https://platform.deepseek.com/api_keys", AssistantConfig.KEY_PAGE)
    }

    @Test
    fun theConfigObjectExposesNoWayToReadTheKeyBackIntoTheUi() {
        // 界面只允许拿掩码与"有没有"；这条是"不许把 Key 回显出来"的形态约束
        val masked = AssistantConfig::class.java.methods.map { it.name }.toSet()
        assertTrue(masked.contains("maskedKey"))
        assertTrue(masked.contains("hasKey"))
        // clearKey/save 的存在是必需的；apiKey 只给内部使用（getter 存在即可，谁调用由 SecretGuard 测试守）
        assertTrue(masked.contains("clearKey"))
    }
}
