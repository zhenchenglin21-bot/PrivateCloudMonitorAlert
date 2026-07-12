$outFile = 'E:\curriculum\PrivateCloudMonitorAlert\docs\thesis\chapter5-detail-diagrams-v4.drawio'

function Escape-Xml([string]$s) {
  if ($null -eq $s) { return '' }
  return [System.Security.SecurityElement]::Escape($s)
}

function Start-Dgm() {
  $script:cid = 2
  $script:cells = New-Object System.Collections.Generic.List[string]
  $script:cells.Add('<mxCell id="0"/>')
  $script:cells.Add('<mxCell id="1" parent="0"/>')
}

function Add([string]$value, [int]$x, [int]$y, [int]$w, [int]$h, [string]$style) {
  $cid = "$script:cid"
  $script:cid++
  $script:cells.Add('<mxCell id="' + $cid + '" value="' + (Escape-Xml $value) + '" style="' + $style + 'fontSize=19;" vertex="1" parent="1"><mxGeometry x="' + $x + '" y="' + $y + '" width="' + $w + '" height="' + $h + '" as="geometry"/></mxCell>')
  return $cid
}

function Frame([string]$v, [int]$x, [int]$y, [int]$w, [int]$h, [string]$fill='#f5f5f5', [string]$stroke='#666666') {
  Add $v $x $y $w $h ('rounded=0;whiteSpace=wrap;html=1;fillColor=' + $fill + ';strokeColor=' + $stroke + ';')
}

function Box([string]$v, [int]$x, [int]$y, [int]$w, [int]$h, [string]$fill='#dae8fc', [string]$stroke='#6c8ebf') {
  Add $v $x $y $w $h ('rounded=1;whiteSpace=wrap;html=1;fillColor=' + $fill + ';strokeColor=' + $stroke + ';')
}

function AddEllipse([string]$v, [int]$x, [int]$y, [int]$w, [int]$h, [string]$fill='#fff2cc', [string]$stroke='#d6b656') {
  Add $v $x $y $w $h ('ellipse;whiteSpace=wrap;html=1;fillColor=' + $fill + ';strokeColor=' + $stroke + ';')
}

function Note([string]$v, [int]$x, [int]$y, [int]$w, [int]$h) {
  Add $v $x $y $w $h 'shape=note;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;'
}

function Cylinder([string]$v, [int]$x, [int]$y, [int]$w, [int]$h, [string]$fill='#fff2cc', [string]$stroke='#d79b00') {
  Add $v $x $y $w $h ('shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=' + $fill + ';strokeColor=' + $stroke + ';size=15;')
}

function Cloud([string]$v, [int]$x, [int]$y, [int]$w, [int]$h, [string]$fill='#e1d5e7', [string]$stroke='#9673a6') {
  Add $v $x $y $w $h ('shape=cloud;whiteSpace=wrap;html=1;fillColor=' + $fill + ';strokeColor=' + $stroke + ';')
}

function Edge([string]$s, [string]$t, [string]$style='endArrow=classic;html=1;strokeWidth=2;') {
  $cid = "$script:cid"
  $script:cid++
  $script:cells.Add('<mxCell id="' + $cid + '" style="' + $style + '" edge="1" parent="1" source="' + $s + '" target="' + $t + '"><mxGeometry relative="1" as="geometry"/></mxCell>')
  return $cid
}

function End-Dgm([string]$id, [string]$name) {
  return '  <diagram id="' + $id + '" name="' + (Escape-Xml $name) + '">' + "`n" +
    '    <mxGraphModel dx="2200" dy="1600" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="2400" pageHeight="1700" math="0" shadow="0">' + "`n" +
    '      <root>' + "`n        " + ($script:cells -join "`n        ") + "`n      </root>`n    </mxGraphModel>`n  </diagram>"
}

$pages = New-Object System.Collections.Generic.List[string]

