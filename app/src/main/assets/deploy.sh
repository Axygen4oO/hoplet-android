#!/bin/bash
# ==============================================================================
#  WDTT VPN Server — Универсальный установщик для VPS
#  Поддержка: Debian 11+, Ubuntu 20.04+, CentOS/RHEL/Fedora/AlmaLinux/Rocky
#  Версия: 3.2  |  Дата: 2026-05-13
#  NAT:  MASQUERADE через iptables
#  WG:   порт 56001 (не конфликтует с существующим WG на 51820)
#  DTLS: порт 56000
# ==============================================================================
set -euo pipefail

STAGED_BINARY=""
STAGED_UNIT=""
cleanup_staged_binary() {
    if [ -n "$STAGED_BINARY" ]; then
        if ! rm -f -- "$STAGED_BINARY" 2>/dev/null; then
            echo "[!] Не удалось удалить временный файл бинарника: $STAGED_BINARY" >&2
        fi
    fi
    if [ -n "$STAGED_UNIT" ]; then
        if ! rm -f -- "$STAGED_UNIT" 2>/dev/null; then
            echo "[!] Не удалось удалить временный unit-файл: $STAGED_UNIT" >&2
        fi
    fi
}
trap cleanup_staged_binary EXIT

readonly SCRIPT_VERSION="3.2"
readonly LOG_FILE="/var/log/wdtt-install.log"
readonly WG_PORT="${WDTT_WG_PORT:-56001}"
readonly DTLS_PORT="${WDTT_DTLS_PORT:-56000}"
readonly DIRECT_PORT="${WDTT_DIRECT_PORT:-56002}"
readonly RAW_PORT="${WDTT_RAW_PORT:-56003}"
readonly SSH_PORT="${WDTT_SSH_PORT:-22}"
readonly WDTT_ARGS="${WDTT_ARGS:-}"
readonly WDTT_SERVER_SHA256="${WDTT_SERVER_SHA256:-}"
readonly WDTT_IFACE="wdtt0"
readonly WDTT_CONFIG_DIR="/etc/wdtt"
readonly WDTT_ACCESS_DB="passwords.json"
readonly IPT_COMMENT="WDTT_MANAGED"
readonly IPT_MIRROR_COMMENT="WDTT_MIRRORED"

validate_port() {
    local name="$1" value="$2"
    case "$value" in
        ''|*[!0-9]*) die "$name должен быть числом от 1 до 65535, получено: $value" ;;
    esac
    if [ "$value" -lt 1 ] || [ "$value" -gt 65535 ]; then
        die "$name должен быть в диапазоне 1..65535, получено: $value"
    fi
}

# ─── Цвета ───────────────────────────────────────────────────────────────────
C_GREEN=''; C_YELLOW=''; C_RED=''
C_CYAN='';  C_BOLD='';      C_NC=''

log_info()  { echo -e "${C_GREEN}[✓]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_warn()  { echo -e "${C_YELLOW}[!]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_error() { echo -e "${C_RED}[✗]${C_NC} $*" | tee -a "$LOG_FILE"; }
log_step()  { echo -e "${C_CYAN}[►]${C_NC} ${C_BOLD}$*${C_NC}" | tee -a "$LOG_FILE"; }

die() { log_error "$*"; exit 1; }

prog() { echo "WDTT_PROGRESS|$1|$2"; }

NEW_SERVER_SHA256=""

server_sha256() {
    sha256sum "$1" | awk '{print $1}'
}

validate_server_binary() {
    local path="$1" expected_sha="${2:-}"
    local magic elf_class elf_data machine actual_sha

    [ -f "$path" ] || die "server binary not found: $path"
    [ -s "$path" ] || die "server binary is empty: $path"
    command -v od >/dev/null 2>&1 || die "Команда od нужна для проверки ELF."
    command -v sha256sum >/dev/null 2>&1 || die "Команда sha256sum нужна для проверки бинарника."

    magic=$(od -An -tx1 -N4 "$path" | tr -d '[:space:]')
    elf_class=$(od -An -tx1 -j4 -N1 "$path" | tr -d '[:space:]')
    elf_data=$(od -An -tx1 -j5 -N1 "$path" | tr -d '[:space:]')
    machine=$(od -An -tx1 -j18 -N2 "$path" | tr -d '[:space:]')
    [ "$magic" = "7f454c46" ] || die "invalid ELF binary"
    [ "$elf_class" = "02" ] || die "invalid ELF64 binary"
    [ "$elf_data" = "01" ] || die "invalid ELF byte order"
    [ "$machine" = "3e00" ] || die "invalid ELF architecture (amd64/x86-64 required)"

    actual_sha=$(server_sha256 "$path") || die "Не удалось вычислить SHA-256: $path"
    if [ -n "$expected_sha" ]; then
        case "$expected_sha" in
            *[!0-9A-Fa-f]*|'') die "WDTT_SERVER_SHA256 должен быть SHA-256 в hex-формате." ;;
        esac
        [ "${#expected_sha}" -eq 64 ] || die "WDTT_SERVER_SHA256 должен содержать 64 hex-символа."
        [ "$(printf '%s' "$expected_sha" | tr 'A-F' 'a-f')" = "$actual_sha" ] || \
            die "server SHA256 mismatch"
    fi
    NEW_SERVER_SHA256="$actual_sha"
    log_info "Новый wdtt-server проверен: ELF64 amd64, SHA-256 $actual_sha"
}

