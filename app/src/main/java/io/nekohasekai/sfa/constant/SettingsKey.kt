package io.nekohasekai.sfa.constant

object SettingsKey {
    const val SELECTED_PROFILE = "selected_profile"
    const val SERVICE_MODE = "service_mode"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val DISABLE_DEPRECATED_WARNINGS = "disable_deprecated_warnings"

    const val AUTO_REDIRECT = "auto_redirect"
    const val PER_APP_PROXY_ENABLED = "per_app_proxy_enabled"
    const val PER_APP_PROXY_MODE = "per_app_proxy_mode"
    const val PER_APP_PROXY_LIST = "per_app_proxy_list"
    const val PER_APP_PROXY_MANAGED_MODE = "per_app_proxy_managed_mode"
    const val PER_APP_PROXY_MANAGED_LIST = "per_app_proxy_managed_list"

    const val ALLOW_BYPASS = "allow_bypass"
    const val SYSTEM_PROXY_ENABLED = "system_proxy_enabled"

    // OOM killer
    const val OOM_KILLER_ENABLED = "oom_killer_enabled"
    const val OOM_KILLER_DISABLED = "oom_killer_disabled"
    const val OOM_MEMORY_LIMIT_MB = "oom_memory_limit_mb"

    // Power report
    const val POWER_REPORT_ENABLED = "power_report_enabled"

    // dashboard
    const val DASHBOARD_ITEM_ORDER = "dashboard_item_order"
    const val DASHBOARD_DISABLED_ITEMS = "dashboard_disabled_items"

    // Remote Control
    const val ACTIVE_REMOTE_SERVER_ID = "active_remote_server_id"

    // Tailscale SSH
    const val TAILSCALE_SSH_REMEMBERED_USERNAMES = "tailscale_ssh_remembered_usernames"
    const val TAILSCALE_SSH_REMEMBERED_TERMINAL_TYPES = "tailscale_ssh_remembered_terminal_types"
    const val TAILSCALE_SSH_QUICK_CONNECT_PEERS = "tailscale_ssh_quick_connect_peers"
    const val TAILSCALE_SSH_LIGHT_THEME = "tailscale_ssh_light_theme"
    const val TAILSCALE_SSH_DARK_THEME = "tailscale_ssh_dark_theme"
    const val TAILSCALE_SSH_FONT_FAMILY = "tailscale_ssh_font_family"
    const val TAILSCALE_SSH_FONT_SIZE = "tailscale_ssh_font_size"
    const val TAILSCALE_SSH_CUSTOM_FONT_PATH = "tailscale_ssh_custom_font_path"
    const val TAILSCALE_SSH_LIGHT_CONFIG = "tailscale_ssh_light_config"
    const val TAILSCALE_SSH_DARK_CONFIG = "tailscale_ssh_dark_config"
    const val TAILSCALE_SSH_FONT_FOLLOW_THEME = "tailscale_ssh_font_follow_theme"

    // Airplane mode recovery (root)
    const val AIRPLANE_RECOVERY_ENABLED = "airplane_recovery_enabled"
    const val AIRPLANE_RECOVERY_FAILURE_THRESHOLD = "airplane_recovery_failure_threshold"
    const val AIRPLANE_RECOVERY_COOLDOWN_SEC = "airplane_recovery_cooldown_sec"

    // cache
    const val STARTED_BY_USER = "started_by_user"
}