Start-Dgm
Frame '图5-1 系统详细实现综合协同图' 40 40 2250 1500 | Out-Null
Frame '表示层' 120 120 340 1250 '#f7fbff' '#6c8ebf' | Out-Null
Frame '业务层' 520 120 760 1250 '#fffaf2' '#d79b00' | Out-Null
Frame '分析与通知层' 1340 120 520 1250 '#faf7ff' '#9673a6' | Out-Null
Frame '数据层' 1920 120 250 1250 '#f8fff6' '#82b366' | Out-Null
$usr = Cloud '运维用户 / 管理员' 130 170 290 120 '#d5e8d4' '#82b366'
$login = Box '登录页 / 路由守卫' 170 370 220 80 '#dae8fc' '#6c8ebf'
$dash = Box '系统总览页' 170 500 220 80 '#dae8fc' '#6c8ebf'
$mon = Box '基础监控页' 170 630 220 80 '#dae8fc' '#6c8ebf'
$rulev = Box '规则管理页' 170 760 220 80 '#dae8fc' '#6c8ebf'
$histv = Box '告警历史页' 170 890 220 80 '#dae8fc' '#6c8ebf'
$userv = Box '用户管理页' 170 1020 220 80 '#dae8fc' '#6c8ebf'
$setv = Box '基础配置页' 170 1150 220 80 '#dae8fc' '#6c8ebf'
$auth = Box '认证与会话管理' 590 170 250 90 '#ffe6cc' '#d79b00'
$usermg = Box '用户与角色管理' 900 170 250 90 '#ffe6cc' '#d79b00'
$targetmg = Box '监控对象管理' 590 350 250 90 '#fff2cc' '#d6b656'
$rulemg = Box '规则申请 / 审核 / 生效管理' 900 350 300 90 '#fff2cc' '#d6b656'
$query = Box '监控查询封装接口' 590 560 250 90 '#dae8fc' '#6c8ebf'
$history = Box '告警历史管理' 900 560 250 90 '#dae8fc' '#6c8ebf'
$feedback = Box '反馈状态与统计管理' 590 760 280 90 '#f8cecc' '#b85450'
$enh = Box '告警记录补充分析服务' 920 760 300 90 '#f8cecc' '#b85450'
$api = Box '统一 REST API 层' 720 1020 330 100 '#e1d5e7' '#9673a6'
$collector = Box 'Metrics Collector' 1430 220 320 90 '#dae8fc' '#6c8ebf'
$alertsvc = Box 'AI Alert Service' 1430 450 320 90 '#ffe6cc' '#d79b00'
$mailsvc = Box '邮件通知与反馈轮询服务' 1430 680 320 90 '#d5e8d4' '#82b366'
$sem = Box '复杂反馈语义辅助判断' 1430 910 320 90 '#f8cecc' '#b85450'
$ext = Cloud '外部语义能力接口' 1420 1130 340 120 '#e1d5e7' '#9673a6'
$dbMysql = Cylinder 'MySQL' 1960 330 170 100 '#d5e8d4' '#82b366'
$dbInflux = Cylinder 'InfluxDB' 1960 620 170 100 '#fff2cc' '#d79b00'
$dbMail = Cylinder 'SMTP / IMAP' 1960 910 170 100 '#dae8fc' '#6c8ebf'
Note '该图用于统一覆盖第五章的大部分实现内容：前端页面、核心业务模块、智能告警、补充分析、邮件反馈和双数据库支撑。' 620 1270 1080 140 | Out-Null
foreach($page in @($login,$dash,$mon,$rulev,$histv,$userv,$setv)){ Edge $usr $page 'endArrow=open;html=1;strokeWidth=2;dashed=1;' | Out-Null; Edge $page $api | Out-Null }
foreach($mod in @($auth,$usermg,$targetmg,$rulemg,$query,$history,$feedback)){ Edge $api $mod | Out-Null }
Edge $collector $dbInflux | Out-Null
Edge $query $dbInflux | Out-Null
Edge $query $dbMysql | Out-Null
Edge $auth $dbMysql | Out-Null
Edge $usermg $dbMysql | Out-Null
Edge $targetmg $dbMysql | Out-Null
Edge $rulemg $dbMysql | Out-Null
Edge $history $dbMysql | Out-Null
Edge $feedback $dbMysql | Out-Null
Edge $alertsvc $dbInflux | Out-Null
Edge $alertsvc $history | Out-Null
Edge $history $enh | Out-Null
Edge $enh $ext 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $mailsvc $dbMail | Out-Null
Edge $mailsvc $feedback | Out-Null
Edge $mailsvc $sem | Out-Null
Edge $sem $ext 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
$pages.Add((End-Dgm 'p1' '图5-1 系统详细实现综合协同图'))