# ─── Проверка root ────────────────────────────────────────────────────────────
check_root() {
    if [ "$(id -u)" -ne 0 ]; then
        die "Скрипт должен быть запущен от root. Запустите установщик от root."
    fi
}

# ─── Определение ОС ──────────────────────────────────────────────────────────
OS_ID="" ; PKG_MGR=""

detect_os() {
    log_step "Определение операционной системы..."
    if [ ! -f /etc/os-release ]; then
        die "Файл /etc/os-release не найден."
    fi
    . /etc/os-release
    OS_ID="${ID:-unknown}"
    case "$OS_ID" in
        ubuntu|debian|linuxmint|pop)     PKG_MGR="apt" ;;
        centos|rhel|rocky|almalinux|oracle) PKG_MGR="yum"
            command -v dnf &>/dev/null && PKG_MGR="dnf" ;;
        fedora)                          PKG_MGR="dnf" ;;
        arch|manjaro|endeavouros)        PKG_MGR="pacman" ;;
        *) die "Неподдерживаемый дистрибутив: $OS_ID" ;;
    esac
    log_info "ОС: ${PRETTY_NAME:-$OS_ID} | PM: $PKG_MGR"
}

# ─── Пакеты ──────────────────────────────────────────────────────────────────
pkg_update_done=0

pkg_update() {
    [ "$pkg_update_done" = "1" ] && return 0
    log_step "Обновление индексов пакетов..."
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y >>"$LOG_FILE" 2>&1 || die "apt package index update failed"
            ;;
        dnf)    dnf makecache -y >>"$LOG_FILE" 2>&1 || die "dnf package index update failed" ;;
        yum)    yum makecache -y >>"$LOG_FILE" 2>&1 || die "yum package index update failed" ;;
        pacman) pacman -Sy --noconfirm >>"$LOG_FILE" 2>&1 || die "pacman package index update failed" ;;
    esac
    pkg_update_done=1
}

pkg_install() {
    [ "$#" -eq 0 ] && return 0
    case "$PKG_MGR" in
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get install -y -qq "$@" >>"$LOG_FILE" 2>&1
            ;;
        dnf)    dnf install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        yum)    yum install -y "$@" >>"$LOG_FILE" 2>&1 ;;
        pacman) pacman -S --noconfirm --needed "$@" >>"$LOG_FILE" 2>&1 ;;
    esac
}

install_prerequisites() {
    prog 0.08 "Пакеты..."
    pkg_update
    log_step "Установка базовых зависимостей..."

    case "$PKG_MGR" in
        apt)
            pkg_install ca-certificates iproute2 iptables nftables procps psmisc
            ;;
        dnf|yum)
            pkg_install ca-certificates iproute iptables nftables procps-ng psmisc
            ;;
        pacman)
            pkg_install ca-certificates iproute2 iptables nftables procps-ng psmisc
            ;;
    esac
}

