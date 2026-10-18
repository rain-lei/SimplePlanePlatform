# LogLens 开发约定

本项目采用 Specification-Driven Development（SDD）。架构依据为 `docs/loglens-architecture.md`，不得缩减其第一版范围。

## 工作顺序与门禁

1. 在 `docs/sdd/requirements.md` 中维护带编号的可验收需求。
2. 在 `docs/sdd/design.md` 中维护实现设计、接口和关键决策；改变契约须先修改规格。
3. 在 `docs/sdd/tasks.md` 中维护依赖顺序、需求映射和任务状态。
4. 按任务实现代码；每项需求用有意义的单元、集成或端到端验证证明。
5. 在 `docs/sdd/verification.md` 中记录实际执行结果、证据路径、限制和未完成项。没有证据不得标为通过。

用户已经授权完成全部开发，常规设计与实现不需要反复审批。规范变更需记录理由，不得以绿色测试替代架构验收。

## 环境与文件

- 不向 C 盘写入任何文件。工具使用 IDEA 配套 JDK/Maven；缓存、临时目录、测试输出位于本工作区 `.build/`。
- 不改动用户已有的架构文档修改、`.claude/`、`软件模板/`。
- Java 17 语言/API 基线；Java 17、21 兼容性必须有实际证据。
- 不擅自提交、推送、部署。所有构建从工作区脚本执行，避免默认 Maven 缓存与临时路径。
- 禁止业务日志框架输出 Agent 诊断；禁止跨线程持有原始日志事件、参数对象与 Throwable。
- API 不依赖日志框架或运行时库；bootstrap bridge 仅依赖 JDK。