Start-Dgm
Frame '图5-2 核心领域模型类图' 40 40 2250 1500 | Out-Null
$clsUser = Box 'User&#xa;- id&#xa;- username&#xa;- passwordHash&#xa;- enabled' 180 220 260 180 '#dae8fc' '#6c8ebf'
$clsRole = Box 'Role&#xa;- id&#xa;- name' 560 120 220 140 '#ffe6cc' '#d79b00'
$clsPerm = Box 'Permission&#xa;- id&#xa;- code&#xa;- description' 900 120 260 160 '#fff2cc' '#d6b656'
$clsTarget = Box 'MonitorTarget&#xa;- host&#xa;- role&#xa;- enabled&#xa;- updatedAt' 180 620 280 180 '#d5e8d4' '#82b366'
$clsChange = Box 'MonitorTargetChange&#xa;- host&#xa;- role&#xa;- enabled&#xa;- changedBy&#xa;- changedAt' 560 620 320 200 '#e1d5e7' '#9673a6'
$clsRule = Box 'AlertRule&#xa;- id&#xa;- name&#xa;- metric&#xa;- threshold&#xa;- severity&#xa;- installed&#xa;- enabled' 980 520 320 220 '#ffe6cc' '#d79b00'
$clsReq = Box 'AlertRuleRequest&#xa;- id&#xa;- action&#xa;- status&#xa;- requestedBy&#xa;- requestedAt' 1380 520 320 200 '#fff2cc' '#d6b656'
$clsHist = Box 'AlertHistory&#xa;- host&#xa;- ruleName&#xa;- metricName&#xa;- level&#xa;- status&#xa;- reason&#xa;- recommendation&#xa;- agentPrediction&#xa;- agentAnalysis&#xa;- feedbackStatus' 1760 420 360 300 '#f8cecc' '#b85450'
$clsNotify = Box 'NotificationSettings&#xa;- emailEnabled&#xa;- recipientEmail&#xa;- smtpHost&#xa;- smtpPort&#xa;- intervalMinutes' 980 980 340 200 '#d5e8d4' '#82b366'
$clsBatch = Box 'AlertFeedbackBatchItem&#xa;- batchId&#xa;- shortCode&#xa;- alertHistoryId&#xa;- sourceMessageId' 1410 980 360 200 '#e1d5e7' '#9673a6'
Note '该图采用论文常见的领域模型表达方式，将权限、监控对象、规则、历史、通知与反馈映射到统一对象关系中。' 170 1230 980 130 | Out-Null
Edge $clsUser $clsRole | Out-Null
Edge $clsRole $clsPerm | Out-Null
Edge $clsUser $clsTarget 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $clsTarget $clsChange | Out-Null
Edge $clsRule $clsReq | Out-Null
Edge $clsRule $clsHist | Out-Null
Edge $clsReq $clsHist 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $clsNotify $clsBatch | Out-Null
Edge $clsBatch $clsHist | Out-Null
$pages.Add((End-Dgm 'p2' '图5-2 核心领域模型类图'))

Start-Dgm
Frame '图5-3 智能告警、记录增强与反馈识别联合时序图' 40 40 2250 1500 | Out-Null
$lf1 = Box 'InfluxDB' 130 110 200 70 '#fff2cc' '#d79b00'
$lf2 = Box 'AI Alert Service' 430 110 240 70 '#ffe6cc' '#d79b00'
$lf3 = Box 'AlertHistoryService' 790 110 260 70 '#dae8fc' '#6c8ebf'
$lf4 = Box '补充分析服务' 1160 110 240 70 '#f8cecc' '#b85450'
$lf5 = Box '通知与反馈服务' 1520 110 260 70 '#d5e8d4' '#82b366'
$lf6 = Box '用户 / 邮箱' 1890 110 220 70 '#e1d5e7' '#9673a6'
$sa = Box '读取窗口指标数据' 160 260 220 70 '#ffffff' '#666666'
$sb = Box '执行阈值、趋势与进程检测' 430 260 300 90 '#ffffff' '#666666'
$sc = Box '生成基础告警记录并写入历史' 810 260 280 80 '#ffffff' '#666666'
$sd = Box '对 firing 记录触发补充分析' 1170 260 250 80 '#ffffff' '#666666'
$se = Box '写回 prediction / analysis / recommendation / riskScore' 1110 420 360 90 '#ffffff' '#666666'
$sf = Box '生成邮件摘要与短码批次' 1530 260 260 80 '#ffffff' '#666666'
$sg = Box '用户查看并回复邮件' 1880 260 230 80 '#ffffff' '#666666'
$sh = Box '轮询邮箱并解析回复' 1530 420 260 80 '#ffffff' '#666666'
$si = Box '复杂文本进入辅助判断' 1530 580 260 80 '#ffffff' '#666666'
$sj = Box '更新反馈状态与反馈统计' 810 580 300 80 '#ffffff' '#666666'
$sk = Box '反馈结果参与后续参数优化' 430 740 300 80 '#ffffff' '#666666'
Note '这是一张覆盖 5.6、5.7、5.8 多个功能点的联合时序图，用于减少碎片化小图。' 1260 860 640 110 | Out-Null
Edge $sa $sb | Out-Null
Edge $sb $sc | Out-Null
Edge $sc $sd | Out-Null
Edge $sd $se 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $sc $sf | Out-Null
Edge $sf $sg 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $sg $sh | Out-Null
Edge $sh $si | Out-Null
Edge $si $sj | Out-Null
Edge $sj $sk | Out-Null
$pages.Add((End-Dgm 'p3' '图5-3 智能告警记录增强与反馈识别联合时序图'))