require_runtime_tools() {
    command -v ip >/dev/null 2>&1 || die "Команда ip не найдена. Установите iproute2/iproute."
    command -v systemctl >/dev/null 2>&1 || die "systemctl не найден. Нужен VPS с systemd."
}

# ─── Автоопределение WAN-интерфейса ──────────────────────────────────────────
detect_wan_interface() {
    local iface=""
    iface=$(ip route show default 2>/dev/null | head -1 | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
    [ -z "$iface" ] && iface=$(ip -4 addr show scope global 2>/dev/null | grep -oP '(?<=dev )\S+' | head -1)
    [ -z "$iface" ] && iface=$(ls /sys/class/net/ | grep -v lo | head -1)
    echo "$iface"
}

# ─── Firewall helpers ────────────────────────────────────────────────────────
FW_BACKEND=""

iptables_add_input() {
    local proto="$1" port="$2" comment="$3"
    [ "$FW_BACKEND" = "iptables" ] || return 0
    case "$proto:$port" in
        tcp:[0-9]*|udp:[0-9]*) ;;
        *) return 0 ;;
    esac
    [ "$port" -ge 1 ] 2>/dev/null && [ "$port" -le 65535 ] 2>/dev/null || return 0
    if iptables -C INPUT -p "$proto" --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null; then
        return 0
    fi
    iptables -I INPUT -p "$proto" --dport "$port" -m comment --comment "$comment" -j ACCEPT 2>/dev/null || \
        die "failed to add firewall rule for $port/$proto"
}

mirror_port_to_iptables() {
    local proto="$1" port="$2" source="$3"
    iptables_add_input "$proto" "$port" "$IPT_MIRROR_COMMENT"
    log_info "iptables: сохранён доступ $port/$proto из $source"
}

mirror_existing_firewall_ports_to_iptables() {
    [ "$FW_BACKEND" = "iptables" ] || return 0
    local tmp
    tmp="$(mktemp)"

    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -qi "Status: active"; then
        log_info "UFW активен: переношу разрешённые tcp/udp порты в iptables"
        if ! ufw status 2>/dev/null | sed -nE 's#^([0-9]{1,5})/(tcp|udp)[[:space:]].*ALLOW IN.*#\2 \1 ufw#p' >> "$tmp"; then
            log_warn "Не удалось прочитать правила UFW; перенос пропущен"
        fi
    fi

    if command -v nft >/dev/null 2>&1; then
        local nft_ports
        if ! nft_ports="$(nft -a list ruleset 2>/dev/null | sed -nE 's/.*(tcp|udp) dport ([0-9]{1,5}).*accept.*/\1 \2 nft/p' | sort -u)"; then
            log_warn "Не удалось прочитать правила nftables; перенос пропущен"
            nft_ports=""
        fi
        if [ -n "$nft_ports" ]; then
            log_info "nftables найден: переношу простые accept dport правила в iptables"
            printf '%s\n' "$nft_ports" >> "$tmp"
        fi
    fi

    if [ -s "$tmp" ]; then
        sort -u "$tmp" | while read -r proto port source; do
            mirror_port_to_iptables "$proto" "$port" "$source"
        done
    else
        log_info "UFW/nftables разрешённых tcp/udp портов для переноса не найдено"
    fi
    rm -f "$tmp"
}

detect_firewall() {
    if ! command -v iptables &>/dev/null; then
        log_warn "iptables не найден. Пытаюсь установить firewall-пакеты..."
        pkg_update
        pkg_install iptables nftables
    fi
    if command -v iptables &>/dev/null; then
        FW_BACKEND="iptables"
        log_info "Firewall backend: iptables (принудительно)"
        mirror_existing_firewall_ports_to_iptables
    else
        FW_BACKEND="none"
        log_warn "iptables не найден. Установка продолжится, но NAT/firewall нужно настроить вручную."
    fi
}

# ─── Firewall-абстракция ─────────────────────────────────────────────────────
fw_add_input_udp() {
    local port="$1"
    case "$FW_BACKEND" in
        iptables)
            iptables_add_input udp "$port" "$IPT_COMMENT"
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt input udp dport "$port" accept 2>/dev/null || die "failed to add nftables UDP input rule"
            ;;
        none) ;;
    esac
}

