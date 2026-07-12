# AI 学习型智能告警服务

该服务将原先的 `LSTM + Z-Score` 单点异常检测，升级为一个以数据驱动为核心的闭环学习型智能告警代理，完整链路如下：

1. Telegraf 持续采集服务器 CPU、内存、磁盘、网络等指标写入 InfluxDB。
2. Python 智能告警服务周期性读取时序数据，执行多层异常检测：
   - 固定阈值兜底
   - 动态阈值判断（滑动均值 / EWMA / 分位数）
   - 趋势分析（持续上升识别）
   - LSTM 预测误差辅助判别
3. 检测结果进入智能决策层：
   - 默认启发式决策
   - 可选接入通义千问兼容接口进行语义理解和决策
4. 告警执行层支持：
   - 去重与抑制
   - 恢复告警
   - 邮件通知
   - Spring Boot 后端落库
5. 用户可在后端对告警历史进行“有效告警 / 误报”反馈，服务会读取反馈摘要并自动微调阈值灵敏度。

## 目录结构

```text
ai-alert-service/
  configs/
    config.yml
  data/
  models/
  src/
    alert_manager.py
    anomaly_detect.py
    data_loader.py
    decision_engine.py
    feedback_loop.py
    model_train.py
    scheduler.py
    utils.py
    main.py
```

## 运行方式

1. 安装依赖

```bash
pip install -r requirements.txt
```

2. 配置 `configs/config.yml`

3. 启动单主机智能告警代理

```bash
python -m src.main --host <服务器名> --config configs/config.yml
```

## 核心能力

- 固定阈值：如 CPU > 90% 直接作为强规则信号。
- 动态阈值：基于近期数据波动自适应调整阈值，降低误报。
- 趋势分析：识别持续抬升等隐性异常，而不是只看瞬时点。
- 智能决策：支持启发式与大模型双模式输出“是否告警、原因、建议”。
- 去重抑制：避免短时间重复触发相同告警。
- 反馈学习：根据用户标注自动放宽或收紧阈值灵敏度。

## 与 Spring Boot 的对接

### 告警写入

服务会调用：

`POST /api/alert-history/ingest`

并写入以下上下文信息：

- 异常原因
- 处理建议
- 决策来源
- 持续时长
- 置信度
- 指纹与上下文 JSON

### 反馈学习

服务会读取：

`GET /api/alert-history/feedback-summary`

用于获取误报率 / 有效率，并动态调整固定阈值偏移量和动态阈值系数。

## 大模型接入

`decision_engine.enabled=true` 时，可通过兼容 OpenAI Chat Completions 的接口接入通义千问等模型。

示例：

```yaml
decision_engine:
  enabled: true
  provider: "qwen"
  base_url: "https://dashscope.aliyuncs.com/compatible-mode/v1"
  model: "qwen-plus"
  api_key: "YOUR_API_KEY"
```

## 说明

- 当前仍保持单主机单进程运行方式，便于部署和调试。
- 若后续要扩展为多主机统一调度，可把 `scheduler.loop()` 外提为任务分发器。
- 若需要把“反馈学习”进一步细化到每类指标或每条规则，可以继续扩展后端反馈汇总接口。