Start-Dgm
Frame '图5-4 告警生命周期与反馈闭环状态机图' 80 100 2140 1280 | Out-Null
$st1 = AddEllipse 'Normal' 170 620 170 90 '#dae8fc' '#6c8ebf'
$st2 = AddEllipse 'Firing' 520 620 170 90 '#ffe6cc' '#d79b00'
$st3 = AddEllipse 'Notified' 860 620 190 90 '#fff2cc' '#d6b656'
$st4 = AddEllipse 'Feedback Pending' 1230 430 230 90 '#e1d5e7' '#9673a6'
$st5 = AddEllipse 'False Positive' 1230 830 220 90 '#f8cecc' '#b85450'
$st6 = AddEllipse 'Valid Alert' 1600 430 210 90 '#d5e8d4' '#82b366'
$st7 = AddEllipse 'Resolved' 1600 830 190 90 '#dae8fc' '#6c8ebf'
$st8 = AddEllipse 'Optimized Rule Baseline' 1910 620 240 90 '#d5e8d4' '#82b366'
Note '状态迁移既包含基础告警生命周期，也体现了邮件反馈、误报判定和参数优化过程。' 700 1070 760 120 | Out-Null
Edge $st1 $st2 | Out-Null
Edge $st2 $st3 | Out-Null
Edge $st3 $st4 | Out-Null
Edge $st4 $st5 | Out-Null
Edge $st4 $st6 | Out-Null
Edge $st6 $st7 | Out-Null
Edge $st5 $st8 | Out-Null
Edge $st6 $st8 | Out-Null
Edge $st8 $st1 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
$pages.Add((End-Dgm 'p4' '图5-4 告警生命周期与反馈闭环状态机图'))

Start-Dgm
Frame '图5-5 关键服务、数据库与外部接口部署关系图' 60 60 2200 1480 | Out-Null
Frame 'Web 访问域' 140 160 430 1160 '#f7fbff' '#6c8ebf' | Out-Null
Frame '应用服务域' 650 160 900 1160 '#fffaf2' '#d79b00' | Out-Null
Frame '数据与外部资源域' 1620 160 510 1160 '#f8fff6' '#82b366' | Out-Null
$client = Cloud '浏览器客户端' 220 250 260 110 '#dae8fc' '#6c8ebf'
$frontend = Box 'Vue 3 前端' 220 470 260 90 '#d5e8d4' '#82b366'
$nginx = Box 'Nginx / 静态资源发布' 220 660 260 90 '#fff2cc' '#d6b656'
$backend = Box 'Spring Boot 后端' 760 260 310 100 '#dae8fc' '#6c8ebf'
$collector2 = Box 'Metrics Collector Service' 760 500 310 100 '#ffe6cc' '#d79b00'
$ai2 = Box 'AI Alert Service' 760 740 310 100 '#f8cecc' '#b85450'
$notify2 = Box 'Alert Notification Service' 1160 500 330 100 '#d5e8d4' '#82b366'
$assist2 = Box '补充分析 / 复杂反馈辅助' 1160 760 330 100 '#e1d5e7' '#9673a6'
$mysql2 = Cylinder 'MySQL' 1730 330 220 110 '#d5e8d4' '#82b366'
$influx2 = Cylinder 'InfluxDB' 1730 600 220 110 '#fff2cc' '#d79b00'
$mail2 = Cylinder 'SMTP / IMAP' 1730 870 220 110 '#dae8fc' '#6c8ebf'
$llm2 = Cloud '外部语义接口' 1700 1130 280 130 '#e1d5e7' '#9673a6'
Note '该图适合覆盖运行环境、关键服务分工和外部接口依赖关系。' 740 1220 820 120 | Out-Null
Edge $client $frontend | Out-Null
Edge $frontend $nginx | Out-Null
Edge $nginx $backend | Out-Null
Edge $backend $mysql2 | Out-Null
Edge $backend $influx2 | Out-Null
Edge $collector2 $influx2 | Out-Null
Edge $ai2 $influx2 | Out-Null
Edge $ai2 $backend | Out-Null
Edge $notify2 $mail2 | Out-Null
Edge $notify2 $mysql2 | Out-Null
Edge $assist2 $llm2 'endArrow=classic;html=1;strokeWidth=2;dashed=1;' | Out-Null
Edge $backend $assist2 | Out-Null
Edge $notify2 $assist2 | Out-Null
$pages.Add((End-Dgm 'p5' '图5-5 关键服务数据库与外部接口部署图'))

$content = '<mxfile host="app.diagrams.net" agent="Codex" compressed="false" pages="5">' + "`n" + ($pages -join "`n") + "`n</mxfile>"
Set-Content -Path $outFile -Value $content -Encoding UTF8
Get-Item $outFile | Select-Object FullName,Length,LastWriteTime