fw_add_input_tcp() {
    local port="$1"
    case "$FW_BACKEND" in
        iptables)
            iptables_add_input tcp "$port" "$IPT_COMMENT"
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt input tcp dport "$port" accept 2>/dev/null || die "failed to add nftables TCP input rule"
            ;;
        none) ;;
    esac
}

fw_add_input_udp_range() {
    local from="$1" to="$2"
    case "$FW_BACKEND" in
        iptables|nft) log_warn "Пропускаю широкий UDP range $from-$to: это не изолировано и может влиять на чужие сервисы." ;;
        none) ;;
    esac
}

fw_add_forward() {
    case "$FW_BACKEND" in
        iptables)
            if ! iptables -C FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null; then
                iptables -I FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                    die "failed to add WDTT forward rule (input)"
            fi
            if ! iptables -C FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null; then
                iptables -I FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || \
                    die "failed to add WDTT forward rule (output)"
            fi
            ;;
        nft)
            ensure_nft_wdtt
            nft add rule inet wdtt forward iifname "$WDTT_IFACE" accept 2>/dev/null || die "failed to add nftables forward input rule"
            nft add rule inet wdtt forward oifname "$WDTT_IFACE" accept 2>/dev/null || die "failed to add nftables forward output rule"
            ;;
        none) ;;
    esac
}

fw_add_masquerade() {
    local iface="$1" subnet="$2"
    case "$FW_BACKEND" in
        iptables)
            if ! iptables -t nat -C POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null; then
                iptables -t nat -A POSTROUTING -s "$subnet" -o "$iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null || \
                    die "failed to add WDTT masquerade rule"
            fi
            ;;
        nft)
            if ! nft add table ip wdtt 2>/dev/null; then
                nft list table ip wdtt >/dev/null 2>&1 || die "failed to create nftables NAT table"
            fi
            if ! nft add chain ip wdtt postrouting '{ type nat hook postrouting priority 100; }' 2>/dev/null; then
                nft list chain ip wdtt postrouting >/dev/null 2>&1 || die "failed to create nftables NAT chain"
            fi
            nft add rule ip wdtt postrouting ip saddr "$subnet" oifname "$iface" masquerade 2>/dev/null || die "failed to add nftables masquerade rule"
            ;;
        none) ;;
    esac
}

fw_add_mss_clamping() {
    local subnet="$1"
    case "$FW_BACKEND" in
        iptables)
            # Применяем правило ТОЛЬКО к нашей подсети WDTT
            if ! iptables -t mangle -C FORWARD -s "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null; then
                iptables -t mangle -I FORWARD -s "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \
                    die "failed to add TCPMSS rule (source)"
            fi
            if ! iptables -t mangle -C FORWARD -d "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null; then
                iptables -t mangle -I FORWARD -d "$subnet" -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || \
                    die "failed to add TCPMSS rule (destination)"
            fi
            ;;
        nft)
            if ! nft add table inet wdtt_mangle 2>/dev/null; then
                nft list table inet wdtt_mangle >/dev/null 2>&1 || die "failed to create nftables mangle table"
            fi
            if ! nft add chain inet wdtt_mangle forward '{ type filter hook forward priority -150; policy accept; }' 2>/dev/null; then
                nft list chain inet wdtt_mangle forward >/dev/null 2>&1 || die "failed to create nftables mangle chain"
            fi
            nft add rule inet wdtt_mangle forward ip saddr "$subnet" tcp flags syn tcp option maxseg size set rt mtu 2>/dev/null || die "failed to add nftables MSS source rule"
            nft add rule inet wdtt_mangle forward ip daddr "$subnet" tcp flags syn tcp option maxseg size set rt mtu 2>/dev/null || die "failed to add nftables MSS destination rule"
            ;;
        none) ;;
    esac
}

fw_add_established() {
    return 0
}

