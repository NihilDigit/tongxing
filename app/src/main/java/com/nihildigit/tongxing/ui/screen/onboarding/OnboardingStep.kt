package com.nihildigit.tongxing.ui.screen.onboarding

enum class OnboardingStep(
    val route: String,
    val index: Int,
    val title: String
) {
    Welcome("welcome", 0, "欢迎"),
    Permissions("permissions", 1, "权限"),
    Tutorial("tutorial", 2, "教学"),
    Complete("complete", 3, "完成");

    companion object {
        const val TotalSteps = 4

        fun fromRoute(route: String?): OnboardingStep {
            val trimmed = route?.substringBefore('?')
            // 兼容旧版本：如果是 config 路由，跳转到 Tutorial
            if (trimmed == "config") return Tutorial
            return entries.firstOrNull { it.route == trimmed } ?: Welcome
        }
    }
}

