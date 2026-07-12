# 动态阈值智能告警测试手册

本手册用于验证当前动态告警是否满足：
- 多检测器并行：`spike`（突发）+ `sustained`（持续）+ `trend`（趋势）
- 融合评分：`final_score`
- 状态机稳定流转：按最小持续次数升级/降级
- 反馈学习可调：权重与灵敏度微调

## 1. 启动与观测

1. 启动服务
```bash
cd e:/curriculum/PrivateCloudMonitorAlert/ai-alert-service
python -m src.main --host host-01 --config configs/config.yml
```

2. 建议同时开一个日志观察窗口（Linux）
```bash
tail -f /path/to/ai-alert-service.log | egrep "\[detect\]|\[reason\]|\[state\]|\[alert\]"
```

3. 重点关注日志字段
- `[reason]`: 看 `top_feature`、`threshold`、`reason`
- 动态融合详情在 `reason` 中应出现：`final_score`、`spike`、`sustained`、`trend`
- `[state]`: 看状态流转是否按最小持续次数发生，而不是瞬时抖动

## 2. 三类场景测试

## 场景A：突发异常（Spike）

目标：`spike` 分数明显升高，触发动态告警。

命令（示例）：
```bash
stress --cpu 8 --timeout 60
```

预期：
- `detector_scores.spike` 明显高于 `sustained/trend`
- `final_score` 快速上升到 `WARNING` 或 `ALERT`
- 状态流转出现：`NORMAL -> WARNING`（或更高）

## 场景B：持续异常（Sustained）

目标：中高负载持续一段时间后触发，而不是瞬间触发。

命令（示例）：
```bash
stress --cpu 4 --timeout 420
```

预期：
- 初期 `sustained` 分数逐步上升，不会立刻最高
- 达到持续窗口后 `final_score` 稳定越过阈值，触发 `WARNING/ALERT`
- 状态机按持续次数升级（不会每个周期跳变）

## 场景C：趋势异常（Trend）

目标：负载逐步恶化时，`trend` 分数上升并参与告警。

命令（示例，阶梯上升）：
```bash
stress --cpu 1 --timeout 120
sleep 20
stress --cpu 2 --timeout 120
sleep 20
stress --cpu 3 --timeout 120
sleep 20
stress --cpu 4 --timeout 120
```

预期：
- `trend` 分数随阶段逐渐升高
- 在 spike 不高的情况下，`sustained + trend` 也可推动 `final_score` 触发告警
- 状态流转具有滞后稳定性（符合最小持续次数）

## 3. 告警历史验证

通过后端接口筛选动态告警（按你的后端地址调整）：
```bash
curl -G "http://localhost:8081/api/alert-history" \
  --data-urlencode "host=host-01" \
  --data-urlencode "thresholdType=dynamic" \
  --data-urlencode "page=1" \
  --data-urlencode "size=50" \
  -H "Authorization: Bearer <TOKEN>"
```

检查点：
- `thresholdType = dynamic`
- `reason` 包含动态融合说明（`final_score`/三检测器）
- `previousState -> alertState` 符合状态机持续约束

## 4. 通过标准

- A/B/C 三场景至少各触发 1 次动态告警（非静态）
- 告警解释中能看到多检测器贡献
- 状态流转无明显抖动（不出现连续无意义来回）
- 恢复后状态可按配置逐步回落

## 5. 常见未触发原因排查

1. `no runtime targets discovered`
- 运行时监控对象未加载成功，先修复目标发现。

2. `[detect] ... no data in last 5 minutes`
- InfluxDB 没采到该对象/指标，或标签不匹配（host/query_host/role）。

3. `candidate=WARNING` 但状态不升级
- 看 `[state]` 的 `reason`，若显示 `waiting_min_duration`，说明还没满足最小持续次数（符合设计）。

4. 一直没有动态告警但 CPU 很高
- 可能是“高但稳定”，突发分数低；需观察 `sustained/trend` 分数是否在上升。
- 可先做场景A确认 spike 通道，再做场景B/C确认持续与趋势通道。

5. 告警被去重抑制
- 查看 dedup 配置 `suppression_seconds`，测试时可临时缩短。

## 6. 反馈学习验证（可选）

1. 在告警历史中给动态告警标注“有效/误报”。
2. 样本达到 `feedback_learning.min_feedback_samples` 后继续运行。
3. 观察后续日志和告警行为：
- 误报偏多：应更保守（阈值或持续要求上调倾向）
- 有效偏多：应更敏感（适度下调倾向）
- 检测器权重会做小步调整，且有上限约束。