fw_cleanup_wdtt_rules() {
    local iface="$1"
    if command -v iptables >/dev/null 2>&1; then
        for i in {1..5}; do
            local nat_iface
            for nat_iface in "$iface" $(ls /sys/class/net 2>/dev/null || true); do
                [ -n "$nat_iface" ] && iptables -t nat -D POSTROUTING -s 10.66.0.0/16 -o "$nat_iface" -m comment --comment "$IPT_COMMENT" -j MASQUERADE 2>/dev/null || true
            done
            iptables -t mangle -D FORWARD -s 10.66.0.0/16 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            iptables -t mangle -D FORWARD -d 10.66.0.0/16 -p tcp -m tcp --tcp-flags SYN,RST SYN -m comment --comment "$IPT_COMMENT" -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${DTLS_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p tcp --dport ${DTLS_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${DIRECT_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${RAW_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p udp --dport ${WG_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p tcp --dport ${SSH_PORT} -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D INPUT -p tcp --dport 22 -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D FORWARD -i "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
            iptables -D FORWARD -o "$WDTT_IFACE" -m comment --comment "$IPT_COMMENT" -j ACCEPT 2>/dev/null || true
        done
    fi
    if command -v nft >/dev/null 2>&1; then
        nft delete table ip wdtt 2>/dev/null || true
        nft delete table inet wdtt 2>/dev/null || true
        nft delete table inet wdtt_mangle 2>/dev/null || true
    fi
}

cleanup_config_dir_keep_access_db() {
    # Не удаляем существующую конфигурацию: неизвестные пользовательские
    # файлы и каталоги должны переживать reinstall/uninstall.
    [ -d "$WDTT_CONFIG_DIR" ] || return 0
    if [ -f "$WDTT_CONFIG_DIR/$WDTT_ACCESS_DB" ]; then
        chmod 600 "$WDTT_CONFIG_DIR/$WDTT_ACCESS_DB" || die "Не удалось защитить $WDTT_ACCESS_DB"
    fi
}

# ══════════════════════════════════════════════════════════════════════════════
#  WDTT VPN SERVER DEPLOYMENT
# ══════════════════════════════════════════════════════════════════════════════

# ─── Очистка старого WDTT ─────────────────────────────────────────────────────
wdtt_cleanup() {
    prog 0.05 "Очистка..."
    echo "🧹 Очистка старой установки Hoplet..."

    systemctl unmask wdtt >/dev/null 2>&1 || die "systemctl unmask failed"
    if systemctl is-active --quiet wdtt 2>/dev/null; then
        systemctl stop wdtt >/dev/null 2>&1 || die "systemctl stop failed"
    fi
    if systemctl is-enabled --quiet wdtt 2>/dev/null; then
        systemctl disable wdtt >/dev/null 2>&1 || die "systemctl disable failed"
    fi

    # Оставляем существующий unit до успешной атомарной замены новым.
    if pgrep -x wdtt-server >/dev/null 2>&1; then
        pkill -x wdtt-server >/dev/null 2>&1 || die "failed to stop wdtt-server process"
    fi

    # Удаляем только собственный интерфейс WDTT.
    if ip link show "$WDTT_IFACE" >/dev/null 2>&1; then
        ip link del "$WDTT_IFACE" >/dev/null 2>&1 || die "failed to remove existing WDTT interface"
    fi

    # Удаляем старые правила NAT для WDTT подсети
    fw_cleanup_wdtt_rules "$(detect_wan_interface)"

    cleanup_config_dir_keep_access_db

    echo "✓ Очистка завершена (база доступа сохранена)"
}

# ─── Sysctl тюнинг ───────────────────────────────────────────────────────────
setup_sysctl() {
    prog 0.20 "Sysctl..."
    echo "⚙️  Настройка сетевых параметров..."

    echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || die "failed to enable IPv4 forwarding"
    mkdir -p /etc/sysctl.d || die "failed to prepare sysctl directory"
    if ! cat > /etc/sysctl.d/99-wdtt.conf << 'SYSEOF'
net.ipv4.ip_forward = 1
SYSEOF
    then
        die "failed to write sysctl configuration"
    fi

    sysctl -p /etc/sysctl.d/99-wdtt.conf >/dev/null 2>&1 || die "failed to apply sysctl settings"

    echo "✓ Sysctl настроен"
}

# ─── Настройка NAT + Firewall ─────────────────────────────────────────────────
setup_nat_and_firewall() {
    prog 0.40 "NAT + Firewall..."
    echo "🛡  Настройка NAT и фаервола..."

    local iface
    iface=$(detect_wan_interface)

    if [ -z "$iface" ]; then
        log_warn "Не удалось определить WAN-интерфейс!"
        log_warn "Настройте NAT вручную для подсети 10.66.0.0/16."
        return 0
    fi

    log_info "WAN-интерфейс: $iface"

    # === WDTT порты ===
    fw_add_input_udp "$DTLS_PORT"   # 56000 — DTLS сервер
    fw_add_input_tcp "$DTLS_PORT"   # 56000 — API (TCP)
    fw_add_input_udp "$DIRECT_PORT" # 56002 — Direct UDP
    fw_add_input_udp "$RAW_PORT"    # 56003 — Raw TUN
    fw_add_input_udp "$WG_PORT"     # 56001 — WireGuard
    fw_add_input_tcp "$SSH_PORT"    # SSH порт, указанный пользователем в приложении

    # === Forward ===
    fw_add_forward

    # === NAT: MASQUERADE для подсети WireGuard ===
    fw_add_masquerade "$iface" "10.66.0.0/16"
    
    # === MSS Clamping для исправления MTU (DonationAlerts / Cloudflare) ===
    fw_add_mss_clamping "10.66.0.0/16"

    if [ "$FW_BACKEND" = "none" ]; then
        echo "⚠ NAT не настроен автоматически: firewall-бэкенд отсутствует"
    else
        echo "✓ NAT: MASQUERADE на $iface для 10.66.0.0/16"
    fi
    echo "✓ Порты: ${DTLS_PORT}/udp(DTLS), ${DIRECT_PORT}/udp(Direct), ${RAW_PORT}/udp(Raw), ${WG_PORT}/udp(WG), ${SSH_PORT}/tcp(SSH)"
    echo "✓ TCP MSS Clamping включен"
}

# ─── Установка бинарника wdtt-server ──────────────────────────────────────────
setup_wdtt_binary() {
    prog 0.60 "Бинарник..."
    echo "📦 Установка wdtt-server..."

    local staged_binary="/usr/local/bin/.wdtt-server.new"
    STAGED_BINARY="$staged_binary"
    rm -f -- "$staged_binary" || die "failed to prepare binary staging file"
    install -m 0755 /tmp/wdtt-server "$staged_binary" || die "failed to stage server binary"
    validate_server_binary "$staged_binary" "$NEW_SERVER_SHA256"
    mv -f "$staged_binary" /usr/local/bin/wdtt-server || die "failed to install server binary"
    STAGED_BINARY=""
    chmod 0755 /usr/local/bin/wdtt-server || die "Не удалось установить права wdtt-server."
    [ -f /usr/local/bin/wdtt-server ] || die "Установленный wdtt-server отсутствует."
    [ "$(server_sha256 /usr/local/bin/wdtt-server)" = "$NEW_SERVER_SHA256" ] || \
        die "post-install SHA mismatch"
    echo "✓ wdtt-server установлен и проверен"

    mkdir -p "$WDTT_CONFIG_DIR" || die "Не удалось подготовить каталог конфигурации"
}

# ─── Systemd-сервис WDTT ─────────────────────────────────────────────────────
setup_wdtt_service() {
    prog 0.75 "Сервис..."
    echo "🔧 Создание systemd-сервиса Hoplet..."

    local unit_tmp="/etc/systemd/system/.wdtt.service.new"
    STAGED_UNIT="$unit_tmp"
    rm -f -- "$unit_tmp" || die "failed to prepare systemd unit staging file"

    if ! cat > "$unit_tmp" << WDTTSVC
[Unit]
Description=Hoplet VPN Server
After=network.target network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStartPre=-/usr/bin/env bash -c "ip link show ${WDTT_IFACE} >/dev/null 2>&1 && ip link del ${WDTT_IFACE} 2>/dev/null || true"
ExecStartPre=-/usr/bin/env bash -c "if command -v iptables >/dev/null 2>&1; then iptables -C INPUT -p udp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p tcp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport ${DTLS_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p udp --dport ${DIRECT_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${DIRECT_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p udp --dport ${RAW_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${RAW_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p udp --dport ${WG_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport ${WG_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; iptables -C INPUT -p tcp --dport ${SSH_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport ${SSH_PORT} -m comment --comment ${IPT_COMMENT} -j ACCEPT; fi"
ExecStart=/usr/local/bin/wdtt-server -listen 0.0.0.0:${DTLS_PORT} -listen-direct 0.0.0.0:${DIRECT_PORT} -listen-raw 0.0.0.0:${RAW_PORT} -wg-port ${WG_PORT} -config-dir ${WDTT_CONFIG_DIR} ${WDTT_ARGS}
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
WDTTSVC
    then
        die "failed to create systemd unit"
    fi

    chmod 0644 "$unit_tmp" || die "failed to set systemd unit permissions"
    mv -f -- "$unit_tmp" /etc/systemd/system/wdtt.service || die "failed to install systemd unit"
    STAGED_UNIT=""

    systemctl daemon-reload >>"$LOG_FILE" 2>&1 || die "systemctl daemon-reload failed"
    systemctl unmask wdtt >/dev/null 2>&1 || die "systemctl unmask failed"
    systemctl enable wdtt >/dev/null 2>&1 || die "systemctl enable failed"
    echo "✓ Сервис wdtt.service создан и включён"
}

# ─── Запуск WDTT ─────────────────────────────────────────────────────────────
start_wdtt() {
    prog 0.90 "Запуск..."
    echo "🚀 Запуск Hoplet VPN Server..."

    [ -f /usr/local/bin/wdtt-server ] || die "wdtt-server не установлен."
    [ "$(server_sha256 /usr/local/bin/wdtt-server)" = "$NEW_SERVER_SHA256" ] || \
        die "SHA-256 установленного wdtt-server изменился до запуска."

    systemctl restart wdtt >>"$LOG_FILE" 2>&1 || die "systemctl restart failed"

    sleep 2
    local status
    if ! status=$(systemctl is-active wdtt 2>/dev/null); then
        case "$status" in
            inactive|failed|activating|deactivating|unknown)
                die "wdtt.service is not active"
                ;;
            *)
                die "systemctl is-active failed"
                ;;
        esac
    fi

    [ "$(server_sha256 /usr/local/bin/wdtt-server)" = "$NEW_SERVER_SHA256" ] || \
        die "post-start SHA mismatch"
    [ "$status" = "active" ] || {
        log_warn "wdtt.service неактивен; подробности доступны через journalctl -u wdtt"
        die "wdtt.service is not active"
    }

    prog 1.0 "Готово!"

    echo ""
    echo "══════════════════════════════════════════════════════════════"

    if [ "$status" = "active" ]; then
        echo "✅ Деплой успешно завершён!"
        echo "   NAT:  MASQUERADE (стандартный)"
        echo "   DTLS: порт ${DTLS_PORT}"
        echo "   Direct: порт ${DIRECT_PORT}"
        echo "   WG:   порт ${WG_PORT}"
        echo "   SSH:  порт ${SSH_PORT}"
    else
        echo "⚠️ Сервис wdtt не запустился. Статус: $status"
        echo "   Последние логи доступны через journalctl -u wdtt"
    fi

    echo "   Логи:   journalctl -u wdtt -f"
    echo "   Статус: systemctl status wdtt"
    echo "══════════════════════════════════════════════════════════════"
    echo ""
}

# ─── Команда: uninstall ──────────────────────────────────────────────────────
do_uninstall() {
    log_step "Удаление Hoplet..."

    if systemctl is-active --quiet wdtt 2>/dev/null; then
        systemctl stop wdtt >/dev/null 2>&1 || die "systemctl stop failed"
    fi
    if systemctl is-enabled --quiet wdtt 2>/dev/null; then
        systemctl disable wdtt >/dev/null 2>&1 || die "systemctl disable failed"
    fi
    if [ -e /etc/systemd/system/wdtt.service ]; then
        rm -f -- /etc/systemd/system/wdtt.service || die "failed to remove systemd unit"
    fi
    systemctl daemon-reload >>"$LOG_FILE" 2>&1 || die "systemctl daemon-reload failed"

    if ip link show "$WDTT_IFACE" >/dev/null 2>&1; then
        ip link del "$WDTT_IFACE" >/dev/null 2>&1 || die "failed to remove WDTT interface"
    fi
    if pgrep -x wdtt-server >/dev/null 2>&1; then
        pkill -x wdtt-server >/dev/null 2>&1 || die "failed to stop wdtt-server process"
    fi

    fw_cleanup_wdtt_rules "$(detect_wan_interface)"

    rm -f /usr/local/bin/wdtt-server
    cleanup_config_dir_keep_access_db
    rm -f /etc/sysctl.d/99-wdtt.conf
    sysctl --system >/dev/null 2>&1 || die "failed to reload sysctl settings"

    log_info "Hoplet удалён. База доступа сохранена: ${WDTT_CONFIG_DIR}/${WDTT_ACCESS_DB}"
}

# ─── Команда: status ─────────────────────────────────────────────────────────
do_status() {
    echo "Статус Hoplet:"
    echo ""
    if systemctl is-active wdtt &>/dev/null; then
        log_info "Сервис: АКТИВЕН"
    else
        log_warn "Сервис: НЕ АКТИВЕН"
    fi
    if [ -f /usr/local/bin/wdtt-server ]; then
        log_info "Бинарник: установлен"
    else
        log_warn "Бинарник: НЕ найден"
    fi
    if ip link show "$WDTT_IFACE" &>/dev/null; then
        log_info "Hoplet интерфейс ($WDTT_IFACE): активен"
    else
        log_warn "Hoplet интерфейс ($WDTT_IFACE): не активен"
    fi
}

# ══════════════════════════════════════════════════════════════════════════════
#  MAIN
# ══════════════════════════════════════════════════════════════════════════════
main() {
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║      Hoplet VPN Server — Installer v${SCRIPT_VERSION}                   ║"
    echo "║   DTLS: ${DTLS_PORT}  |  Direct: ${DIRECT_PORT}  |  Raw: ${RAW_PORT}  |  WG: ${WG_PORT}  |  SSH: ${SSH_PORT}   ║"
    echo "╚══════════════════════════════════════════════════════════════╝"

    local action="${1:-install}"
    check_root
    validate_port "WDTT_DTLS_PORT" "$DTLS_PORT"
    validate_port "WDTT_DIRECT_PORT" "$DIRECT_PORT"
    validate_port "WDTT_RAW_PORT" "$RAW_PORT"
    validate_port "WDTT_WG_PORT" "$WG_PORT"
    validate_port "WDTT_SSH_PORT" "$SSH_PORT"
    [ "$RAW_PORT" != "$DTLS_PORT" ] || die "WDTT_RAW_PORT должен отличаться от WDTT_DTLS_PORT"
    [ "$RAW_PORT" != "$DIRECT_PORT" ] || die "WDTT_RAW_PORT должен отличаться от WDTT_DIRECT_PORT"
    [ "$RAW_PORT" != "$WG_PORT" ] || die "WDTT_RAW_PORT должен отличаться от WDTT_WG_PORT"

    mkdir -p "$(dirname "$LOG_FILE")" || die "failed to prepare installer log directory"
    echo "=== Hoplet Installer v${SCRIPT_VERSION} — $(date) ===" >> "$LOG_FILE" || die "failed to write installer log"

    case "$action" in
        status|--status|-s|uninstall|--uninstall|-u) ;;
        *) validate_server_binary /tmp/wdtt-server "$WDTT_SERVER_SHA256" ;;
    esac

    detect_os
    install_prerequisites
    require_runtime_tools
    detect_firewall

    case "$action" in
        status|--status|-s)       do_status ;;
        uninstall|--uninstall|-u) do_uninstall ;;
        install|--install|-i|*)
            wdtt_cleanup
            setup_sysctl
            setup_nat_and_firewall
            setup_wdtt_binary
            setup_wdtt_service
            start_wdtt
            ;;
    esac
}

main "$@"
