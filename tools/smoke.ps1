# 真机冒烟测试：逐个界面点开，断言关键控件**存在**且全程无崩溃。
#
# 为什么需要它：只验证「界面能打开」是不够的 —— 曾经出现过设置页少了
# 「应用/跟随今天/保存链接」等按钮（View 造出来但没 addView），界面照常打开、
# 不报错，用户却根本无法操作。所以这里断言的是控件文字。
#
# 用法：
#   pwsh -File tools\smoke.ps1
# 前置：模拟器/真机已连接，debug 包已安装。

param(
    [string]$Adb = "adb",   # 默认用 PATH 里的 adb；也可传 -Adb "<你的 platform-tools>\adb.exe"
    [string]$Package = "app.timetable.debug",
    [string]$MainActivity = "app.timetable.MainActivity",
    [string]$WorkDir = "$PSScriptRoot\..\recon"
)

# 注意：不要设 $ErrorActionPreference = "Stop" —— adb 会把提示写到 stderr，
# Windows PowerShell 5.1 下会被当成终止性错误，脚本会在第一步就挂掉。
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$script:fail = 0

function Dump([string]$name) {
    & $Adb shell uiautomator dump /sdcard/$name 2>&1 | Out-Null
    & $Adb pull /sdcard/$name "$WorkDir\$name" 2>&1 | Out-Null
    return [xml](Get-Content "$WorkDir\$name" -Encoding UTF8)
}

function Texts($doc) {
    return @($doc.SelectNodes("//node[@text!='']") | ForEach-Object {
        [string]$_.attributes.GetNamedItem("text").Value
    })
}

function CenterOf($doc, [string]$text) {
    $n = $doc.SelectNodes("//node") | Where-Object {
        ([string]$_.attributes.GetNamedItem("text").Value) -eq $text
    } | Select-Object -First 1
    if (-not $n) { return $null }
    $b = [string]$n.attributes.GetNamedItem("bounds").Value
    if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        return ,@([int](([int]$Matches[1] + [int]$Matches[3]) / 2),
                  [int](([int]$Matches[2] + [int]$Matches[4]) / 2))
    }
    return $null
}

function TapText($doc, [string]$text) {
    $c = CenterOf $doc $text
    if (-not $c) { return $false }
    & $Adb shell input tap $c[0] $c[1]
    return $true
}

function HasCrash() {
    return [bool](& $Adb logcat -d 2>&1 | Select-String "FATAL EXCEPTION")
}

function Assert-Controls([string]$screen, [string[]]$expected) {
    $doc = Dump "smoke_$([guid]::NewGuid().ToString('N').Substring(0,6)).xml"
    $texts = Texts $doc
    Write-Output "  [$screen]"
    foreach ($e in $expected) {
        if ($texts -contains $e) {
            Write-Output "    OK   $e"
        } else {
            Write-Output "    MISS $e"
            $script:fail++
        }
    }
    if (HasCrash) {
        Write-Output "    CRASH 检测到 FATAL EXCEPTION"
        $script:fail++
    }
}

# 长页面用这个：边向下滚边找，滚到底还没找到才算缺失，
# 避免「控件在屏幕外」被误报成「控件不存在」
function Assert-ControlsScrolling([string]$screen, [string[]]$expected, [int]$swipes = 5) {
    Write-Output "  [$screen]"
    $found = @{}
    for ($i = 0; $i -le $swipes; $i++) {
        $doc = Dump "smoke_scroll.xml"
        $texts = Texts $doc
        foreach ($e in $expected) {
            if (-not $found.ContainsKey($e) -and ($texts -contains $e)) { $found[$e] = $true }
        }
        if ($found.Count -ge $expected.Count) { break }
        & $Adb shell input swipe 540 1700 540 500 300 | Out-Null
        Start-Sleep -Seconds 2
    }
    foreach ($e in $expected) {
        if ($found.ContainsKey($e)) {
            Write-Output "    OK   $e"
        } else {
            Write-Output "    MISS $e"
            $script:fail++
        }
    }
    if (HasCrash) {
        Write-Output "    CRASH 检测到 FATAL EXCEPTION"
        $script:fail++
    }
}

Write-Output "=== 启动 ==="
& $Adb logcat -c
& $Adb shell am force-stop $Package | Out-Null
& $Adb shell am start -n "$Package/$MainActivity" | Out-Null
Start-Sleep -Seconds 7

# 主界面
Assert-Controls "主界面" @("同步", "本周")

# 通过 ⋮ 菜单进入各子界面（它们没有 exported，不能用 am start）
$main = Dump "smoke_main.xml"
$menuBtn = $main.SelectNodes("//node") | Where-Object {
    ([string]$_.attributes.GetNamedItem("resource-id").Value) -like "*menuBtn"
} | Select-Object -First 1
$b = [string]$menuBtn.attributes.GetNamedItem("bounds").Value
$null = $b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]'
$mcx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
$mcy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)

function OpenViaMenu([string]$label) {
    & $Adb shell input tap $mcx $mcy
    Start-Sleep -Seconds 2
    $doc = Dump "smoke_menu.xml"
    return (TapText $doc $label)
}

if (OpenViaMenu "设置") {
    Start-Sleep -Seconds 4
    Assert-Controls "设置页·上半" @(
        "保存链接", "重新登录", "立即同步", "应用", "跟随今天"
    )
    # 下半部分在屏幕外，边滚边找
    Assert-ControlsScrolling "设置页·下半" @("课表风格", "背景", "清空本地缓存")
    & $Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds 2
} else { Write-Output "  无法打开设置页"; $script:fail++ }

if (OpenViaMenu "诊断") {
    Start-Sleep -Seconds 4
    Assert-Controls "诊断页" @("重新抓取", "导出 HTML", "复制")
    & $Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Seconds 2
} else { Write-Output "  无法打开诊断页"; $script:fail++ }

if (OpenViaMenu "重新登录") {
    Start-Sleep -Seconds 6
    Assert-Controls "登录页" @("登录并导入课表")
} else { Write-Output "  无法打开登录页"; $script:fail++ }

Write-Output ""
if ($script:fail -eq 0) {
    Write-Output "全部通过：控件齐备且无崩溃"
    exit 0
} else {
    Write-Output "发现 $script:fail 处问题"
    exit 1
}
